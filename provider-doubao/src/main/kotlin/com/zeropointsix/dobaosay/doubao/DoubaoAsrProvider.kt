package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AsrDriver
import com.zeropointsix.dobaosay.asr.AsrProvider
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.ProviderId

class DoubaoAsrProvider(
    private val config: DoubaoProviderConfig = DoubaoProviderConfig(),
) : AsrProvider {
    override val id: ProviderId = ProviderId("doubao-ime")

    override fun createDriver(config: AsrSessionConfig): AsrDriver =
        DoubaoAsrDriver(
            DoubaoDriverRuntimeConfig(
                audioFormat = config.audioFormat,
                providerConfig = this.config,
            ),
        )
}
