package com.zeropointsix.dobaosay.asr.simulation

import com.zeropointsix.dobaosay.asr.AsrDriver
import com.zeropointsix.dobaosay.asr.AsrFailure
import com.zeropointsix.dobaosay.asr.AudioFrame
import com.zeropointsix.dobaosay.asr.DriverSignal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

internal enum class SimulatedDriverMode {
    SUCCESS,
    CONNECT_TIMEOUT,
    FINAL_TIMEOUT,
    DRIVER_FAILED,
}

/** Test-only scripted driver. Its fixed text is not produced by speech recognition. */
internal class SimulatedTranscriptionDriver(
    private val mode: SimulatedDriverMode,
    private val scenarioId: String,
) : AsrDriver {
    private lateinit var sink: suspend (DriverSignal) -> Unit
    private val connects = AtomicInteger()
    private val stops = AtomicInteger()
    private val aborts = AtomicInteger()
    private val releases = AtomicInteger()
    private val activeCalls = AtomicInteger()
    private val partials = AtomicInteger()

    val effects = Collections.synchronizedList(mutableListOf<String>())
    val receivedFrames = Collections.synchronizedList(mutableListOf<AudioFrame>())
    val released = CompletableDeferred<Unit>()

    val connectCount: Int get() = connects.get()
    val stopCount: Int get() = stops.get()
    val abortCount: Int get() = aborts.get()
    val releaseCount: Int get() = releases.get()
    val activeCallCount: Int get() = activeCalls.get()

    override suspend fun connect(sink: suspend (DriverSignal) -> Unit) =
        tracked {
            check(connects.incrementAndGet() == 1)
            effects += "connect"
            this.sink = sink
            if (mode == SimulatedDriverMode.CONNECT_TIMEOUT) {
                awaitCancellation()
            } else {
                sink(DriverSignal.Ready)
            }
        }

    override suspend fun sendAudio(frame: AudioFrame) =
        tracked {
            receivedFrames += frame
            effects += "audio:${frame.sequence}"
            if (partials.compareAndSet(0, 1)) {
                sink(DriverSignal.SpeechStarted)
                sink(
                    DriverSignal.Partial(
                        utteranceId = "utterance-$scenarioId",
                        text = "SIMULATED_PARTIAL_$scenarioId",
                        revision = 1,
                    ),
                )
                if (mode == SimulatedDriverMode.DRIVER_FAILED) {
                    sink(DriverSignal.Failed(AsrFailure.NetworkUnavailable))
                }
            }
        }

    override suspend fun requestStop() =
        tracked {
            check(stops.incrementAndGet() == 1)
            effects += "stop"
            if (mode == SimulatedDriverMode.SUCCESS) {
                sink(
                    DriverSignal.Final(
                        resultId = "result-$scenarioId",
                        utteranceId = "utterance-$scenarioId",
                        text = "SIMULATED_FINAL_$scenarioId",
                    ),
                )
            }
        }

    override suspend fun abort() =
        tracked {
            check(aborts.incrementAndGet() == 1)
            effects += "abort"
        }

    override suspend fun release() =
        tracked {
            check(releases.incrementAndGet() == 1)
            effects += "release"
            released.complete(Unit)
            Unit
        }

    suspend fun emitSpeechEnded() {
        sink(DriverSignal.SpeechEnded)
    }

    private suspend fun <T> tracked(block: suspend () -> T): T {
        activeCalls.incrementAndGet()
        return try {
            block()
        } finally {
            activeCalls.decrementAndGet()
        }
    }
}
