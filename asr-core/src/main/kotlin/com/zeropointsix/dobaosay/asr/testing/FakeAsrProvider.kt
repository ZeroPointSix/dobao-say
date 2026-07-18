package com.zeropointsix.dobaosay.asr.testing

import com.zeropointsix.dobaosay.asr.AsrDriver
import com.zeropointsix.dobaosay.asr.AsrProvider
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.AudioFrame
import com.zeropointsix.dobaosay.asr.DriverSignal
import com.zeropointsix.dobaosay.asr.ProviderId
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class FakeAsrProvider(
    private val driverFactory: () -> FakeAsrDriver = ::FakeAsrDriver,
) : AsrProvider {
    override val id = ProviderId("fake")
    override fun createDriver(config: AsrSessionConfig): AsrDriver = driverFactory()
}

class FakeAsrDriver : AsrDriver {
    @Volatile
    private var sink: (suspend (DriverSignal) -> Unit)? = null
    val receivedFrames = Collections.synchronizedList(mutableListOf<AudioFrame>())
    val effects = Collections.synchronizedList(mutableListOf<String>())
    private val connects = AtomicInteger()
    private val stops = AtomicInteger()
    private val aborts = AtomicInteger()
    private val releases = AtomicInteger()

    val connectCount: Int get() = connects.get()
    val stopCount: Int get() = stops.get()
    val abortCount: Int get() = aborts.get()
    val releaseCount: Int get() = releases.get()

    override suspend fun connect(sink: suspend (DriverSignal) -> Unit) {
        connects.incrementAndGet()
        effects += "connect"
        this.sink = sink
    }

    override suspend fun sendAudio(frame: AudioFrame) {
        receivedFrames += frame
        effects += "push:${frame.sequence}"
    }

    override suspend fun requestStop() {
        stops.incrementAndGet()
        effects += "stop"
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
}
