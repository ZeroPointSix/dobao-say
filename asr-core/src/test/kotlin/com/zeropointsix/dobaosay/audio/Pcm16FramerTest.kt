package com.zeropointsix.dobaosay.audio

import com.zeropointsix.dobaosay.asr.AudioFormat
import com.zeropointsix.dobaosay.asr.AudioFrame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Pcm16FramerTest {
    @Test
    fun `audio frame owns constructor and getter bytes`() {
        val input = ByteArray(640) { it.toByte() }
        val expected = input.copyOf()
        val frame = AudioFrame(7, 140, input, AudioFormat())

        input.fill(0)
        val exposed = frame.bytes
        exposed.fill(1)

        assertContentEquals(expected, frame.bytes)
        assertEquals(AudioFormat(), frame.format)
        assertEquals(640, frame.byteCount)
    }

    @Test
    fun `audio frame rejects illegal metadata and empty payload`() {
        assertFailsWith<IllegalArgumentException> { AudioFrame(-1, 0, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { AudioFrame(0, -1, ByteArray(1)) }
        assertFailsWith<IllegalArgumentException> { AudioFrame(0, 0, ByteArray(0)) }
    }

    @Test
    fun `empty and 639 bytes emit no frame and drop tail`() {
        val framer = Pcm16Framer()

        assertTrue(framer.push(ByteArray(0)).isEmpty())
        assertTrue(framer.push(ByteArray(639)).isEmpty())
        assertTrue(framer.finish().isEmpty())
        assertEquals(PcmFramerStats(0, 639, 0), framer.stats)
        assertTrue(framer.finish().isEmpty())
    }

    @Test
    fun `640 bytes emit exactly one deterministic frame`() {
        val bytes = ByteArray(640) { (it % 127).toByte() }
        val frame = Pcm16Framer(firstSequence = 5, firstTimestampMs = 100).push(bytes).single()

        assertEquals(5, frame.sequence)
        assertEquals(100, frame.timestampMs)
        assertEquals(AudioFormat(), frame.format)
        assertContentEquals(bytes, frame.bytes)
    }

    @Test
    fun `641 bytes emit one frame and retain one-byte tail`() {
        val framer = Pcm16Framer()
        val frames = framer.push(ByteArray(641) { it.toByte() })

        assertEquals(1, frames.size)
        assertEquals(640, frames.single().byteCount)
        assertTrue(framer.finish().isEmpty())
        assertEquals(PcmFramerStats(1, 1, 0), framer.stats)
    }

    @Test
    fun `continuous chunks preserve bytes sequence and monotonic time`() {
        val input = ByteArray(1_920) { (it % 251).toByte() }
        val framer = Pcm16Framer()
        val frames =
            buildList {
                addAll(framer.push(input.copyOfRange(0, 639)))
                addAll(framer.push(input.copyOfRange(639, 640)))
                addAll(framer.push(input.copyOfRange(640, 1_281)))
                addAll(framer.push(input.copyOfRange(1_281, input.size)))
            }

        assertEquals(listOf(0L, 1L, 2L), frames.map(AudioFrame::sequence))
        assertEquals(listOf(0L, 20L, 40L), frames.map(AudioFrame::timestampMs))
        assertContentEquals(input, frames.flatMap { it.bytes.asIterable() }.toByteArray())
        assertTrue(framer.finish().isEmpty())
        assertEquals(PcmFramerStats(3, 0, 0), framer.stats)
    }

    @Test
    fun `padding policy emits one zero-padded tail frame`() {
        val framer = Pcm16Framer(tailPolicy = TailFramePolicy.PAD_WITH_ZERO)
        framer.push(byteArrayOf(1, 2, 3))

        val tail = framer.finish().single()

        assertEquals(640, tail.byteCount)
        assertContentEquals(byteArrayOf(1, 2, 3), tail.bytes.copyOfRange(0, 3))
        assertTrue(tail.bytes.copyOfRange(3, 640).all { it == 0.toByte() })
        assertEquals(PcmFramerStats(1, 0, 637), framer.stats)
        assertFailsWith<IllegalStateException> { framer.push(byteArrayOf(4)) }
    }
}
