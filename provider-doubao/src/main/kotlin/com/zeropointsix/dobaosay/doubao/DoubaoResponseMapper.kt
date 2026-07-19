package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AsrFailure
import com.zeropointsix.dobaosay.asr.DriverSignal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal sealed interface DoubaoResponseEvent {
    data object TaskStarted : DoubaoResponseEvent

    data object SessionStarted : DoubaoResponseEvent

    data object SessionFinished : DoubaoResponseEvent

    data object Heartbeat : DoubaoResponseEvent

    data object Unknown : DoubaoResponseEvent

    data class Signal(
        val signal: DriverSignal,
    ) : DoubaoResponseEvent

    data class Failure(
        val failure: AsrFailure,
    ) : DoubaoResponseEvent
}

internal class DoubaoResponseMapper {
    private val json = Json { ignoreUnknownKeys = true }
    private var revision = 0L
    private var speechStarted = false
    private var speechEnded = false

    fun map(response: DoubaoAsrResponse): List<DoubaoResponseEvent> {
        // Doubao messageType is authoritative. statusCode is a service-specific code, not HTTP.
        // Match the Node reference client: only TaskFailed/SessionFailed are hard failures here.
        if (response.messageType == "TaskFailed" || response.messageType == "SessionFailed") {
            return listOf(DoubaoResponseEvent.Failure(remoteFailure(response)))
        }

        return when (response.messageType) {
            "TaskStarted" -> listOf(DoubaoResponseEvent.TaskStarted)
            "SessionStarted" -> listOf(DoubaoResponseEvent.SessionStarted)
            "SessionFinished" -> listOf(DoubaoResponseEvent.SessionFinished)
            else -> mapResultJson(response.resultJson)
        }
    }

    private fun mapResultJson(resultJson: String): List<DoubaoResponseEvent> {
        if (resultJson.isBlank()) return listOf(DoubaoResponseEvent.Unknown)
        val root =
            try {
                json.parseToJsonElement(resultJson).jsonObject
            } catch (_: IllegalArgumentException) {
                return listOf(DoubaoResponseEvent.Unknown)
            }

        val events = mutableListOf<DoubaoResponseEvent>()
        val extra = root["extra"]?.jsonObjectOrNull()
        if (extra?.boolean("vad_start") == true && !speechStarted) {
            speechStarted = true
            events += DoubaoResponseEvent.Signal(DriverSignal.SpeechStarted)
        }

        val results = root["results"] as? JsonArray ?: return events.ifEmpty { listOf(DoubaoResponseEvent.Heartbeat) }
        val parsed = parseResults(results)
        if (parsed.text.isBlank()) return events.ifEmpty { listOf(DoubaoResponseEvent.Unknown) }

        if (parsed.isFinal) {
            if (parsed.vadFinished && !speechEnded) {
                speechEnded = true
                events += DoubaoResponseEvent.Signal(DriverSignal.SpeechEnded)
            }
            events +=
                DoubaoResponseEvent.Signal(
                    DriverSignal.Final(
                        resultId = parsed.resultId,
                        utteranceId = parsed.utteranceId,
                        text = parsed.text,
                    ),
                )
        } else {
            revision += 1
            events +=
                DoubaoResponseEvent.Signal(
                    DriverSignal.Partial(
                        utteranceId = parsed.utteranceId,
                        text = parsed.text,
                        revision = revision,
                    ),
                )
        }

        return events
    }

    private fun parseResults(results: JsonArray): ParsedResults {
        var text = ""
        var isInterim = true
        var vadFinished = false
        var nonstreamResult = false
        var index = 0L

        results.forEach { element ->
            val result = element.jsonObjectOrNull() ?: return@forEach
            result["text"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) text = it }
            result["index"]?.jsonPrimitive?.longOrNull?.let { index = it }
            if (result.boolean("is_interim") == false) isInterim = false
            if (result.boolean("is_vad_finished") == true) vadFinished = true
            val extra = result["extra"]?.jsonObjectOrNull()
            if (extra?.boolean("nonstream_result") == true) nonstreamResult = true
        }

        val isFinal = nonstreamResult || (!isInterim && vadFinished)
        val utteranceId = "doubao-$index"
        return ParsedResults(
            text = text,
            isFinal = isFinal,
            vadFinished = vadFinished,
            utteranceId = utteranceId,
            resultId = if (isFinal) "$utteranceId-final" else utteranceId,
        )
    }

    private fun remoteFailure(response: DoubaoAsrResponse): AsrFailure =
        when {
            response.statusCode == 401 || response.statusCode == 403 -> {
                AsrFailure.PermissionDenied
            }

            response.statusCode == 429 -> {
                AsrFailure.RateLimited(kotlin.time.Duration.ZERO)
            }

            response.statusMessage.contains("risk", ignoreCase = true) -> {
                AsrFailure.RiskControlled
            }

            else -> {
                AsrFailure.ProtocolViolation(
                    "doubao_${response.messageType}_${response.statusCode}_${response.statusMessage.take(48)}",
                )
            }
        }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private data class ParsedResults(
        val text: String,
        val isFinal: Boolean,
        val vadFinished: Boolean,
        val utteranceId: String,
        val resultId: String,
    )
}
