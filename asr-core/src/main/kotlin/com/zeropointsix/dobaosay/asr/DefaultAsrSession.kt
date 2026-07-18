package com.zeropointsix.dobaosay.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DefaultAsrSession(
    private val config: AsrSessionConfig,
    private val driver: AsrDriver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AsrSession {
    private val startedAtMs = clockMs()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private var eventSequence = 0L
    private var lastAudioSequence = -1L
    private var lastAudioTimestampMs = -1L
    private var released = false
    private var connectTimeoutJob: Job? = null
    private var finalTimeoutJob: Job? = null
    private var sessionTimeoutJob: Job? = null

    private val mutableSnapshot = MutableStateFlow(AsrSessionSnapshot())
    override val snapshot: StateFlow<AsrSessionSnapshot> = mutableSnapshot.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AsrEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch {
            for (command in commands) handle(command)
        }
    }

    override suspend fun start(): AsrCommandResult = submit { Command.Start(it) }

    override suspend fun pushAudio(frame: AudioFrame): AsrCommandResult =
        submit { Command.PushAudio(frame, it) }

    override suspend fun stop(reason: StopReason): AsrCommandResult =
        submit { Command.Stop(reason, it) }

    override suspend fun cancel(reason: CancelReason): AsrCommandResult =
        submit { Command.Cancel(reason, it) }

    override suspend fun close(): AsrCommandResult = submit { Command.Close(it) }

    suspend fun onDriverSignal(signal: DriverSignal) {
        commands.trySend(Command.Signal(signal))
    }

    private suspend fun submit(factory: (CompletableDeferred<AsrCommandResult>) -> Command): AsrCommandResult {
        val reply = CompletableDeferred<AsrCommandResult>()
        if (commands.trySend(factory(reply)).isFailure) return AsrCommandResult.IgnoredAlreadyHandled
        return reply.await()
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Start -> command.reply.complete(handleStart())
            is Command.PushAudio -> command.reply.complete(handlePushAudio(command.frame))
            is Command.Stop -> command.reply.complete(handleStop(command.reason))
            is Command.Cancel -> command.reply.complete(handleCancel(command.reason))
            is Command.Close -> command.reply.complete(handleClose())
            is Command.Signal -> handleSignal(command.signal)
            is Command.Timeout -> handleTimeout(command.phase)
        }
    }

    private suspend fun handleStart(): AsrCommandResult {
        if (mutableSnapshot.value.state != AsrSessionState.Created) return rejected("start")
        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Connecting)
        publish { sequence, elapsed -> AsrEvent.Connecting(sequence, elapsed) }
        scheduleTimeout(TimeoutPhase.CONNECT, config.connectTimeout)
        scheduleTimeout(TimeoutPhase.SESSION, config.sessionTimeout)
        try {
            driver.connect(::onDriverSignal)
        } catch (_: Exception) {
            commitTerminal(SessionOutcome.Failed(AsrFailure.Internal("driver_connect")), abort = false)
        }
        return AsrCommandResult.Accepted
    }

    private suspend fun handlePushAudio(frame: AudioFrame): AsrCommandResult {
        val state = mutableSnapshot.value.state
        if (state != AsrSessionState.Ready && state != AsrSessionState.Streaming) return rejected("pushAudio")
        validateFrame(frame)?.let { return AsrCommandResult.Rejected(it) }

        lastAudioSequence = frame.sequence
        lastAudioTimestampMs = frame.timestampMs
        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Streaming)
        try {
            driver.sendAudio(frame)
        } catch (_: Exception) {
            commitTerminal(SessionOutcome.Failed(AsrFailure.Internal("driver_send_audio")), abort = false)
        }
        return AsrCommandResult.Accepted
    }

    private suspend fun handleStop(reason: StopReason): AsrCommandResult =
        when (mutableSnapshot.value.state) {
            AsrSessionState.Ready,
            AsrSessionState.Streaming,
            -> {
                mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Stopping(reason))
                scheduleTimeout(TimeoutPhase.FINAL, config.stopFinalTimeout)
                requestStopOrFail()
                AsrCommandResult.Accepted
            }
            is AsrSessionState.Stopping,
            is AsrSessionState.Closed,
            -> AsrCommandResult.IgnoredAlreadyHandled
            else -> rejected("stop")
        }

    private suspend fun handleCancel(reason: CancelReason): AsrCommandResult {
        if (mutableSnapshot.value.state is AsrSessionState.Closed) {
            return AsrCommandResult.IgnoredAlreadyHandled
        }
        commitTerminal(SessionOutcome.Cancelled(reason), abort = true)
        return AsrCommandResult.Accepted
    }

    private suspend fun handleClose(): AsrCommandResult {
        if (mutableSnapshot.value.state is AsrSessionState.Closed) {
            return AsrCommandResult.IgnoredAlreadyHandled
        }
        commitTerminal(SessionOutcome.ClosedWithoutResult, abort = false)
        return AsrCommandResult.Accepted
    }

    private suspend fun handleSignal(signal: DriverSignal) {
        if (mutableSnapshot.value.state is AsrSessionState.Closed) return
        when (signal) {
            DriverSignal.Ready -> {
                if (mutableSnapshot.value.state != AsrSessionState.Connecting) {
                    protocolFailure("ready_out_of_order")
                } else {
                    cancelTimeout(TimeoutPhase.CONNECT)
                    mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Ready)
                    publish { sequence, elapsed -> AsrEvent.Ready(sequence, elapsed) }
                }
            }
            DriverSignal.SpeechStarted -> {
                if (!isReadyOrStreaming()) {
                    protocolFailure("speech_start_out_of_order")
                } else {
                    mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Streaming)
                    publish { sequence, elapsed -> AsrEvent.SpeechStarted(sequence, elapsed) }
                }
            }
            is DriverSignal.Partial -> {
                if (!isReadyOrStreaming()) {
                    protocolFailure("partial_out_of_order")
                } else {
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        state = AsrSessionState.Streaming,
                        partialText = signal.text,
                    )
                    publish { sequence, elapsed ->
                        AsrEvent.Partial(signal.utteranceId, signal.text, signal.revision, sequence, elapsed)
                    }
                }
            }
            is DriverSignal.Final -> {
                if (!canAcceptFinal()) {
                    protocolFailure("final_out_of_order")
                } else {
                    cancelTimeout(TimeoutPhase.FINAL)
                    val success = SessionOutcome.Succeeded(signal.resultId, signal.text)
                    mutableSnapshot.value = mutableSnapshot.value.copy(finalResult = success)
                    publish { sequence, elapsed ->
                        AsrEvent.Final(signal.resultId, signal.utteranceId, signal.text, sequence, elapsed)
                    }
                    commitTerminal(success, abort = false)
                }
            }
            DriverSignal.SpeechEnded -> {
                if (isReadyOrStreaming()) {
                    mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Stopping(StopReason.VAD))
                    publish { sequence, elapsed -> AsrEvent.SpeechEnded(sequence, elapsed) }
                    scheduleTimeout(TimeoutPhase.FINAL, config.stopFinalTimeout)
                    requestStopOrFail()
                }
            }
            is DriverSignal.Retrying -> publish { sequence, elapsed ->
                AsrEvent.Retrying(signal.attempt, signal.failure, sequence, elapsed)
            }
            is DriverSignal.Failed -> commitTerminal(SessionOutcome.Failed(signal.failure), abort = false)
            DriverSignal.RemoteClosed -> protocolFailure("remote_closed_before_final")
        }
    }

    private suspend fun handleTimeout(phase: TimeoutPhase) {
        val applies = when (phase) {
            TimeoutPhase.CONNECT -> mutableSnapshot.value.state == AsrSessionState.Connecting
            TimeoutPhase.FINAL -> mutableSnapshot.value.state is AsrSessionState.Stopping
            TimeoutPhase.SESSION -> mutableSnapshot.value.state !is AsrSessionState.Closed
        }
        if (applies) {
            commitTerminal(SessionOutcome.Failed(AsrFailure.Timeout(phase)), abort = true)
        }
    }

    private fun scheduleTimeout(phase: TimeoutPhase, duration: kotlin.time.Duration) {
        cancelTimeout(phase)
        val job = scope.launch {
            delay(duration)
            commands.trySend(Command.Timeout(phase))
        }
        when (phase) {
            TimeoutPhase.CONNECT -> connectTimeoutJob = job
            TimeoutPhase.FINAL -> finalTimeoutJob = job
            TimeoutPhase.SESSION -> sessionTimeoutJob = job
        }
    }

    private fun cancelTimeout(phase: TimeoutPhase) {
        when (phase) {
            TimeoutPhase.CONNECT -> connectTimeoutJob.also { connectTimeoutJob = null }
            TimeoutPhase.FINAL -> finalTimeoutJob.also { finalTimeoutJob = null }
            TimeoutPhase.SESSION -> sessionTimeoutJob.also { sessionTimeoutJob = null }
        }?.cancel()
    }

    private fun cancelAllTimeouts() {
        TimeoutPhase.entries.forEach(::cancelTimeout)
    }

    private suspend fun requestStopOrFail() {
        try {
            driver.requestStop()
        } catch (_: Exception) {
            commitTerminal(SessionOutcome.Failed(AsrFailure.Internal("driver_stop")), abort = false)
        }
    }

    private suspend fun protocolFailure(code: String) {
        commitTerminal(SessionOutcome.Failed(AsrFailure.ProtocolViolation(code)), abort = false)
    }

    private suspend fun commitTerminal(outcome: SessionOutcome, abort: Boolean) {
        if (mutableSnapshot.value.state is AsrSessionState.Closed) return
        cancelAllTimeouts()
        if (outcome is SessionOutcome.Failed) {
            publish { sequence, elapsed -> AsrEvent.Error(outcome.failure, sequence, elapsed) }
        }
        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Closed(outcome))
        publish { sequence, elapsed -> AsrEvent.Closed(outcome, sequence, elapsed) }

        if (abort) {
            try {
                driver.abort()
            } catch (_: Exception) {
                // The terminal outcome is already committed; abort failure cannot replace it.
            }
        }
        if (!released) {
            released = true
            try {
                driver.release()
            } catch (_: Exception) {
                // Release is attempted exactly once and cannot replace the committed outcome.
            }
        }
        commands.close()
    }

    private fun isReadyOrStreaming(): Boolean =
        mutableSnapshot.value.state == AsrSessionState.Ready ||
            mutableSnapshot.value.state == AsrSessionState.Streaming

    private fun canAcceptFinal(): Boolean =
        isReadyOrStreaming() || mutableSnapshot.value.state is AsrSessionState.Stopping

    private fun validateFrame(frame: AudioFrame): AsrFailure.InvalidAudio? {
        if (frame.bytes.size != config.audioFormat.bytesPerFrame) {
            return AsrFailure.InvalidAudio("frame_size")
        }
        if (frame.sequence <= lastAudioSequence) return AsrFailure.InvalidAudio("sequence_not_monotonic")
        if (frame.timestampMs < lastAudioTimestampMs) return AsrFailure.InvalidAudio("timestamp_not_monotonic")
        return null
    }

    private fun rejected(command: String): AsrCommandResult.Rejected =
        AsrCommandResult.Rejected(AsrFailure.InvalidState(command, mutableSnapshot.value.state))

    private suspend fun publish(factory: (Long, Long) -> AsrEvent) {
        eventSequence += 1
        mutableEvents.emit(factory(eventSequence, (clockMs() - startedAtMs).coerceAtLeast(0)))
    }

    private sealed interface Command {
        data class Start(val reply: CompletableDeferred<AsrCommandResult>) : Command
        data class PushAudio(val frame: AudioFrame, val reply: CompletableDeferred<AsrCommandResult>) : Command
        data class Stop(val reason: StopReason, val reply: CompletableDeferred<AsrCommandResult>) : Command
        data class Cancel(val reason: CancelReason, val reply: CompletableDeferred<AsrCommandResult>) : Command
        data class Close(val reply: CompletableDeferred<AsrCommandResult>) : Command
        data class Signal(val signal: DriverSignal) : Command
        data class Timeout(val phase: TimeoutPhase) : Command
    }
}
