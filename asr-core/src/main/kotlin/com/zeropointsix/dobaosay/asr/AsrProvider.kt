package com.zeropointsix.dobaosay.asr

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface AsrProvider {
    val id: ProviderId
    fun createDriver(config: AsrSessionConfig): AsrDriver
}

interface AsrDriver {
    suspend fun connect(sink: suspend (DriverSignal) -> Unit)
    suspend fun sendAudio(frame: AudioFrame)
    suspend fun requestStop()
    suspend fun abort()
    suspend fun release()
}

interface AsrSession {
    val snapshot: StateFlow<AsrSessionSnapshot>
    val events: SharedFlow<AsrEvent>

    suspend fun start(): AsrCommandResult
    suspend fun pushAudio(frame: AudioFrame): AsrCommandResult
    suspend fun stop(reason: StopReason = StopReason.MANUAL): AsrCommandResult
    suspend fun cancel(reason: CancelReason): AsrCommandResult
    suspend fun close(): AsrCommandResult
}
