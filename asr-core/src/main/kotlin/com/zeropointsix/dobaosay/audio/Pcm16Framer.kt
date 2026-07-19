package com.zeropointsix.dobaosay.audio

import com.zeropointsix.dobaosay.asr.AudioEncoding
import com.zeropointsix.dobaosay.asr.AudioFormat
import com.zeropointsix.dobaosay.asr.AudioFrame

enum class TailFramePolicy {
    DROP,
    PAD_WITH_ZERO,
}

data class PcmFramerStats(
    val emittedFrames: Long,
    val droppedTailBytes: Int,
    val paddedTailBytes: Int,
)

/**
 * Stateful clean-room PCM16 framer. Input chunks may split samples or frame boundaries.
 *
 * The default format deterministically emits 20 ms, 640-byte frames. [finish] either drops the
 * incomplete tail or pads it with zeroes, as selected by [tailPolicy].
 */
class Pcm16Framer(
    val format: AudioFormat = AudioFormat(),
    private val tailPolicy: TailFramePolicy = TailFramePolicy.DROP,
    firstSequence: Long = 0,
    firstTimestampMs: Long = 0,
) {
    private var pending = ByteArray(0)
    private var nextSequence = firstSequence
    private var nextTimestampMs = firstTimestampMs
    private var finished = false
    private var emittedFrames = 0L
    private var droppedTailBytes = 0
    private var paddedTailBytes = 0

    init {
        require(format.encoding == AudioEncoding.PCM_16_LE) { "Only PCM16 little-endian input is supported" }
        require(format.frameDurationMs == 20) { "Only deterministic 20 ms framing is supported" }
        require(firstSequence >= 0) { "First sequence must be non-negative" }
        require(firstTimestampMs >= 0) { "First timestamp must be non-negative" }
    }

    val stats: PcmFramerStats
        get() = PcmFramerStats(emittedFrames, droppedTailBytes, paddedTailBytes)

    fun push(bytes: ByteArray): List<AudioFrame> {
        check(!finished) { "Framer is already finished" }
        if (bytes.isEmpty()) return emptyList()

        val combined = ByteArray(Math.addExact(pending.size, bytes.size))
        pending.copyInto(combined)
        bytes.copyInto(combined, pending.size)

        val frameSize = format.bytesPerFrame
        val frameCount = combined.size / frameSize
        if (frameCount == 0) {
            pending = combined
            return emptyList()
        }

        val frames = ArrayList<AudioFrame>(frameCount)
        repeat(frameCount) { index ->
            val start = index * frameSize
            frames += newFrame(combined.copyOfRange(start, start + frameSize))
        }
        pending = combined.copyOfRange(frameCount * frameSize, combined.size)
        return frames
    }

    fun finish(): List<AudioFrame> {
        if (finished) return emptyList()
        finished = true
        if (pending.isEmpty()) return emptyList()

        return when (tailPolicy) {
            TailFramePolicy.DROP -> {
                droppedTailBytes = pending.size
                pending = ByteArray(0)
                emptyList()
            }

            TailFramePolicy.PAD_WITH_ZERO -> {
                val tailSize = pending.size
                val padded = pending.copyOf(format.bytesPerFrame)
                paddedTailBytes = format.bytesPerFrame - tailSize
                pending = ByteArray(0)
                listOf(newFrame(padded))
            }
        }
    }

    private fun newFrame(bytes: ByteArray): AudioFrame {
        val frame = AudioFrame(nextSequence, nextTimestampMs, bytes, format)
        nextSequence = Math.incrementExact(nextSequence)
        nextTimestampMs = Math.addExact(nextTimestampMs, format.frameDurationMs.toLong())
        emittedFrames = Math.incrementExact(emittedFrames)
        return frame
    }
}
