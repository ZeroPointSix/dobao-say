package com.zeropointsix.dobaosay.doubao

import java.io.ByteArrayOutputStream

internal enum class DoubaoFrameState(
    val wireValue: Int,
) {
    UNSPECIFIED(0),
    FIRST(1),
    MIDDLE(3),
    LAST(9),
}

internal data class DoubaoAsrRequest(
    val token: String = "",
    val serviceName: String = "ASR",
    val methodName: String,
    val payload: String = "",
    val audioData: ByteArray = ByteArray(0),
    val requestId: String,
    val frameState: DoubaoFrameState = DoubaoFrameState.UNSPECIFIED,
) {
    override fun equals(other: Any?): Boolean =
        when {
            this === other -> true
            other !is DoubaoAsrRequest -> false
            token != other.token -> false
            serviceName != other.serviceName -> false
            methodName != other.methodName -> false
            payload != other.payload -> false
            !audioData.contentEquals(other.audioData) -> false
            requestId != other.requestId -> false
            frameState != other.frameState -> false
            else -> true
        }

    override fun hashCode(): Int {
        var result = token.hashCode()
        result = 31 * result + serviceName.hashCode()
        result = 31 * result + methodName.hashCode()
        result = 31 * result + payload.hashCode()
        result = 31 * result + audioData.contentHashCode()
        result = 31 * result + requestId.hashCode()
        result = 31 * result + frameState.hashCode()
        return result
    }

    override fun toString(): String =
        "DoubaoAsrRequest(token=${token.redactedSecret()}, serviceName=$serviceName, " +
            "methodName=$methodName, payloadBytes=${payload.length}, audioBytes=${audioData.size}, " +
            "requestId=$requestId, frameState=$frameState)"
}

internal data class DoubaoAsrResponse(
    val requestId: String = "",
    val taskId: String = "",
    val serviceName: String = "",
    val messageType: String = "",
    val statusCode: Int = 0,
    val statusMessage: String = "",
    val resultJson: String = "",
    val unknownField9: Int = 0,
)

internal object DoubaoProtoCodec {
    fun encodeRequest(request: DoubaoAsrRequest): ByteArray =
        ProtoWriter()
            .string(2, request.token)
            .string(3, request.serviceName)
            .string(5, request.methodName)
            .string(6, request.payload)
            .bytes(7, request.audioData)
            .string(8, request.requestId)
            .int32(9, request.frameState.wireValue)
            .toByteArray()

    fun decodeResponse(bytes: ByteArray): DoubaoAsrResponse {
        val reader = ProtoReader(bytes)
        var requestId = ""
        var taskId = ""
        var serviceName = ""
        var messageType = ""
        var statusCode = 0
        var statusMessage = ""
        var resultJson = ""
        var unknownField9 = 0

        while (!reader.isAtEnd()) {
            val key = reader.readVarint32()
            val fieldNumber = key ushr 3
            val wireType = key and 0x07
            when (fieldNumber) {
                1 -> requestId = reader.readString(wireType)
                2 -> taskId = reader.readString(wireType)
                3 -> serviceName = reader.readString(wireType)
                4 -> messageType = reader.readString(wireType)
                5 -> statusCode = reader.readInt32(wireType)
                6 -> statusMessage = reader.readString(wireType)
                7 -> resultJson = reader.readString(wireType)
                9 -> unknownField9 = reader.readInt32(wireType)
                else -> reader.skip(wireType)
            }
        }

        return DoubaoAsrResponse(
            requestId = requestId,
            taskId = taskId,
            serviceName = serviceName,
            messageType = messageType,
            statusCode = statusCode,
            statusMessage = statusMessage,
            resultJson = resultJson,
            unknownField9 = unknownField9,
        )
    }

    internal fun encodeResponseForTest(response: DoubaoAsrResponse): ByteArray =
        ProtoWriter()
            .string(1, response.requestId)
            .string(2, response.taskId)
            .string(3, response.serviceName)
            .string(4, response.messageType)
            .int32(5, response.statusCode)
            .string(6, response.statusMessage)
            .string(7, response.resultJson)
            .int32(9, response.unknownField9)
            .toByteArray()
}

private class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun string(
        fieldNumber: Int,
        value: String,
    ): ProtoWriter {
        if (value.isEmpty()) return this
        return bytes(fieldNumber, value.encodeToByteArray())
    }

    fun bytes(
        fieldNumber: Int,
        value: ByteArray,
    ): ProtoWriter {
        if (value.isEmpty()) return this
        writeKey(fieldNumber, WireType.LENGTH_DELIMITED)
        writeVarint(value.size)
        out.write(value)
        return this
    }

    fun int32(
        fieldNumber: Int,
        value: Int,
    ): ProtoWriter {
        if (value == 0) return this
        writeKey(fieldNumber, WireType.VARINT)
        writeVarint(value)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun writeKey(
        fieldNumber: Int,
        wireType: Int,
    ) {
        require(fieldNumber > 0) { "fieldNumber must be positive" }
        writeVarint((fieldNumber shl 3) or wireType)
    }

    private fun writeVarint(value: Int) {
        var current = value
        while ((current and 0x7f.inv()) != 0) {
            out.write((current and 0x7f) or 0x80)
            current = current ushr 7
        }
        out.write(current)
    }
}

private class ProtoReader(
    private val data: ByteArray,
) {
    private var offset = 0

    fun isAtEnd(): Boolean = offset >= data.size

    fun readString(wireType: Int): String {
        requireWireType(wireType, WireType.LENGTH_DELIMITED)
        return readBytes().decodeToString()
    }

    fun readInt32(wireType: Int): Int {
        requireWireType(wireType, WireType.VARINT)
        return readVarint32()
    }

    fun readVarint32(): Int {
        var shift = 0
        var result = 0
        while (shift < 32) {
            if (offset >= data.size) throw DoubaoProtocolException("Truncated protobuf varint")
            val byte = data[offset++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl shift)
            if ((byte and 0x80) == 0) return result
            shift += 7
        }
        throw DoubaoProtocolException("Malformed protobuf varint")
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WireType.VARINT -> readVarint32()
            WireType.LENGTH_DELIMITED -> readBytes()
            WireType.FIXED32 -> offset = checkedOffset(4)
            WireType.FIXED64 -> offset = checkedOffset(8)
            else -> throw DoubaoProtocolException("Unsupported protobuf wire type $wireType")
        }
    }

    private fun readBytes(): ByteArray {
        val size = readVarint32()
        if (size < 0) throw DoubaoProtocolException("Negative protobuf length")
        val end = checkedOffset(size)
        val bytes = data.copyOfRange(offset, end)
        offset = end
        return bytes
    }

    private fun checkedOffset(size: Int): Int {
        val end = offset + size
        if (end < offset || end > data.size) throw DoubaoProtocolException("Truncated protobuf field")
        return end
    }

    private fun requireWireType(
        actual: Int,
        expected: Int,
    ) {
        if (actual != expected) {
            throw DoubaoProtocolException("Unexpected protobuf wire type $actual, expected $expected")
        }
    }
}

private object WireType {
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH_DELIMITED = 2
    const val FIXED32 = 5
}
