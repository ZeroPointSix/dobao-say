package com.zeropointsix.dobaosay.session

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.zeropointsix.dobaosay.asr.AsrCommandResult
import com.zeropointsix.dobaosay.asr.AsrEvent
import com.zeropointsix.dobaosay.asr.AsrFailure
import com.zeropointsix.dobaosay.asr.AsrSession
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.AsrSessionState
import com.zeropointsix.dobaosay.asr.AudioFrame
import com.zeropointsix.dobaosay.asr.CancelReason
import com.zeropointsix.dobaosay.asr.DefaultAsrSession
import com.zeropointsix.dobaosay.asr.SessionOutcome
import com.zeropointsix.dobaosay.asr.StopReason
import com.zeropointsix.dobaosay.doubao.DoubaoAsrProvider
import com.zeropointsix.dobaosay.doubao.DoubaoProviderConfig
import com.zeropointsix.dobaosay.infra.AppCredentialStore
import com.zeropointsix.dobaosay.infra.AudioFocusController
import com.zeropointsix.dobaosay.infra.MicPcmCapture
import com.zeropointsix.dobaosay.infra.PermissionGate
import com.zeropointsix.dobaosay.infra.VoiceCaptureNotifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Single-active-session foreground microphone service.
 *
 * UI and notifications both drive the same start/stop commands.
 */
class VoiceCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopRequested = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)

    private var sessionJob: Job? = null
    private var currentSession: AsrSession? = null
    private var micCapture: MicPcmCapture? = null
    private var audioFocus: AudioFocusController? = null
    private var activeSessionId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> startSession()
            ACTION_STOP -> {
                // Always mark stop so a late-starting session / connecting session aborts.
                stopRequested.set(true)
                if (sessionActive.get()) {
                    requestStop(manual = true)
                } else {
                    // Finger released before session became active — avoid orphan start.
                    stopSelf(startId)
                }
            }
            ACTION_CANCEL -> {
                stopRequested.set(true)
                if (sessionActive.get()) {
                    requestCancel()
                } else {
                    stopSelf(startId)
                }
            }
            else -> {
                if (!sessionActive.get()) {
                    stopSelf(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested.set(true)
        micCapture?.close()
        audioFocus?.abandon()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSession() {
        if (!PermissionGate.hasRecordAudio(this)) {
            publish(
                VoicePhase.Failed,
                "错误",
                "缺少录音权限，无法启动前台录音服务。",
            )
            stopSelfSafely()
            return
        }
        if (!sessionActive.compareAndSet(false, true)) {
            // Single-session rule: ignore duplicate starts.
            return
        }

        stopRequested.set(false)
        val sessionId = UUID.randomUUID().toString()
        activeSessionId = sessionId
        // Finger already up (ACTION_UP while START was still queued / connecting).
        if (!VoiceSessionBus.holdPressed.get()) {
            // startForegroundService requires startForeground before stopSelf.
            promoteToForeground("已取消", "取消过快，未开始录音。")
            publish(VoicePhase.Cancelled, "已取消", "取消过快，未开始录音。", sessionId = sessionId)
            stopForegroundCompat()
            sessionActive.set(false)
            activeSessionId = null
            stopSelfSafely()
            return
        }
        promoteToForeground("连接中", "正在连接豆包 ASR...")
        publish(VoicePhase.Connecting, "连接中", "正在连接豆包 ASR...", sessionId = sessionId)

        sessionJob =
            serviceScope.launch {
                try {
                    runAsrSession(sessionId)
                } finally {
                    teardownCapture()
                    sessionActive.set(false)
                    activeSessionId = null
                    sessionJob = null
                    stopForegroundCompat()
                    stopSelfSafely()
                }
            }
    }

    private fun requestStop(manual: Boolean) {
        stopRequested.set(true)
        micCapture?.stop()
        val session = currentSession ?: return
        serviceScope.launch {
            when (session.snapshot.value.state) {
                AsrSessionState.Created,
                AsrSessionState.Connecting,
                -> session.cancel(CancelReason.USER)

                AsrSessionState.Ready,
                AsrSessionState.Streaming,
                -> {
                    publish(VoicePhase.Stopping, "收尾中", "正在请求最终结果...")
                    updateNotification("收尾中", "正在请求最终结果...", showStop = false)
                    session.stop(if (manual) StopReason.MANUAL else StopReason.MANUAL)
                }

                is AsrSessionState.Stopping,
                is AsrSessionState.Closed,
                -> Unit
            }
        }
    }

    private fun requestCancel() {
        stopRequested.set(true)
        micCapture?.stop()
        currentSession?.let { session ->
            serviceScope.launch { session.cancel(CancelReason.USER) }
        }
    }

    private suspend fun runAsrSession(sessionId: String) {
        // Align with reverse PTT clients (Node realtime / gfreezy DoubaoASR):
        // VAD finals are segment boundaries to join, not end-of-hold.
        val sessionConfig =
            AsrSessionConfig(
                autoStopOnVad = false,
                commitFinalImmediately = false,
                stopFinalTimeout = 3.seconds,
            )
        val provider =
            try {
                prepareDoubaoProvider()
            } catch (_: Exception) {
                publish(VoicePhase.Failed, "错误", "豆包凭据准备失败。", sessionId = sessionId)
                return
            }
        val session = DefaultAsrSession(sessionConfig, provider.createDriver(sessionConfig), serviceScope)
        currentSession = session
        val ready = CompletableDeferred<Boolean>()

        val focus =
            AudioFocusController(this) {
                stopRequested.set(true)
                micCapture?.stop()
                serviceScope.launch {
                    currentSession?.stop(StopReason.MANUAL)
                }
            }
        audioFocus = focus
        if (!focus.request()) {
            publish(VoicePhase.Failed, "错误", "无法获取音频焦点。", sessionId = sessionId)
            session.cancel(CancelReason.USER)
            return
        }

        val eventJob =
            serviceScope.launch {
                session.events.collect { event ->
                    handleAsrEvent(event, sessionId)
                    when (event) {
                        is AsrEvent.Ready -> ready.completeIfActive(true)
                        is AsrEvent.Error,
                        is AsrEvent.Closed,
                        -> ready.completeIfActive(false)

                        else -> Unit
                    }
                }
            }

        // Start mic while connecting so speech during handshake is not lost (prebuffer).
        val prebuffer = ArrayDeque<ByteArray>(PREBUFFER_MAX_FRAMES)
        val prebufferLock = Any()
        micCapture?.close()
        val earlyCapture = MicPcmCapture()
        micCapture = earlyCapture
        val prebufferJob =
            serviceScope.launch(Dispatchers.IO) {
                try {
                    earlyCapture.start()
                    while (isActive && !stopRequested.get() && !ready.isCompleted) {
                        val bytes = earlyCapture.readFrame { !stopRequested.get() && !ready.isCompleted } ?: break
                        synchronized(prebufferLock) {
                            if (prebuffer.size >= PREBUFFER_MAX_FRAMES) {
                                prebuffer.removeFirst()
                            }
                            prebuffer.addLast(bytes)
                        }
                    }
                } catch (_: Exception) {
                    // Handshake failure / cancel — captureAndPush or teardown handles cleanup.
                }
            }

        try {
            when (val result = session.start()) {
                AsrCommandResult.Accepted,
                AsrCommandResult.IgnoredAlreadyHandled,
                -> Unit

                is AsrCommandResult.Rejected -> {
                    publishFailure(result.failure, sessionId)
                    return
                }
            }

            if (!VoiceSessionBus.holdPressed.get()) {
                stopRequested.set(true)
            }
            val connected = ready.await()
            prebufferJob.cancel()
            runCatching { prebufferJob.join() }
            if (connected && !stopRequested.get()) {
                captureAndPush(session, sessionId, earlyCapture, prebuffer, prebufferLock)
            }

            if (session.snapshot.value.state !is AsrSessionState.Closed) {
                if (stopRequested.get() || !VoiceSessionBus.holdPressed.get()) {
                    when (session.snapshot.value.state) {
                        AsrSessionState.Created,
                        AsrSessionState.Connecting,
                        -> session.cancel(CancelReason.USER)

                        AsrSessionState.Ready,
                        AsrSessionState.Streaming,
                        -> session.stop(StopReason.MANUAL)

                        is AsrSessionState.Stopping,
                        is AsrSessionState.Closed,
                        -> Unit
                    }
                } else {
                    session.close()
                }
                withTimeoutOrNull(sessionConfig.stopFinalTimeout + 2.seconds) {
                    session.snapshot.first { it.state is AsrSessionState.Closed }
                }
            }
        } finally {
            prebufferJob.cancel()
            eventJob.cancel()
            currentSession = null
        }
    }

    /**
     * Load persisted Doubao credentials, ensure device/token via provider-doubao,
     * then save the resolved credentials back to internal storage.
     *
     * Mirrors [com.zeropointsix.dobaosay.doubao] CLI persistence so the driver
     * reuses an existing device instead of registering on every session.
     */
    private suspend fun prepareDoubaoProvider(): DoubaoAsrProvider {
        val store = AppCredentialStore(this)
        val loaded = withContext(Dispatchers.IO) { store.load() }
        val seedConfig = DoubaoProviderConfig(credentials = loaded)
        val ensured = seedConfig.deviceClient().ensureCredentials(seedConfig)
        withContext(Dispatchers.IO) { store.save(ensured) }
        return DoubaoAsrProvider(
            seedConfig.copy(credentials = ensured, token = ensured.token),
        )
    }

    private suspend fun captureAndPush(
        session: AsrSession,
        sessionId: String,
        capture: MicPcmCapture,
        prebuffer: ArrayDeque<ByteArray>,
        prebufferLock: Any,
    ) {
        withContext(Dispatchers.IO) {
            var sequence = 0L
            try {
                publish(VoicePhase.Recording, "录音中", "再单击语音球或通知中的停止结束。", sessionId = sessionId)
                updateNotification("录音中", "正在使用麦克风", showStop = true)

                val pending =
                    synchronized(prebufferLock) {
                        ArrayList(prebuffer).also { prebuffer.clear() }
                    }
                for (bytes in pending) {
                    if (stopRequested.get()) break
                    val frame =
                        AudioFrame(
                            sequence = sequence,
                            timestampMs = sequence * capture.audioFormat.frameDurationMs.toLong(),
                            bytes = bytes,
                            format = capture.audioFormat,
                        )
                    when (val result = session.pushAudio(frame)) {
                        AsrCommandResult.Accepted,
                        AsrCommandResult.IgnoredAlreadyHandled,
                        -> sequence += 1

                        is AsrCommandResult.Rejected -> {
                            if (!stopRequested.get()) publishFailure(result.failure, sessionId)
                            return@withContext
                        }
                    }
                }

                while (isActive && !stopRequested.get()) {
                    val bytes = capture.readFrame { !stopRequested.get() } ?: break
                    val frame =
                        AudioFrame(
                            sequence = sequence,
                            timestampMs = sequence * capture.audioFormat.frameDurationMs.toLong(),
                            bytes = bytes,
                            format = capture.audioFormat,
                        )
                    when (val result = session.pushAudio(frame)) {
                        AsrCommandResult.Accepted,
                        AsrCommandResult.IgnoredAlreadyHandled,
                        -> sequence += 1

                        is AsrCommandResult.Rejected -> {
                            if (!stopRequested.get()) publishFailure(result.failure, sessionId)
                            break
                        }
                    }
                }
            } finally {
                capture.close()
                if (micCapture === capture) micCapture = null
            }
        }
    }

    private fun handleAsrEvent(
        event: AsrEvent,
        sessionId: String,
    ) {
        when (event) {
            is AsrEvent.Connecting -> {
                publish(VoicePhase.Connecting, "连接中", "正在连接豆包 ASR...", sessionId = sessionId)
                updateNotification("连接中", "正在连接豆包 ASR...", showStop = true)
            }

            is AsrEvent.Ready -> {
                publish(VoicePhase.Recording, "录音中", "已连接，请继续说话。", sessionId = sessionId)
                updateNotification("录音中", "正在使用麦克风", showStop = true)
            }

            is AsrEvent.SpeechStarted -> {
                publish(VoicePhase.Recording, "录音中", "检测到语音。", sessionId = sessionId)
            }

            is AsrEvent.Partial -> {
                val text = event.text.ifBlank { "正在接收部分结果..." }
                publish(VoicePhase.Recognizing, "识别中", text, sessionId = sessionId)
            }

            is AsrEvent.Final -> {
                // Mid-hold VAD segments arrive as Final; keep showing recognizing until Closed.
                val text = event.text.ifBlank { "正在接收结果..." }
                publish(VoicePhase.Recognizing, "识别中", text, sessionId = sessionId)
            }

            is AsrEvent.SpeechEnded -> {
                publish(VoicePhase.Recognizing, "识别中", "检测到句段结束，继续录音中…", sessionId = sessionId)
            }

            is AsrEvent.Retrying -> {
                publish(
                    VoicePhase.Connecting,
                    "重试中",
                    "网络或服务暂不可用，正在重试第 ${event.attempt} 次。",
                    sessionId = sessionId,
                )
            }

            is AsrEvent.Error -> publishFailure(event.failure, sessionId)
            is AsrEvent.Closed -> handleClosed(event.outcome, sessionId)
        }
    }

    private fun handleClosed(
        outcome: SessionOutcome,
        sessionId: String,
    ) {
        when (outcome) {
            is SessionOutcome.Succeeded -> publishFinalText(outcome.text, sessionId)

            is SessionOutcome.Failed -> publishFailure(outcome.failure, sessionId)
            is SessionOutcome.Cancelled -> {
                // No finalText — Activity must not auto-copy or toast success.
                publish(VoicePhase.Cancelled, "已取消", "本次录音已取消，未写入剪贴板。", sessionId = sessionId)
            }

            SessionOutcome.ClosedWithoutResult -> {
                // No finalText — not a copyable success.
                publish(VoicePhase.Idle, "空结果", "本次录音未产生可复制文本。", sessionId = sessionId)
            }
        }
    }

    private fun publishFailure(
        failure: AsrFailure,
        sessionId: String?,
    ) {
        // Failed must never carry finalText so Activity won't auto-copy.
        publish(VoicePhase.Failed, "错误", "ASR 失败：${failure.code}", sessionId = sessionId)
        updateNotification("错误", "ASR 失败：${failure.code}", showStop = false)
    }

    /**
     * Non-blank finals are Succeeded + finalText (auto-copyable).
     * Blank finals are Idle/"空结果" with no finalText — never a pseudo-success.
     */
    private fun publishFinalText(
        text: String,
        sessionId: String,
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            publish(
                VoicePhase.Idle,
                "空结果",
                "识别结束但没有得到文本，未写入剪贴板。",
                finalText = null,
                sessionId = sessionId,
            )
            updateNotification("空结果", "没有可复制的文本", showStop = false)
            return
        }
        publish(
            VoicePhase.Succeeded,
            "识别成功",
            trimmed,
            finalText = trimmed,
            sessionId = sessionId,
        )
        updateNotification("识别成功", "最终文本已就绪", showStop = false)
    }

    private fun publish(
        phase: VoicePhase,
        title: String,
        detail: String,
        finalText: String? = null,
        sessionId: String? = activeSessionId,
    ) {
        VoiceSessionBus.publish(
            VoiceUiState(
                phase = phase,
                title = title,
                detail = detail,
                finalText = finalText,
                sessionId = sessionId,
            ),
        )
    }

    private fun promoteToForeground(
        title: String,
        text: String,
    ) {
        val notification = VoiceCaptureNotifications.build(this, title, text, showStopAction = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                VoiceCaptureNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(VoiceCaptureNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(
        title: String,
        text: String,
        showStop: Boolean,
    ) {
        val notification = VoiceCaptureNotifications.build(this, title, text, showStopAction = showStop)
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(VoiceCaptureNotifications.NOTIFICATION_ID, notification)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun teardownCapture() {
        micCapture?.close()
        micCapture = null
        audioFocus?.abandon()
        audioFocus = null
    }

    private fun stopSelfSafely() {
        stopSelf()
    }

    private fun CompletableDeferred<Boolean>.completeIfActive(value: Boolean) {
        if (!isCompleted) complete(value)
    }

    companion object {
        const val ACTION_START = "com.zeropointsix.dobaosay.action.VOICE_START"
        const val ACTION_STOP = "com.zeropointsix.dobaosay.action.VOICE_STOP"
        const val ACTION_CANCEL = "com.zeropointsix.dobaosay.action.VOICE_CANCEL"

        /** ~3s ring buffer while WS handshake completes (20ms frames). */
        private const val PREBUFFER_MAX_FRAMES = 150

        fun start(context: Context) {
            val intent = Intent(context, VoiceCaptureService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceCaptureService::class.java).setAction(ACTION_STOP))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, VoiceCaptureService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
