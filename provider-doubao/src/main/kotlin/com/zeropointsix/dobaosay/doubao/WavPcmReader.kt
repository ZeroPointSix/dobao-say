package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

data class WavPcmData(
    val audioFormat: AudioFormat,
    val pcm: ByteArray,
)

object WavPcmReader {
    fun read(
        path: Path,
        expectedFormat: AudioFormat = AudioFormat(),
    ): WavPcmData {
        val bytes = Files.readAllBytes(path)
        require(bytes.size >= 44) { "Not a WAV file: too small" }
        require(bytes.ascii(0, 4) == "RIFF") { "Not a WAV file: missing RIFF header" }
        require(bytes.ascii(8, 12) == "WAVE") { "Not a WAV file: missing WAVE marker" }

        var offset = 12
        var pcmFormat = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var data: ByteArray? = null

        while (offset + 8 <= bytes.size) {
            val chunkId = bytes.ascii(offset, offset + 4)
            val chunkSize = bytes.uint32Le(offset + 4)
            offset += 8
            require(offset + chunkSize <= bytes.size) { "Invalid WAV chunk size" }

            when (chunkId) {
                "fmt " -> {
                    pcmFormat = bytes.uint16Le(offset)
                    channels = bytes.uint16Le(offset + 2)
                    sampleRate = bytes.uint32Le(offset + 4)
                    bitsPerSample = bytes.uint16Le(offset + 14)
                }

                "data" -> {
                    data = bytes.copyOfRange(offset, offset + chunkSize)
                }
            }

            offset += chunkSize
            if (chunkSize % 2 != 0) offset += 1
        }

        require(pcmFormat == 1) { "Unsupported WAV format $pcmFormat; only PCM is supported" }
        require(bitsPerSample == 16) { "Unsupported WAV bit depth $bitsPerSample; only 16-bit is supported" }
        require(sampleRate == expectedFormat.sampleRateHz) {
            "WAV sample rate is $sampleRate Hz; expected ${expectedFormat.sampleRateHz} Hz"
        }
        require(channels == expectedFormat.channels) {
            "WAV channel count is $channels; expected ${expectedFormat.channels}"
        }

        return WavPcmData(expectedFormat, data ?: error("WAV file has no data chunk"))
    }

    private fun ByteArray.ascii(
        start: Int,
        end: Int,
    ): String = copyOfRange(start, end).toString(Charsets.US_ASCII)

    private fun ByteArray.uint16Le(offset: Int): Int =
        ByteBuffer
            .wrap(this, offset, 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt() and 0xffff

    private fun ByteArray.uint32Le(offset: Int): Int = ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
