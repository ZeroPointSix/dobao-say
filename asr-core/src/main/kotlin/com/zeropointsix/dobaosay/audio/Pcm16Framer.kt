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
 * incomplete tail or pads it with zeroes, as selected by [tailPolicy]. Every mutation is
 * transactional: all counters, timestamps and frames are validated before state is committed.
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

        val plan = planFrames(combined, frameCount)
        pending = combined.copyOfRange(frameCount * frameSize, combined.size)
        commit(plan)
        return plan.frames
    }

    fun finish(): List<AudioFrame> {
        if (finished) return emptyList()
        if (pending.isEmpty()) {
            finished = true
            return emptyList()
        }

        return when (tailPolicy) {
            TailFramePolicy.DROP -> {
                val tailSize = pending.size
                pending = ByteArray(0)
                droppedTailBytes = tailSize
                finished = true
                emptyList()
            }

            TailFramePolicy.PAD_WITH_ZERO -> {
                val tailSize = pending.size
                val padded = pending.copyOf(format.bytesPerFrame)
                val plan = planFrames(padded, 1)
                pending = ByteArray(0)
                paddedTailBytes = format.bytesPerFrame - tailSize
                finished = true
                commit(plan)
                plan.frames
            }
        }
    }

    private fun planFrames(
        source: ByteArray,
        frameCount: Int,
    ): FramePlan {
        require(frameCount > 0)
        val count = frameCount.toLong()
        val finalSequence = Math.addExact(nextSequence, count)
        val timestampAdvance = Math.multiplyExact(format.frameDurationMs.toLong(), count)
        val finalTimestamp = Math.addExact(nextTimestampMs, timestampAdvance)
        val finalEmittedFrames = Math.addExact(emittedFrames, count)
        val frameSize = format.bytesPerFrame
        val frames =
            List(frameCount) { index ->
                val offset = index * frameSize
                AudioFrame(
                    sequence = Math.addExact(nextSequence, index.toLong()),
                    timestampMs =
                        Math.addExact(
                            nextTimestampMs,
                            Math.multiplyExact(format.frameDurationMs.toLong(), index.toLong()),
                        ),
                    bytes = source.copyOfRange(offset, offset + frameSize),
                    format = format,
                )
            }
        return FramePlan(frames, finalSequence, finalTimestamp, finalEmittedFrames)
    }

    private fun commit(plan: FramePlan) {
        nextSequence = plan.finalSequence
        nextTimestampMs = plan.finalTimestampMs
        emittedFrames = plan.finalEmittedFrames
    }

    private data class FramePlan(
        val frames: List<AudioFrame>,
        val finalSequence: Long,
        val finalTimestampMs: Long,
        val finalEmittedFrames: Long,
    )
}
