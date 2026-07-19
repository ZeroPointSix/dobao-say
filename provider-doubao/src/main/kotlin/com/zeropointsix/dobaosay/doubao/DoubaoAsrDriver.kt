package com.zeropointsix.dobaosay.doubao

import com.zeropointsix.dobaosay.asr.AsrDriver
import com.zeropointsix.dobaosay.asr.AsrFailure
import com.zeropointsix.dobaosay.asr.AudioEncoding
import com.zeropointsix.dobaosay.asr.AudioFrame
import com.zeropointsix.dobaosay.asr.DriverSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toJavaDuration

private const val NORMAL_CLOSURE = 1000

/** ~400ms of silence at 20ms/frame before LAST — reduces clipped sentence endings. */
private const val SILENCE_PAD_FRAMES = 20

class DoubaoAsrDriver(
    private val runtimeConfig: DoubaoDriverRuntimeConfig,
) : AsrDriver {
    private val providerConfig = runtimeConfig.providerConfig
    private val requestId = UUID.randomUUID().toString()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mapper = DoubaoResponseMapper()
    private val closed = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val frameSizePerChannel =
        runtimeConfig.audioFormat.sampleRateHz * runtimeConfig.audioFormat.frameDurationMs / 1_000

    private lateinit var credentials: DoubaoCredentials
    private lateinit var encoder: DoubaoOpusEncoder
    private var socket: WebSocket? = null
    private var incoming: Channel<ByteArray>? = null
    private var receiverJob: Job? = null
    private var nextFrameState = DoubaoFrameState.FIRST
    private var timestampBaseMs: Long? = null
    private var lastSentTimestampMs: Long? = null

    override suspend fun connect(sink: suspend (DriverSignal) -> Unit) {
        try {
            validateFormat()
            val client = providerConfig.deviceClient()
            credentials = client.ensureCredentials(providerConfig)
            encoder = providerConfig.opusEncoderFactory.create(runtimeConfig.audioFormat)

            val listener = DoubaoWebSocketListener()
            incoming = listener.messages
            val connectTimeout = providerConfig.websocketReceiveTimeout
            socket =
                withContext(Dispatchers.IO) {
                    val client =
                        providerConfig.httpClient
                            .newBuilder()
                            .connectTimeout(connectTimeout.toJavaDuration())
                            .build()
                    val request =
                        Request
                            .Builder()
                            .url(providerConfig.websocketUrl(credentials))
                            .header("User-Agent", providerConfig.userAgent)
                            .header("proto-version", "v2")
                            .header("x-custom-keepalive", "true")
                            .build()
                    client.newWebSocket(request, listener)
                }
            withTimeout(connectTimeout) { listener.awaitOpen() }

            sendRequest(startTaskRequest())
            val taskResponse = receiveResponse()
            expectHandshake("TaskStarted", taskResponse)
            sendRequest(startSessionRequest())
            val sessionResponse = receiveResponse()
            expectHandshake("SessionStarted", sessionResponse)
            sink(DriverSignal.Ready)
            receiverJob = scope.launch { receiveLoop(sink) }
        } catch (cancelled: CancellationException) {
            // TimeoutCancellationException is a CancellationException; convert it so the session
            // still observes a typed failure instead of hanging until its own connect timeout.
            if (cancelled is kotlinx.coroutines.TimeoutCancellationException) {
                sink(DriverSignal.Failed(AsrFailure.Timeout(com.zeropointsix.dobaosay.asr.TimeoutPhase.CONNECT)))
                return
            }
            throw cancelled
        } catch (error: Exception) {
            sink(DriverSignal.Failed(error.toAsrFailure("connect")))
        }
    }

    override suspend fun sendAudio(frame: AudioFrame) {
        if (closed.get() || stopRequested.get()) return
        val opus = encoder.encodePcm16Le(frame.copyBytes(), frameSizePerChannel)
        val timestamp = timestampFor(frame)
        val request =
            DoubaoAsrRequest(
                serviceName = "ASR",
                methodName = "TaskRequest",
                payload = audioMetadata(timestamp),
                audioData = opus,
                requestId = requestId,
                frameState = nextFrameState,
            )
        nextFrameState = DoubaoFrameState.MIDDLE
        sendRequest(request)
    }

    override suspend fun requestStop() {
        if (!stopRequested.compareAndSet(false, true)) return
        if (!closed.get() && nextFrameState != DoubaoFrameState.FIRST) {
            // Reverse clients / IME UX: pad ~400ms silence before LAST so server VAD can flush
            // the trailing syllable (a single 20ms frame is often clipped).
            val silence = ByteArray(runtimeConfig.audioFormat.bytesPerFrame)
            for (index in 0 until SILENCE_PAD_FRAMES) {
                val opus = encoder.encodePcm16Le(silence, frameSizePerChannel)
                val frameState =
                    if (index == SILENCE_PAD_FRAMES - 1) {
                        DoubaoFrameState.LAST
                    } else {
                        DoubaoFrameState.MIDDLE
                    }
                val frameDuration = runtimeConfig.audioFormat.frameDurationMs.toLong()
                val timestamp =
                    (lastSentTimestampMs ?: System.currentTimeMillis()) + frameDuration
                lastSentTimestampMs = timestamp
                sendRequest(
                    DoubaoAsrRequest(
                        serviceName = "ASR",
                        methodName = "TaskRequest",
                        payload = audioMetadata(timestamp),
                        audioData = opus,
                        requestId = requestId,
                        frameState = frameState,
                    ),
                )
            }
            nextFrameState = DoubaoFrameState.LAST
        }
        sendRequest(finishSessionRequest())
    }

    override suspend fun abort() {
        if (closed.compareAndSet(false, true)) {
            socket?.cancel()
        }
    }

    override suspend fun release() {
        if (closed.compareAndSet(false, true)) {
            socket?.close(NORMAL_CLOSURE, "release")
        }
        receiverJob?.cancel()
        incoming?.close()
        scope.cancel()
    }

    private suspend fun receiveLoop(sink: suspend (DriverSignal) -> Unit) {
        while (scope.isActive && !closed.get()) {
            val bytes = incoming?.receiveCatching()?.getOrNull() ?: break
            val response =
                try {
                    DoubaoProtoCodec.decodeResponse(bytes)
                } catch (error: Exception) {
                    sink(DriverSignal.Failed(error.toAsrFailure("decode")))
                    continue
                }
            for (event in mapper.map(response)) {
                when (event) {
                    DoubaoResponseEvent.TaskStarted,
                    DoubaoResponseEvent.SessionStarted,
                    DoubaoResponseEvent.Heartbeat,
                    DoubaoResponseEvent.Unknown,
                    -> Unit

                    DoubaoResponseEvent.SessionFinished -> sink(DriverSignal.RemoteClosed)

                    is DoubaoResponseEvent.Signal -> sink(event.signal)

                    is DoubaoResponseEvent.Failure -> sink(DriverSignal.Failed(event.failure))
                }
            }
        }
    }

    private suspend fun receiveResponse(): DoubaoAsrResponse {
        val channel = incoming ?: throw DoubaoProtocolException("WebSocket receive channel not ready")
        val bytes =
            withTimeout(providerConfig.websocketReceiveTimeout) {
                channel.receive()
            }
        return DoubaoProtoCodec.decodeResponse(bytes)
    }

    private fun expectHandshake(
        expected: String,
        response: DoubaoAsrResponse,
    ) {
        val events = mapper.map(response)
        val failed = events.filterIsInstance<DoubaoResponseEvent.Failure>().firstOrNull()
        if (failed != null) throw DoubaoProtocolException("Doubao handshake failed: ${failed.failure.code}")
        val matched =
            when (expected) {
                "TaskStarted" -> events.any { it is DoubaoResponseEvent.TaskStarted }
                "SessionStarted" -> events.any { it is DoubaoResponseEvent.SessionStarted }
                else -> false
            }
        if (!matched) {
            throw DoubaoProtocolException("Expected $expected, got ${response.messageType.ifBlank { "<empty>" }}")
        }
    }

    private suspend fun sendRequest(request: DoubaoAsrRequest) {
        val webSocket = socket ?: throw DoubaoProtocolException("WebSocket is not connected")
        val bytes = DoubaoProtoCodec.encodeRequest(request)
        withContext(Dispatchers.IO) {
            check(webSocket.send(bytes.toByteString())) { "WebSocket send failed" }
        }
    }

    private fun startTaskRequest(): DoubaoAsrRequest =
        DoubaoAsrRequest(
            token = credentials.token.orEmpty(),
            serviceName = "ASR",
            methodName = "StartTask",
            requestId = requestId,
        )

    private fun startSessionRequest(): DoubaoAsrRequest =
        DoubaoAsrRequest(
            token = credentials.token.orEmpty(),
            serviceName = "ASR",
            methodName = "StartSession",
            payload = sessionPayload(),
            requestId = requestId,
        )

    private fun finishSessionRequest(): DoubaoAsrRequest =
        DoubaoAsrRequest(
            token = credentials.token.orEmpty(),
            serviceName = "ASR",
            methodName = "FinishSession",
            requestId = requestId,
        )

    private fun sessionPayload(): String {
        val options = providerConfig.sessionOptions
        return buildJsonObject {
            put(
                "audio_info",
                buildJsonObject {
                    put("channel", runtimeConfig.audioFormat.channels)
                    put("format", "speech_opus")
                    put("sample_rate", runtimeConfig.audioFormat.sampleRateHz)
                },
            )
            put("enable_punctuation", options.enablePunctuation)
            put("enable_speech_rejection", options.enableSpeechRejection)
            put(
                "extra",
                buildJsonObject {
                    put("app_name", options.sourceAppName)
                    put("cell_compress_rate", options.cellCompressRate)
                    put("did", credentials.deviceId)
                    put("enable_asr_threepass", options.enableAsrThreepass)
                    put("enable_asr_twopass", options.enableAsrTwopass)
                    put("input_mode", options.inputMode)
                },
            )
        }.toString()
    }

    private fun audioMetadata(timestampMs: Long): String =
        buildJsonObject {
            put("extra", buildJsonObject {})
            put("timestamp_ms", timestampMs)
        }.toString()

    private fun timestampFor(frame: AudioFrame): Long {
        val base =
            timestampBaseMs
                ?: (System.currentTimeMillis() - frame.timestampMs).also { timestampBaseMs = it }
        val timestamp = base + frame.timestampMs
        val monotonic =
            lastSentTimestampMs?.let { last -> maxOf(timestamp, last + 1) } ?: timestamp
        lastSentTimestampMs = monotonic
        return monotonic
    }

    private fun validateFormat() {
        val format = runtimeConfig.audioFormat
        require(format.encoding == AudioEncoding.PCM_16_LE) { "Doubao ASR requires PCM16 LE input" }
        require(format.sampleRateHz == 16_000) { "Doubao ASR MVP requires 16 kHz input" }
        require(format.channels == 1) { "Doubao ASR MVP requires mono input" }
        require(format.frameDurationMs == 20) { "Doubao ASR MVP requires 20 ms frames" }
    }

    private fun Throwable.toAsrFailure(phase: String): AsrFailure =
        when (this) {
            is DoubaoProtocolException -> AsrFailure.ProtocolViolation("doubao_$phase")

            is java.net.ConnectException,
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException,
            -> AsrFailure.NetworkUnavailable

            else -> AsrFailure.Internal("doubao_$phase")
        }
}

private class DoubaoWebSocketListener : WebSocketListener() {
    val messages = Channel<ByteArray>(Channel.UNLIMITED)
    private val opened = CompletableDeferred<Unit>()

    suspend fun awaitOpen() {
        opened.await()
    }

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        opened.complete(Unit)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        messages.trySend(bytes.toByteArray())
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        messages.trySend(text.encodeToByteArray())
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        messages.close()
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        opened.completeExceptionally(t)
        messages.close(t)
    }
}
