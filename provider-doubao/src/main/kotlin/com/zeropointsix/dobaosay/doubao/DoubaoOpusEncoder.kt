package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AudioEncoding
import com.zeropointsix.dobaosay.asr.AudioFormat
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder

fun interface DoubaoOpusEncoder {
    fun encodePcm16Le(
        pcm: ByteArray,
        frameSizePerChannel: Int,
    ): ByteArray
}

fun interface OpusEncoderFactory {
    fun create(format: AudioFormat): DoubaoOpusEncoder

    companion object {
        fun concentus(): OpusEncoderFactory = OpusEncoderFactory { format -> ConcentusDoubaoOpusEncoder(format) }
    }
}

class ConcentusDoubaoOpusEncoder(
    private val format: AudioFormat,
    bitrate: Int = 24_000,
) : DoubaoOpusEncoder {
    private val encoder = OpusEncoder(format.sampleRateHz, format.channels, OpusApplication.OPUS_APPLICATION_VOIP)

    init {
        require(format.encoding == AudioEncoding.PCM_16_LE) { "Doubao ASR requires PCM16 LE input" }
        require(format.channels == 1) { "Doubao MVP only supports mono Opus frames" }
        require(format.sampleRateHz == 16_000) { "Doubao MVP only supports 16 kHz audio" }
        require(format.frameDurationMs == 20) { "Doubao MVP only supports 20 ms frames" }
        encoder.bitrate = bitrate
        encoder.useVBR = true
        encoder.complexity = 5
    }

    override fun encodePcm16Le(
        pcm: ByteArray,
        frameSizePerChannel: Int,
    ): ByteArray {
        require(pcm.size == frameSizePerChannel * format.channels * format.encoding.bytesPerSample) {
            "PCM frame size does not match the audio format"
        }
        val samples = ShortArray(frameSizePerChannel * format.channels)
        var byteOffset = 0
        for (index in samples.indices) {
            val low = pcm[byteOffset].toInt() and 0xff
            val high = pcm[byteOffset + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort()
            byteOffset += 2
        }

        val output = ByteArray(MAX_OPUS_PACKET_BYTES)
        val encodedSize = encoder.encode(samples, 0, frameSizePerChannel, output, 0, output.size)
        return output.copyOf(encodedSize)
    }

    private companion object {
        const val MAX_OPUS_PACKET_BYTES = 4_000
    }
}
