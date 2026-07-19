package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AudioFormat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DoubaoCredentialAndOpusTest {
    @Test
    fun `credentials toString redacts token`() {
        val credentials =
            DoubaoCredentials(
                deviceId = "device-1234567890",
                installId = "install-1234567890",
                cdid = "cdid-1234567890",
                openudid = "open-1234567890",
                clientudid = "client-1234567890",
                token = "token-1234567890",
            )

        assertContains(credentials.toString(), "toke...7890")
        assertFalse(credentials.toString().contains("token-1234567890"))
        assertFalse(credentials.toString().contains("device-1234567890"))
        assertFalse(credentials.toString().contains("install-1234567890"))
        assertFalse(credentials.toString().contains("cdid-1234567890"))
        assertFalse(credentials.toString().contains("open-1234567890"))
        assertFalse(credentials.toString().contains("client-1234567890"))
    }

    @Test
    fun `concentus encoder produces an opus packet for one silent frame`() {
        val format = AudioFormat(sampleRateHz = 16_000, channels = 1, frameDurationMs = 20)
        val encoder = ConcentusDoubaoOpusEncoder(format)
        val packet = encoder.encodePcm16Le(ByteArray(format.bytesPerFrame), 320)

        assertTrue(packet.isNotEmpty())
    }
}
