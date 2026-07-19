package com.zeropointsix.dobaosay.doubao

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DoubaoProtoCodecTest {
    @Test
    fun `response protobuf decodes supported fields`() {
        val response =
            DoubaoAsrResponse(
                requestId = "request",
                taskId = "task",
                serviceName = "ASR",
                messageType = "SessionStarted",
                statusCode = 200,
                statusMessage = "OK",
                resultJson = """{"extra":{"packet_number":1}}""",
                unknownField9 = 7,
            )

        val decoded = DoubaoProtoCodec.decodeResponse(DoubaoProtoCodec.encodeResponseForTest(response))

        assertEquals(response, decoded)
    }

    @Test
    fun `request string redacts token and carries audio by content`() {
        val request =
            DoubaoAsrRequest(
                token = "abcdef0123456789",
                methodName = "TaskRequest",
                audioData = byteArrayOf(1, 2, 3),
                requestId = "request",
                frameState = DoubaoFrameState.FIRST,
            )
        val same = request.copy(audioData = byteArrayOf(1, 2, 3))

        assertEquals(request, same)
        assertContentEquals(byteArrayOf(1, 2, 3), request.audioData)
        assertContains(request.toString(), "abcd...6789")
        assertFalse(request.toString().contains("abcdef0123456789"))
    }
}
