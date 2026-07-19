package com.zeropointsix.dobaosay.infra

import android.annotation.SuppressLint
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.zeropointsix.dobaosay.asr.AudioEncoding
import com.zeropointsix.dobaosay.asr.AudioFormat
import kotlin.math.max

/**
 * Provider-neutral PCM microphone capture for 16-bit mono frames.
 *
 * Callers own threading and must hold RECORD_AUDIO before [start].
 */
class MicPcmCapture(
    private val format: AudioFormat = AudioFormat(),
) : AutoCloseable {
    private var recorder: AudioRecord? = null

    val bytesPerFrame: Int
        get() = format.bytesPerFrame

    val audioFormat: AudioFormat
        get() = format

    @SuppressLint("MissingPermission")
    fun start() {
        check(recorder == null) { "MicPcmCapture already started" }
        require(format.encoding == AudioEncoding.PCM_16_LE)
        require(format.channels == 1)

        val channelMask = AndroidAudioFormat.CHANNEL_IN_MONO
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(format.sampleRateHz, channelMask, encoding)
        require(minBufferSize > 0) { "unsupported AudioRecord format: $format" }
        val bufferSize = max(minBufferSize, format.bytesPerFrame * 4)
        val built =
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AndroidAudioFormat
                        .Builder()
                        .setEncoding(encoding)
                        .setSampleRate(format.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        check(built.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        built.startRecording()
        recorder = built
    }

    /**
     * Reads exactly one frame. Returns null when capture was stopped mid-read.
     */
    fun readFrame(shouldContinue: () -> Boolean = { true }): ByteArray? {
        val active = recorder ?: return null
        val size = format.bytesPerFrame
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size && shouldContinue()) {
            val read = active.read(bytes, offset, size - offset, AudioRecord.READ_BLOCKING)
            if (read < 0) {
                throw IllegalStateException("AudioRecord read failed: $read")
            }
            if (read == 0) continue
            offset += read
        }
        return if (offset == size) bytes else null
    }

    fun stop() {
        val active = recorder ?: return
        runCatching {
            if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                active.stop()
            }
        }
    }

    override fun close() {
        val active = recorder ?: return
        recorder = null
        runCatching {
            if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                active.stop()
            }
        }
        active.release()
    }
}
