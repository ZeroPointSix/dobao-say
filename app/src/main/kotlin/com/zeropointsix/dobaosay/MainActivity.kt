package com.zeropointsix.dobaosay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.zeropointsix.dobaosay.asr.AsrCommandResult
import com.zeropointsix.dobaosay.asr.AsrEvent
import com.zeropointsix.dobaosay.asr.AsrFailure
import com.zeropointsix.dobaosay.asr.AsrSession
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.AsrSessionState
import com.zeropointsix.dobaosay.asr.AudioEncoding
import com.zeropointsix.dobaosay.asr.AudioFormat
import com.zeropointsix.dobaosay.asr.AudioFrame
import com.zeropointsix.dobaosay.asr.CancelReason
import com.zeropointsix.dobaosay.asr.DefaultAsrSession
import com.zeropointsix.dobaosay.asr.SessionOutcome
import com.zeropointsix.dobaosay.asr.StopReason
import com.zeropointsix.dobaosay.doubao.DoubaoAsrProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

class MainActivity : Activity() {
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stopRequested = AtomicBoolean(false)

    private lateinit var stateText: TextView
    private lateinit var resultText: TextView
    private lateinit var recordButton: Button

    @Volatile
    private var currentSession: AsrSession? = null

    @Volatile
    private var currentRecorder: AudioRecord? = null

    private var sessionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        stateText = findViewById(R.id.stateText)
        resultText = findViewById(R.id.resultText)
        recordButton = findViewById(R.id.recordButton)

        configureRecordButton()
        if (hasRecordAudioPermission()) {
            showState("空闲", "按住下方按钮开始录音，松开结束。")
        } else {
            showState("需要录音权限", "dobao-say 只会在你按住按钮时使用麦克风。")
        }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        currentRecorder?.safeStop()
        activityScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RECORD_AUDIO_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            showState("空闲", "录音权限已开启，按住按钮开始。")
        } else {
            showState("错误", "未授予录音权限，无法启动语音识别。")
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureRecordButton() {
        recordButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startHoldRecording()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    finishHoldRecording()
                    true
                }

                else -> false
            }
        }
    }

    private fun startHoldRecording() {
        if (!hasRecordAudioPermission()) {
            requestRecordAudioPermission()
            return
        }
        if (sessionJob?.isActive == true) return

        stopRequested.set(false)
        sessionJob =
            activityScope.launch {
                runAsrSession()
            }
    }

    private fun finishHoldRecording() {
        stopRequested.set(true)
        currentRecorder?.safeStop()
        currentSession?.let { session ->
            activityScope.launch {
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
            }
        }
    }

    private suspend fun runAsrSession() {
        val sessionConfig = AsrSessionConfig(audioFormat = AudioFormat())
        val provider = DoubaoAsrProvider()
        val session = DefaultAsrSession(sessionConfig, provider.createDriver(sessionConfig), activityScope)
        val ready = CompletableDeferred<Boolean>()
        currentSession = session

        val eventJob =
            activityScope.launch {
                session.events.collect { event ->
                    handleAsrEvent(event)
                    when (event) {
                        is AsrEvent.Ready -> ready.completeIfActive(true)
                        is AsrEvent.Error,
                        is AsrEvent.Closed,
                        -> ready.completeIfActive(false)

                        else -> Unit
                    }
                }
            }

        try {
            showState("连接中", "正在连接豆包 ASR...")
            when (val result = session.start()) {
                AsrCommandResult.Accepted,
                AsrCommandResult.IgnoredAlreadyHandled,
                -> Unit

                is AsrCommandResult.Rejected -> {
                    showError(result.failure)
                    return
                }
            }

            if (ready.await() && !stopRequested.get()) {
                recordAndPushAudio(session, sessionConfig.audioFormat)
            }

            if (session.snapshot.value.state !is AsrSessionState.Closed) {
                if (stopRequested.get()) {
                    session.stop(StopReason.MANUAL)
                } else {
                    session.close()
                }
                withTimeoutOrNull(sessionConfig.stopFinalTimeout + 2.seconds) {
                    session.snapshot.first { it.state is AsrSessionState.Closed }
                }
            }
        } finally {
            eventJob.cancel()
            currentRecorder?.release()
            currentRecorder = null
            currentSession = null
            sessionJob = null
            stopRequested.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordAndPushAudio(
        session: AsrSession,
        format: AudioFormat,
    ) {
        val recorder = buildAudioRecord(format)
        currentRecorder = recorder
        withContext(Dispatchers.IO) {
            var sequence = 0L
            val startedAtMs = SystemClock.elapsedRealtime()
            try {
                recorder.startRecording()
                showState("录音中", "松开按钮结束并等待最终文本。")
                while (isActive && !stopRequested.get()) {
                    val bytes = recorder.readFrame(format.bytesPerFrame) ?: break
                    val frame =
                        AudioFrame(
                            sequence = sequence,
                            timestampMs = SystemClock.elapsedRealtime() - startedAtMs,
                            bytes = bytes,
                            format = format,
                        )
                    when (val result = session.pushAudio(frame)) {
                        AsrCommandResult.Accepted,
                        AsrCommandResult.IgnoredAlreadyHandled,
                        -> sequence += 1

                        is AsrCommandResult.Rejected -> {
                            if (!stopRequested.get()) showError(result.failure)
                            break
                        }
                    }
                }
            } finally {
                recorder.safeStop()
                recorder.release()
                if (currentRecorder === recorder) currentRecorder = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(format: AudioFormat): AudioRecord {
        require(format.encoding == AudioEncoding.PCM_16_LE)
        require(format.channels == 1)
        val channelMask = android.media.AudioFormat.CHANNEL_IN_MONO
        val encoding = android.media.AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(format.sampleRateHz, channelMask, encoding)
        val bufferSize = max(minBufferSize, format.bytesPerFrame * 4)
        val recorder =
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    android.media.AudioFormat
                        .Builder()
                        .setEncoding(encoding)
                        .setSampleRate(format.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
        return recorder
    }

    private fun AudioRecord.readFrame(size: Int): ByteArray? {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size && !stopRequested.get()) {
            val read = read(bytes, offset, size - offset, AudioRecord.READ_BLOCKING)
            if (read < 0) throw IllegalStateException("AudioRecord read failed: $read")
            if (read == 0) continue
            offset += read
        }
        return if (offset == size) bytes else null
    }

    private fun handleAsrEvent(event: AsrEvent) {
        when (event) {
            is AsrEvent.Connecting -> showState("连接中", "正在连接豆包 ASR...")
            is AsrEvent.Ready -> showState("录音中", "已连接，请继续按住说话。")
            is AsrEvent.SpeechStarted -> showState("录音中", "检测到语音。")
            is AsrEvent.Partial -> showState("识别中", event.text.ifBlank { "正在接收部分结果..." })
            is AsrEvent.Final -> showFinalText(event.text)
            is AsrEvent.SpeechEnded -> showState("收尾中", "语音结束，正在请求最终结果。")
            is AsrEvent.Retrying -> showState("重试中", "网络或服务暂不可用，正在重试第 ${event.attempt} 次。")
            is AsrEvent.Error -> showError(event.failure)
            is AsrEvent.Closed -> handleClosed(event.outcome)
        }
    }

    private fun handleClosed(outcome: SessionOutcome) {
        when (outcome) {
            is SessionOutcome.Succeeded -> showFinalText(outcome.text)
            is SessionOutcome.Failed -> showError(outcome.failure)
            is SessionOutcome.Cancelled -> showState("空闲", "本次录音已取消。")
            SessionOutcome.ClosedWithoutResult -> showState("空闲", "本次录音未产生结果。")
        }
    }

    private fun showFinalText(text: String) {
        val finalText = text.ifBlank { "(空结果)" }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("dobao-say", finalText))
        showState("最终文本已复制", finalText)
        runOnUiThread {
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(failure: AsrFailure) {
        showState("错误", "ASR 失败：${failure.code}")
    }

    private fun showState(
        state: String,
        detail: String,
    ) {
        runOnUiThread {
            stateText.text = state
            resultText.text = detail
        }
    }

    private fun hasRecordAudioPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestRecordAudioPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog
                .Builder(this)
                .setTitle("需要录音权限")
                .setMessage("dobao-say 只在你按住按钮时录音，并将音频发送给豆包 ASR 以生成文本。")
                .setPositiveButton("授予权限") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
        }
    }

    private fun CompletableDeferred<Boolean>.completeIfActive(value: Boolean) {
        if (!isCompleted) complete(value)
    }

    private fun AudioRecord.safeStop() {
        runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
        }
    }

    private companion object {
        const val RECORD_AUDIO_REQUEST = 102
    }
}
