package com.zeropointsix.dobaosay.asr.testing

import com.zeropointsix.dobaosay.asr.AsrDriver
import com.zeropointsix.dobaosay.asr.AsrProvider
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.AudioFrame
import com.zeropointsix.dobaosay.asr.DriverSignal
import com.zeropointsix.dobaosay.asr.ProviderId

class FakeAsrProvider(
    private val driverFactory: () -> FakeAsrDriver = ::FakeAsrDriver,
) : AsrProvider {
    override val id = ProviderId("fake")
    override fun createDriver(config: AsrSessionConfig): AsrDriver = driverFactory()
}

class FakeAsrDriver : AsrDriver {
    private var sink: (suspend (DriverSignal) -> Unit)? = null
    val receivedFrames = mutableListOf<AudioFrame>()
    var connectCount = 0
        private set
    var stopCount = 0
        private set
    var abortCount = 0
        private set
    var releaseCount = 0
        private set

    override suspend fun connect(sink: suspend (DriverSignal) -> Unit) {
        connectCount += 1
        this.sink = sink
    }

    override suspend fun sendAudio(frame: AudioFrame) {
        receivedFrames += frame
    }

    override suspend fun requestStop() {
        stopCount += 1
    }

    override suspend fun abort() {
        abortCount += 1
    }

    override suspend fun release() {
        releaseCount += 1
    }

    suspend fun emit(signal: DriverSignal) {
        checkNotNull(sink) { "Fake driver has not been connected" }(signal)
    }
}
