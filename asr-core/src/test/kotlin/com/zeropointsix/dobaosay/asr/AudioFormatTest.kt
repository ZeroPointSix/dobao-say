package com.zeropointsix.dobaosay.asr

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFormatTest {
    @Test
    fun `frame bytes include channel count`() {
        assertEquals(640, AudioFormat(channels = 1).bytesPerFrame)
        assertEquals(1_280, AudioFormat(channels = 2).bytesPerFrame)
    }

    @Test
    fun `audio frame toString never exposes bytes`() {
        val frame = AudioFrame(7, 140, "secret-audio".encodeToByteArray())
        assertEquals("AudioFrame(sequence=7, timestampMs=140, byteCount=12)", frame.toString())
    }
}
