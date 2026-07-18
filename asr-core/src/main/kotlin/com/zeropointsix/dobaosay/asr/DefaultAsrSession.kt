package com.zeropointsix.dobaosay.asr

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DefaultAsrSession(
    private val config: AsrSessionConfig,
    private val driver: AsrDriver,
    private val clockMs: () -> Long = System::currentTimeMillis,
) : AsrSession {
    private val mutex = Mutex()
    private val startedAtMs = clockMs()
    private val released = AtomicBoolean(false)
    private val acceptedResultIds = LinkedHashSet<String>()
    private var eventSequence = 0L
    private var lastAudioSequence = -1L
    private var lastAudioTimestampMs = -1L

    private val mutableSnapshot = MutableStateFlow(AsrSessionSnapshot())
    override val snapshot: StateFlow<AsrSessionSnapshot> = mutableSnapshot.asStateFlow()

    private val mutableEvents = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<AsrEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(): AsrCommandResult {
        val result = mutex.withLock {
            if (mutableSnapshot.value.state != AsrSessionState.Created) {
                return@withLock rejected("start")
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Connecting)
            publish { sequence, elapsed -> AsrEvent.Connecting(sequence, elapsed) }
            AsrCommandResult.Accepted
        }
        if (result != AsrCommandResult.Accepted) return result

        try {
            driver.connect(::onDriverSignal)
        } catch (cancelled: CancellationException) {
            terminate(SessionOutcome.Cancelled(CancelReason.SUPERSEDED))
            throw cancelled
        } catch (_: Exception) {
            onDriverSignal(DriverSignal.Failed(AsrFailure.Internal("driver_connect")))
        }
        return result
    }

    override suspend fun pushAudio(frame: AudioFrame): AsrCommandResult {
        val validationFailure = validateFrame(frame)
        if (validationFailure != null) return AsrCommandResult.Rejected(validationFailure)

        val result = mutex.withLock {
            when (mutableSnapshot.value.state) {
                AsrSessionState.Ready,
                AsrSessionState.Streaming,
                -> {
                    mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Streaming)
                    lastAudioSequence = frame.sequence
                    lastAudioTimestampMs = frame.timestampMs
                    AsrCommandResult.Accepted
                }
                else -> rejected("pushAudio")
            }
        }
        if (result != AsrCommandResult.Accepted) return result

        try {
            driver.sendAudio(frame)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onDriverSignal(DriverSignal.Failed(AsrFailure.Internal("driver_send_audio")))
        }
        return result
    }

    override suspend fun stop(reason: StopReason): AsrCommandResult {
        val result = mutex.withLock {
            when (mutableSnapshot.value.state) {
                AsrSessionState.Ready,
                AsrSessionState.Streaming,
                -> {
                    mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Stopping(reason))
                    AsrCommandResult.Accepted
                }
                is AsrSessionState.Stopping,
                is AsrSessionState.Closed,
                -> AsrCommandResult.IgnoredAlreadyHandled
                else -> rejected("stop")
            }
        }
        if (result == AsrCommandResult.Accepted) driver.requestStopSafely()
        return result
    }

    override suspend fun cancel(reason: CancelReason): AsrCommandResult {
        val result = mutex.withLock {
            if (mutableSnapshot.value.state is AsrSessionState.Closed) {
                AsrCommandResult.IgnoredAlreadyHandled
            } else {
                AsrCommandResult.Accepted
            }
        }
        if (result != AsrCommandResult.Accepted) return result

        try {
            driver.abort()
        } finally {
            terminate(SessionOutcome.Cancelled(reason))
        }
        return result
    }

    override suspend fun close(): AsrCommandResult {
        val alreadyClosed = mutex.withLock { mutableSnapshot.value.state is AsrSessionState.Closed }
        if (alreadyClosed) {
            releaseOnce()
            return AsrCommandResult.IgnoredAlreadyHandled
        }
        terminate(SessionOutcome.ClosedWithoutResult)
        return AsrCommandResult.Accepted
    }

    suspend fun onDriverSignal(signal: DriverSignal) {
        var terminalOutcome: SessionOutcome? = null
        mutex.withLock {
            if (mutableSnapshot.value.state is AsrSessionState.Closed) return
            when (signal) {
                DriverSignal.Ready -> {
                    if (mutableSnapshot.value.state != AsrSessionState.Connecting) {
                        terminalOutcome = SessionOutcome.Failed(AsrFailure.ProtocolViolation("ready_out_of_order"))
                    } else {
                        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Ready)
                        publish { sequence, elapsed -> AsrEvent.Ready(sequence, elapsed) }
                    }
                }
                DriverSignal.SpeechStarted -> {
                    if (mutableSnapshot.value.state !in setOf(AsrSessionState.Ready, AsrSessionState.Streaming)) {
                        terminalOutcome = SessionOutcome.Failed(AsrFailure.ProtocolViolation("speech_start_out_of_order"))
                    } else {
                        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Streaming)
                        publish { sequence, elapsed -> AsrEvent.SpeechStarted(sequence, elapsed) }
                    }
                }
                is DriverSignal.Partial -> {
                    if (mutableSnapshot.value.state !in setOf(AsrSessionState.Ready, AsrSessionState.Streaming)) {
                        terminalOutcome = SessionOutcome.Failed(AsrFailure.ProtocolViolation("partial_out_of_order"))
                    } else {
                        mutableSnapshot.value = mutableSnapshot.value.copy(partialText = signal.text)
                        publish { sequence, elapsed ->
                            AsrEvent.Partial(signal.utteranceId, signal.text, signal.revision, sequence, elapsed)
                        }
                    }
                }
                is DriverSignal.Final -> {
                    if (mutableSnapshot.value.state !in setOf(
                            AsrSessionState.Ready,
                            AsrSessionState.Streaming,
                            AsrSessionState.Stopping(StopReason.MANUAL),
                            AsrSessionState.Stopping(StopReason.VAD),
                        )
                    ) {
                        terminalOutcome = SessionOutcome.Failed(AsrFailure.ProtocolViolation("final_out_of_order"))
                    } else if (acceptedResultIds.add(signal.resultId)) {
                        val success = SessionOutcome.Succeeded(signal.resultId, signal.text)
                        mutableSnapshot.value = mutableSnapshot.value.copy(finalResult = success)
                        publish { sequence, elapsed ->
                            AsrEvent.Final(signal.resultId, signal.utteranceId, signal.text, sequence, elapsed)
                        }
                        terminalOutcome = success
                    }
                }
                DriverSignal.SpeechEnded -> {
                    val state = mutableSnapshot.value.state
                    if (state == AsrSessionState.Ready || state == AsrSessionState.Streaming) {
                        mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Stopping(StopReason.VAD))
                        publish { sequence, elapsed -> AsrEvent.SpeechEnded(sequence, elapsed) }
                    }
                }
                is DriverSignal.Retrying -> publish { sequence, elapsed ->
                    AsrEvent.Retrying(signal.attempt, signal.failure, sequence, elapsed)
                }
                is DriverSignal.Failed -> terminalOutcome = SessionOutcome.Failed(signal.failure)
                DriverSignal.RemoteClosed -> terminalOutcome = SessionOutcome.ClosedWithoutResult
            }
        }
        terminalOutcome?.let { terminate(it) }
    }

    private fun validateFrame(frame: AudioFrame): AsrFailure.InvalidAudio? {
        if (frame.bytes.size != config.audioFormat.bytesPerFrame) {
            return AsrFailure.InvalidAudio("frame_size")
        }
        if (frame.sequence <= lastAudioSequence) return AsrFailure.InvalidAudio("sequence_not_monotonic")
        if (frame.timestampMs < lastAudioTimestampMs) return AsrFailure.InvalidAudio("timestamp_not_monotonic")
        return null
    }

    private suspend fun terminate(outcome: SessionOutcome) {
        var changed = false
        mutex.withLock {
            if (mutableSnapshot.value.state !is AsrSessionState.Closed) {
                changed = true
                if (outcome is SessionOutcome.Failed) {
                    publish { sequence, elapsed -> AsrEvent.Error(outcome.failure, sequence, elapsed) }
                }
                mutableSnapshot.value = mutableSnapshot.value.copy(state = AsrSessionState.Closed(outcome))
                publish { sequence, elapsed -> AsrEvent.Closed(outcome, sequence, elapsed) }
            }
        }
        if (changed || !released.get()) releaseOnce()
    }

    private suspend fun releaseOnce() {
        if (released.compareAndSet(false, true)) driver.release()
    }

    private suspend fun AsrDriver.requestStopSafely() {
        try {
            requestStop()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onDriverSignal(DriverSignal.Failed(AsrFailure.Internal("driver_stop")))
        }
    }

    private fun rejected(command: String): AsrCommandResult.Rejected =
        AsrCommandResult.Rejected(AsrFailure.InvalidState(command, mutableSnapshot.value.state))

    private suspend fun publish(factory: (Long, Long) -> AsrEvent) {
        eventSequence += 1
        mutableEvents.emit(factory(eventSequence, (clockMs() - startedAtMs).coerceAtLeast(0)))
    }
}
