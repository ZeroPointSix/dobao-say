package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.DriverSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DoubaoResponseMapperTest {
    @Test
    fun `vad start maps to speech started`() {
        val events =
            DoubaoResponseMapper().map(
                DoubaoAsrResponse(
                    resultJson = """{"extra":{"vad_start":true}}""",
                ),
            )

        val signal = assertIs<DoubaoResponseEvent.Signal>(events.single()).signal
        assertEquals(DriverSignal.SpeechStarted, signal)
    }

    @Test
    fun `interim and final results map to driver signals`() {
        val mapper = DoubaoResponseMapper()
        val partialEvents =
            mapper.map(
                DoubaoAsrResponse(
                    resultJson = """{"results":[{"text":"中间","is_interim":true,"index":3}],"extra":{}}""",
                ),
            )
        val finalEvents =
            mapper.map(
                DoubaoAsrResponse(
                    resultJson =
                        """{"results":[{"text":"最终","is_interim":false,"is_vad_finished":true,"index":3}]}""",
                ),
            )

        val partial = assertIs<DriverSignal.Partial>(assertIs<DoubaoResponseEvent.Signal>(partialEvents.single()).signal)
        assertEquals("中间", partial.text)
        assertEquals(1L, partial.revision)

        assertEquals(2, finalEvents.size)
        assertEquals(DriverSignal.SpeechEnded, assertIs<DoubaoResponseEvent.Signal>(finalEvents[0]).signal)
        val final = assertIs<DriverSignal.Final>(assertIs<DoubaoResponseEvent.Signal>(finalEvents[1]).signal)
        assertEquals("最终", final.text)
        assertEquals("doubao-3-final", final.resultId)
    }
}
