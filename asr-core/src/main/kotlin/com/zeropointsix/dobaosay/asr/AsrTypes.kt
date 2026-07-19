package com.zeropointsix.dobaosay.asr

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@JvmInline
value class ProviderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Provider ID must not be blank" }
    }
}

data class AsrSessionConfig(
    val audioFormat: AudioFormat = AudioFormat(),
    val connectTimeout: Duration = 10.seconds,
    val stopFinalTimeout: Duration = 10.seconds,
    val sessionTimeout: Duration = 5.minutes,
) {
    init {
        require(connectTimeout.isPositive()) { "Connect timeout must be positive" }
        require(stopFinalTimeout.isPositive()) { "Stop timeout must be positive" }
        require(sessionTimeout.isPositive()) { "Session timeout must be positive" }
    }
}

data class AudioFormat(
    val sampleRateHz: Int = 16_000,
    val channels: Int = 1,
    val frameDurationMs: Int = 20,
    val encoding: AudioEncoding = AudioEncoding.PCM_16_LE,
) {
    init {
        require(sampleRateHz in 8_000..48_000) { "Unsupported sample rate" }
        require(channels in 1..2) { "Unsupported channel count" }
        require(frameDurationMs in setOf(10, 20, 40, 60)) { "Unsupported frame duration" }
        require(Math.multiplyExact(sampleRateHz, frameDurationMs) % 1_000 == 0) {
            "Sample rate and frame duration must produce a whole sample count"
        }
    }

    val bytesPerFrame: Int
        get() {
            val samplesPerChannel = Math.multiplyExact(sampleRateHz, frameDurationMs) / 1_000
            return Math.multiplyExact(
                Math.multiplyExact(samplesPerChannel, channels),
                encoding.bytesPerSample,
            )
        }
}

enum class AudioEncoding(
    val bytesPerSample: Int,
) {
    PCM_16_LE(2),
}

/**
 * Immutable audio value. Both constructor input and every [bytes] read are copied so callers cannot
 * mutate frame contents after construction.
 *
 * [timestampMs] is elapsed monotonic time relative to the producing stream, never wall-clock time.
 */
class AudioFrame(
    val sequence: Long,
    val timestampMs: Long,
    bytes: ByteArray,
    val format: AudioFormat = AudioFormat(),
) {
    private val pcmBytes = bytes.copyOf()

    val bytes: ByteArray
        get() = pcmBytes.copyOf()

    val byteCount: Int
        get() = pcmBytes.size

    init {
        require(sequence >= 0) { "Audio sequence must be non-negative" }
        require(timestampMs >= 0) { "Audio timestamp must be non-negative" }
        require(bytes.isNotEmpty()) { "Audio bytes must not be empty" }
    }

    fun copyBytes(): ByteArray = pcmBytes.copyOf()

    override fun toString(): String =
        "AudioFrame(sequence=$sequence, timestampMs=$timestampMs, byteCount=$byteCount, format=$format)"
}

enum class StopReason { MANUAL, VAD }

enum class CancelReason { USER, APP_BACKGROUNDED, SUPERSEDED }

sealed interface AsrCommandResult {
    data object Accepted : AsrCommandResult

    data object IgnoredAlreadyHandled : AsrCommandResult

    data class Rejected(
        val failure: AsrFailure,
    ) : AsrCommandResult
}
