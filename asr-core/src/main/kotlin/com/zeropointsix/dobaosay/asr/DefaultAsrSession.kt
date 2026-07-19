package com.zeropointsix.dobaosay.asr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DefaultAsrSession(
    private val config: AsrSessionConfig,
    private val driver: AsrDriver,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AsrSession {
    private val startedAtMs = clockMs()
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val effects = Channel<DriverEffect>(Channel.UNLIMITED)
    private val terminalCommitted = AtomicBoolean(false)
    private val cleanupScheduled = AtomicBoolean(false)
    private val activeEffectJob = AtomicReference<Job?>(null)
    private var eventSequence = 0L
    private var lastAudioSequence = -1L
    private var lastAudioTimestampMs = -1L
    private var connectTimeoutJob: Job? = null
    private var finalTimeoutJob: Job? = null
    private var sessionTimeoutJob: Job? = null

    private val mutableSnapshot = MutableStateFlow(AsrSessionSnapshot())
    override val snapshot: StateFlow<AsrSessionSnapshot> = mutableSnapshot.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AsrEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch { runEffectWorker() }
        scope.launch { runActor() }
    }

    override suspend fun start(): AsrCommandResult = submit { Command.Start(it) }

    override suspend fun pushAudio(frame: AudioFrame): AsrCommandResult = submit { Command.PushAudio(frame, it) }

    override suspend fun stop(reason: StopReason): AsrCommandResult = submit { Command.Stop(reason, it) }

    override suspend fun cancel(reason: CancelReason): AsrCommandResult = submit { Command.Cancel(reason, it) }

    override suspend fun close(): AsrCommandResult = submit { Command.Close(it) }

    suspend fun onDriverSignal(signal: DriverSignal) {
        commands.trySend(Command.Signal(signal))
    }

    private suspend fun submit(factory: (CompletableDeferred<AsrCommandResult>) -> Command): AsrCommandResult {
        val reply = CompletableDeferred<AsrCommandResult>()
        if (commands.trySend(factory(reply)).isFailure) return AsrCommandResult.IgnoredAlreadyHandled
        return reply.await()
    }

    private suspend fun runActor() {
        var current: Command? = null
        try {
            for (command in commands) {
                current = command
                handle(command)
                current = null
            }
        } catch (_: CancellationException) {
            throw CancellationException("ASR session actor cancelled")
        } catch (_: Throwable) {
            current?.completeIfPending(AsrCommandResult.IgnoredAlreadyHandled)
            commitTerminal(SessionOutcome.Failed(AsrFailure.Internal("session_actor")), abort = true)
        } finally {
            current?.completeIfPending(AsrCommandResult.IgnoredAlreadyHandled)
            commands.close()
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                pending.completeIfPending(AsrCommandResult.IgnoredAlreadyHandled)
            }
            effects.close()
        }
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Command.Start -> {
                command.reply.complete(handleStart())
            }

            is Command.PushAudio -> {
                command.reply.complete(handlePushAudio(command.frame))
            }

            is Command.Stop -> {
                command.reply.complete(handleStop(command.reason))
            }

            is Command.Cancel -> {
                command.reply.complete(handleCancel(command.reason))
            }

            is Command.Close -> {
                command.reply.complete(handleClose())
            }

            is Command.Signal -> {
                handleSignal(command.signal)
            }

            is Command.Timeout -> {
                handleTimeout(command.phase)
            }

            is Command.EffectFailed -> {
                try {
                    handleEffectFailure(command.effect)
                } finally {
                    command.handled.complete(Unit)
                }
            }
        }
    }

    private suspend fun handleStart(): AsrCommandResult {
        if (mutableSnapshot.value.state != AsrSessionState.Created) return rejected("start")
        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Connecting)
        publish { sequence, elapsed -> AsrEvent.Connecting(sequence, elapsed) }
        scheduleTimeout(TimeoutPhase.CONNECT, config.connectTimeout)
        scheduleTimeout(TimeoutPhase.SESSION, config.sessionTimeout)
        enqueueEffect(DriverEffect.Connect)
        return AsrCommandResult.Accepted
    }

    private suspend fun handlePushAudio(frame: AudioFrame): AsrCommandResult {
        val state = mutableSnapshot.value.state
        if (state != AsrSessionState.Ready && state != AsrSessionState.Streaming) return rejected("pushAudio")
        validateFrame(frame)?.let { return AsrCommandResult.Rejected(it) }

        lastAudioSequence = frame.sequence
        lastAudioTimestampMs = frame.timestampMs
        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Streaming)
        enqueueEffect(DriverEffect.SendAudio(frame))
        return AsrCommandResult.Accepted
    }

    private suspend fun handleStop(reason: StopReason): AsrCommandResult =
        when (mutableSnapshot.value.state) {
            AsrSessionState.Ready,
            AsrSessionState.Streaming,
            -> {
                mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Stopping(reason))
                scheduleTimeout(TimeoutPhase.FINAL, config.stopFinalTimeout)
                enqueueEffect(DriverEffect.RequestStop)
                AsrCommandResult.Accepted
            }

            is AsrSessionState.Stopping,
            is AsrSessionState.Closed,
            -> {
                AsrCommandResult.IgnoredAlreadyHandled
            }

            else -> {
                rejected("stop")
            }
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
                    mutableSnapshot.value =
                        mutableSnapshot.value.copy(
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
                    enqueueEffect(DriverEffect.RequestStop)
                }
            }

            is DriverSignal.Retrying -> {
                publish { sequence, elapsed ->
                    AsrEvent.Retrying(signal.attempt, signal.failure, sequence, elapsed)
                }
            }

            is DriverSignal.Failed -> {
                commitTerminal(SessionOutcome.Failed(signal.failure), abort = false)
            }

            DriverSignal.RemoteClosed -> {
                protocolFailure("remote_closed_before_final")
            }
        }
    }

    private suspend fun handleTimeout(phase: TimeoutPhase) {
        val applies =
            when (phase) {
                TimeoutPhase.CONNECT -> mutableSnapshot.value.state == AsrSessionState.Connecting
                TimeoutPhase.FINAL -> mutableSnapshot.value.state is AsrSessionState.Stopping
                TimeoutPhase.SESSION -> mutableSnapshot.value.state !is AsrSessionState.Closed
            }
        if (applies) {
            commitTerminal(SessionOutcome.Failed(AsrFailure.Timeout(phase)), abort = true)
        }
    }

    private suspend fun handleEffectFailure(effect: DriverEffect) {
        if (mutableSnapshot.value.state is AsrSessionState.Closed) return
        val code =
            when (effect) {
                DriverEffect.Connect -> "driver_connect"
                is DriverEffect.SendAudio -> "driver_send_audio"
                DriverEffect.RequestStop -> "driver_stop"
                is DriverEffect.Cleanup -> return
            }
        commitTerminal(SessionOutcome.Failed(AsrFailure.Internal(code)), abort = false)
    }

    private fun scheduleTimeout(
        phase: TimeoutPhase,
        duration: kotlin.time.Duration,
    ) {
        cancelTimeout(phase)
        val job =
            scope.launch {
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

    private suspend fun protocolFailure(code: String) {
        commitTerminal(SessionOutcome.Failed(AsrFailure.ProtocolViolation(code)), abort = false)
    }

    private suspend fun commitTerminal(
        outcome: SessionOutcome,
        abort: Boolean,
    ) {
        if (!terminalCommitted.compareAndSet(false, true)) return
        cancelAllTimeouts()
        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Closed(outcome))
        if (outcome is SessionOutcome.Failed) {
            publish { sequence, elapsed -> AsrEvent.Error(outcome.failure, sequence, elapsed) }
        }
        publish { sequence, elapsed -> AsrEvent.Closed(outcome, sequence, elapsed) }

        activeEffectJob.getAndSet(null)?.cancel()
        if (cleanupScheduled.compareAndSet(false, true)) {
            enqueueEffect(DriverEffect.Cleanup(abort))
        }
        commands.close()
    }

    private fun enqueueEffect(effect: DriverEffect) {
        if (effects.trySend(effect).isFailure && effect !is DriverEffect.Cleanup) {
            commands.trySend(Command.EffectFailed(effect, CompletableDeferred()))
        }
    }

    private suspend fun runEffectWorker() {
        try {
            for (effect in effects) {
                if (terminalCommitted.get() && effect !is DriverEffect.Cleanup) continue
                val job = scope.launch(start = CoroutineStart.LAZY) { executeEffect(effect) }
                activeEffectJob.set(job)
                if (terminalCommitted.get() && effect !is DriverEffect.Cleanup) {
                    job.cancel()
                } else {
                    job.start()
                }
                job.join()
                activeEffectJob.compareAndSet(job, null)
            }
        } finally {
            activeEffectJob.getAndSet(null)?.cancel()
        }
    }

    private suspend fun executeEffect(effect: DriverEffect) {
        try {
            when (effect) {
                DriverEffect.Connect -> {
                    driver.connect(::onDriverSignal)
                }

                is DriverEffect.SendAudio -> {
                    driver.sendAudio(effect.frame)
                }

                DriverEffect.RequestStop -> {
                    driver.requestStop()
                }

                is DriverEffect.Cleanup -> {
                    if (effect.abort) {
                        try {
                            driver.abort()
                        } catch (_: Exception) {
                            // The terminal outcome is already committed; abort failure cannot replace it.
                        }
                    }
                    try {
                        driver.release()
                    } catch (_: Exception) {
                        // Release is attempted exactly once and cannot replace the committed outcome.
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!terminalCommitted.get()) {
                val handled = CompletableDeferred<Unit>()
                if (commands.trySend(Command.EffectFailed(effect, handled)).isSuccess) handled.await()
            }
        }
    }

    private fun isReadyOrStreaming(): Boolean =
        mutableSnapshot.value.state == AsrSessionState.Ready ||
            mutableSnapshot.value.state == AsrSessionState.Streaming

    private fun canAcceptFinal(): Boolean = isReadyOrStreaming() || mutableSnapshot.value.state is AsrSessionState.Stopping

    private fun validateFrame(frame: AudioFrame): AsrFailure.InvalidAudio? {
        if (frame.format != config.audioFormat) {
            return AsrFailure.InvalidAudio("frame_format")
        }
        if (frame.byteCount != config.audioFormat.bytesPerFrame) {
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

    private sealed interface DriverEffect {
        data object Connect : DriverEffect

        data class SendAudio(
            val frame: AudioFrame,
        ) : DriverEffect

        data object RequestStop : DriverEffect

        data class Cleanup(
            val abort: Boolean,
        ) : DriverEffect
    }

    private sealed interface Command {
        data class Start(
            val reply: CompletableDeferred<AsrCommandResult>,
        ) : Command

        data class PushAudio(
            val frame: AudioFrame,
            val reply: CompletableDeferred<AsrCommandResult>,
        ) : Command

        data class Stop(
            val reason: StopReason,
            val reply: CompletableDeferred<AsrCommandResult>,
        ) : Command

        data class Cancel(
            val reason: CancelReason,
            val reply: CompletableDeferred<AsrCommandResult>,
        ) : Command

        data class Close(
            val reply: CompletableDeferred<AsrCommandResult>,
        ) : Command

        data class Signal(
            val signal: DriverSignal,
        ) : Command

        data class Timeout(
            val phase: TimeoutPhase,
        ) : Command

        data class EffectFailed(
            val effect: DriverEffect,
            val handled: CompletableDeferred<Unit>,
        ) : Command

        fun completeIfPending(result: AsrCommandResult) {
            when (this) {
                is Start -> reply.complete(result)

                is PushAudio -> reply.complete(result)

                is Stop -> reply.complete(result)

                is Cancel -> reply.complete(result)

                is Close -> reply.complete(result)

                is Signal,
                is Timeout,
                -> Unit

                is EffectFailed -> handled.complete(Unit)
            }
        }
    }
}
