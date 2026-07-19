package com.zeropointsix.dobaosay.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Pcm16FramerOverflowTest {
    @Test
    fun `maximum first sequence rejects frame without mutation`() {
        val framer = Pcm16Framer(firstSequence = Long.MAX_VALUE)

        assertFailsWith<ArithmeticException> { framer.push(ByteArray(640)) }

        assertEquals(PcmFramerStats(0, 0, 0), framer.stats)
        assertTrue(framer.finish().isEmpty())
        assertEquals(PcmFramerStats(0, 0, 0), framer.stats)
    }

    @Test
    fun `timestamp overflow rejects frame without mutation`() {
        val framer = Pcm16Framer(firstTimestampMs = Long.MAX_VALUE - 10)

        assertFailsWith<ArithmeticException> { framer.push(ByteArray(640)) }

        assertEquals(PcmFramerStats(0, 0, 0), framer.stats)
        assertTrue(framer.finish().isEmpty())
    }

    @Test
    fun `multi-frame sequence overflow commits neither frame nor input`() {
        val framer = Pcm16Framer(firstSequence = Long.MAX_VALUE - 1)

        assertFailsWith<ArithmeticException> { framer.push(ByteArray(1_280)) }
        assertEquals(PcmFramerStats(0, 0, 0), framer.stats)

        val retry = framer.push(ByteArray(640)).single()
        assertEquals(Long.MAX_VALUE - 1, retry.sequence)
        assertEquals(PcmFramerStats(1, 0, 0), framer.stats)
        assertFailsWith<ArithmeticException> { framer.push(ByteArray(640)) }
        assertEquals(PcmFramerStats(1, 0, 0), framer.stats)
    }

    @Test
    fun `overflow with prior tail does not duplicate new input`() {
        val framer = Pcm16Framer(firstSequence = Long.MAX_VALUE)
        assertTrue(framer.push(ByteArray(639)).isEmpty())

        assertFailsWith<ArithmeticException> { framer.push(byteArrayOf(1)) }
        assertEquals(PcmFramerStats(0, 0, 0), framer.stats)

        assertTrue(framer.finish().isEmpty())
        assertEquals(PcmFramerStats(0, 639, 0), framer.stats)
    }

    @Test
    fun `padding overflow leaves finish retry predictable`() {
        val framer =
            Pcm16Framer(
                tailPolicy = TailFramePolicy.PAD_WITH_ZERO,
                firstTimestampMs = Long.MAX_VALUE - 10,
            )
        framer.push(byteArrayOf(1))

        assertFailsWith<ArithmeticException> { framer.finish() }
        assertFailsWith<ArithmeticException> { framer.finish() }
        assertEquals(PcmFramerStats(0, 0, 0), framer.stats)
    }

    @Test
    fun `single valid boundary commit is followed by predictable overflow`() {
        val framer = Pcm16Framer(firstTimestampMs = Long.MAX_VALUE - 20)

        val frame = framer.push(ByteArray(640)).single()

        assertEquals(Long.MAX_VALUE - 20, frame.timestampMs)
        assertEquals(PcmFramerStats(1, 0, 0), framer.stats)
        assertFailsWith<ArithmeticException> { framer.push(ByteArray(640)) }
        assertEquals(PcmFramerStats(1, 0, 0), framer.stats)
    }
}
