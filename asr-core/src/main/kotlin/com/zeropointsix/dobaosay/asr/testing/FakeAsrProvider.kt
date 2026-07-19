package com.zeropointsix.dobaosay.asr.testing

import com.zeropointsix.dobaosay.asr.AsrDriver
import com.zeropointsix.dobaosay.asr.AsrProvider
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.AudioFrame
import com.zeropointsix.dobaosay.asr.DriverSignal
import com.zeropointsix.dobaosay.asr.ProviderId
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlin.time.Duration

class FakeAsrProvider(
    private val driverFactory: () -> FakeAsrDriver = ::FakeAsrDriver,
) : AsrProvider {
    override val id = ProviderId("fake")
    override fun createDriver(config: AsrSessionConfig): AsrDriver = driverFactory()
}

enum class FakeDriverTrigger { CONNECT, AUDIO, STOP }

sealed interface FakeDriverStep {
    data class Emit(val signal: DriverSignal) : FakeDriverStep
    data class Delay(val duration: Duration) : FakeDriverStep
    data class Throw(val exception: RuntimeException = IllegalStateException("scripted failure")) : FakeDriverStep
    data class Gate(val gate: Deferred<Unit>) : FakeDriverStep
}

class FakeAsrDriver(
    script: Map<FakeDriverTrigger, List<FakeDriverStep>> = emptyMap(),
) : AsrDriver {
    @Volatile
    private var sink: (suspend (DriverSignal) -> Unit)? = null
    val receivedFrames = Collections.synchronizedList(mutableListOf<AudioFrame>())
    val effects = Collections.synchronizedList(mutableListOf<String>())
    private val connects = AtomicInteger()
    private val stops = AtomicInteger()
    private val aborts = AtomicInteger()
    private val releases = AtomicInteger()
    private val scripts = script.mapValuesTo(mutableMapOf()) { (_, steps) -> ConcurrentLinkedQueue(steps) }

    val connectCount: Int get() = connects.get()
    val stopCount: Int get() = stops.get()
    val abortCount: Int get() = aborts.get()
    val releaseCount: Int get() = releases.get()

    override suspend fun connect(sink: suspend (DriverSignal) -> Unit) {
        connects.incrementAndGet()
        effects += "connect"
        this.sink = sink
        runScript(FakeDriverTrigger.CONNECT)
    }

    override suspend fun sendAudio(frame: AudioFrame) {
        receivedFrames += frame
        effects += "push:${frame.sequence}"
        runScript(FakeDriverTrigger.AUDIO)
    }

    override suspend fun requestStop() {
        stops.incrementAndGet()
        effects += "stop"
        runScript(FakeDriverTrigger.STOP)
    }

    override suspend fun abort() {
        aborts.incrementAndGet()
        effects += "abort"
    }

    override suspend fun release() {
        releases.incrementAndGet()
        effects += "release"
    }

    suspend fun emit(signal: DriverSignal) {
        checkNotNull(sink) { "Fake driver has not been connected" }(signal)
    }

    private suspend fun runScript(trigger: FakeDriverTrigger) {
        val steps = scripts[trigger] ?: return
        while (true) {
            when (val step = steps.poll() ?: return) {
                is FakeDriverStep.Emit -> emit(step.signal)
                is FakeDriverStep.Delay -> delay(step.duration)
                is FakeDriverStep.Throw -> throw step.exception
                is FakeDriverStep.Gate -> step.gate.await()
            }
        }
    }
}
