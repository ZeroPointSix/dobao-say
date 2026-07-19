package com.zeropointsix.dobaosay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.zeropointsix.dobaosay.infra.AppCredentialStore
import com.zeropointsix.dobaosay.infra.PermissionGate
import com.zeropointsix.dobaosay.session.VoiceCaptureService
import com.zeropointsix.dobaosay.session.VoicePhase
import com.zeropointsix.dobaosay.session.VoiceSessionBus
import com.zeropointsix.dobaosay.session.VoiceUiState

/**
 * In-app orb UI aligned to Notion「前端样式草稿」view-02:
 * click toggle, phase colors/pulse/spinner — not a system overlay (ZER-110).
 */
class MainActivity : Activity() {
    private lateinit var orb: View
    private lateinit var orbPulse: View
    private lateinit var orbSpinner: ProgressBar
    private lateinit var phaseChip: TextView
    private lateinit var hint: TextView
    private lateinit var transcript: TextView
    private lateinit var status: TextView
    private lateinit var copyButton: Button
    private lateinit var settingsPanel: View
    private lateinit var historyPanel: View
    private lateinit var historyList: TextView

    private var unsubscribe: (() -> Unit)? = null
    private var lastCopiedSessionId: String? = null
    private var lastCopyableText: String? = null
    private val sessionHistory = ArrayList<String>()
    private var pulseRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        orb = findViewById(R.id.orb)
        orbPulse = findViewById(R.id.orbPulse)
        orbSpinner = findViewById(R.id.orbSpinner)
        phaseChip = findViewById(R.id.phaseChip)
        hint = findViewById(R.id.hint)
        transcript = findViewById(R.id.transcript)
        status = findViewById(R.id.status)
        copyButton = findViewById(R.id.btnCopy)
        settingsPanel = findViewById(R.id.settingsPanel)
        historyPanel = findViewById(R.id.historyPanel)
        historyList = findViewById(R.id.historyList)

        configureOrb()
        configureCopyButton()
        configureChrome()
        unsubscribe = VoiceSessionBus.subscribe(::renderState)

        if (PermissionGate.missingPermissions(this).isEmpty()) {
            VoiceSessionBus.resetIdle()
        } else {
            renderState(
                VoiceUiState(
                    phase = VoicePhase.Idle,
                    title = "需要系统权限",
                    detail = "dobao-say 只会在你主动录音时使用麦克风，并通过通知标明录音状态。",
                ),
            )
        }
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        unsubscribe = null
        VoiceSessionBus.holdPressed.set(false)
        stopPulse()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        PermissionGate.handlePermissionResult(
            activity = this,
            requestCode = requestCode,
            permissions = permissions,
            grantResults = grantResults,
            onGranted = {
                VoiceSessionBus.resetIdle("权限已开启，单击语音球开始。")
            },
            onDenied = { permanentlyDenied ->
                if (permanentlyDenied) {
                    AlertDialog
                        .Builder(this)
                        .setTitle("权限被拒绝")
                        .setMessage("请在系统设置中开启麦克风/通知权限后重试。")
                        .setPositiveButton("去设置") { _, _ -> PermissionGate.openAppSettings(this) }
                        .setNegativeButton("取消", null)
                        .show()
                }
                renderState(
                    VoiceUiState(
                        phase = VoicePhase.Failed,
                        title = "错误",
                        detail = "未授予必要权限，无法启动语音识别。",
                    ),
                )
            },
        )
    }

    private fun configureOrb() {
        orb.setOnClickListener { onOrbClick() }
    }

    private fun configureCopyButton() {
        copyButton.setOnClickListener {
            val text = copyableText() ?: return@setOnClickListener
            writeClipboard(text, toast = true)
        }
        updateCopyButtonEnabled()
    }

    private fun configureChrome() {
        findViewById<View>(R.id.btnHistory).setOnClickListener {
            val show = historyPanel.visibility != View.VISIBLE
            historyPanel.visibility = if (show) View.VISIBLE else View.GONE
            settingsPanel.visibility = View.GONE
            refreshHistoryList()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            val show = settingsPanel.visibility != View.VISIBLE
            settingsPanel.visibility = if (show) View.VISIBLE else View.GONE
            historyPanel.visibility = View.GONE
        }
        findViewById<Button>(R.id.btnClearHistory).setOnClickListener {
            sessionHistory.clear()
            refreshHistoryList()
            Toast.makeText(this, "已清空本会话历史", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnClearCredentials).setOnClickListener {
            AlertDialog
                .Builder(this)
                .setTitle("清除凭证")
                .setMessage("将删除本机 Doubao ASR 设备凭证，下次录音会重新注册。")
                .setPositiveButton("清除") { _, _ ->
                    AppCredentialStore(this).clear()
                    Toast.makeText(this, "凭证已清除", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消", null)
                .show()
        }
    }

    private fun onOrbClick() {
        when (VoiceSessionBus.latest.phase) {
            VoicePhase.Connecting,
            VoicePhase.Recording,
            VoicePhase.Recognizing,
            -> {
                VoiceSessionBus.holdPressed.set(false)
                VoiceCaptureService.stop(this)
            }

            VoicePhase.Stopping -> {
                // Ignore rapid taps while finalizing.
            }

            VoicePhase.Idle,
            VoicePhase.Succeeded,
            VoicePhase.Failed,
            VoicePhase.Cancelled,
            -> {
                PermissionGate.ensureVoicePermissions(this) {
                    VoiceSessionBus.holdPressed.set(true)
                    VoiceCaptureService.start(this)
                }
            }
        }
    }

    private fun renderState(state: VoiceUiState) {
        runOnUiThread {
            status.text = "${state.title} · ${state.detail}"
            applyOrbVisuals(state.phase)
            phaseChip.text = phaseLabel(state.phase)
            hint.text = hintFor(state.phase)

            when (state.phase) {
                VoicePhase.Recognizing -> {
                    if (state.detail.isNotBlank()) {
                        transcript.text = state.detail
                    }
                }

                VoicePhase.Succeeded -> {
                    val text = state.finalText?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        transcript.text = text
                        rememberHistory(text)
                    }
                }

                VoicePhase.Failed,
                VoicePhase.Cancelled,
                -> {
                    // Keep last transcript; status carries the error.
                }

                else -> Unit
            }

            rememberCopyable(state)
            maybeAutoCopyFinal(state)
            updateCopyButtonEnabled()
        }
    }

    private fun applyOrbVisuals(phase: VoicePhase) {
        when (phase) {
            VoicePhase.Idle,
            VoicePhase.Cancelled,
            -> {
                orb.setBackgroundResource(R.drawable.orb_idle)
                orbSpinner.visibility = View.GONE
                stopPulse()
            }

            VoicePhase.Connecting,
            VoicePhase.Recording,
            -> {
                orb.setBackgroundResource(R.drawable.orb_recording)
                orbSpinner.visibility = View.GONE
                startPulse()
            }

            VoicePhase.Recognizing,
            VoicePhase.Stopping,
            -> {
                orb.setBackgroundResource(R.drawable.orb_finalizing)
                orbSpinner.visibility = View.VISIBLE
                stopPulse()
            }

            VoicePhase.Succeeded -> {
                orb.setBackgroundResource(R.drawable.orb_success)
                orbSpinner.visibility = View.GONE
                stopPulse()
            }

            VoicePhase.Failed -> {
                orb.setBackgroundResource(R.drawable.orb_error)
                orbSpinner.visibility = View.GONE
                stopPulse()
            }
        }
    }

    private fun phaseLabel(phase: VoicePhase): String =
        when (phase) {
            VoicePhase.Idle -> "Idle"
            VoicePhase.Connecting -> "Connecting"
            VoicePhase.Recording -> "Recording"
            VoicePhase.Recognizing -> "Recognizing"
            VoicePhase.Stopping -> "Finalizing"
            VoicePhase.Succeeded -> "Success"
            VoicePhase.Failed -> "Error"
            VoicePhase.Cancelled -> "Cancelled"
        }

    private fun hintFor(phase: VoicePhase): String =
        when (phase) {
            VoicePhase.Idle,
            VoicePhase.Cancelled,
            VoicePhase.Succeeded,
            -> "单击语音球开始 · 再单击结束并识别"

            VoicePhase.Connecting -> "连接中 · 再单击可取消"

            VoicePhase.Recording -> "录音中 · 单击结束"

            VoicePhase.Recognizing,
            VoicePhase.Stopping,
            -> "识别优化中…"

            VoicePhase.Failed -> "失败 · 单击重试 · 可手动复制上次结果"
        }

    private fun startPulse() {
        if (pulseRunning) return
        pulseRunning = true
        orbPulse.visibility = View.VISIBLE
        orbPulse.alpha = 1f
        val scale =
            ScaleAnimation(
                1f,
                1.55f,
                1f,
                1.55f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
            ).apply {
                duration = 900
                repeatCount = Animation.INFINITE
                repeatMode = Animation.RESTART
            }
        val alpha =
            AlphaAnimation(0.55f, 0f).apply {
                duration = 900
                repeatCount = Animation.INFINITE
                repeatMode = Animation.RESTART
            }
        val set =
            AnimationSet(true).apply {
                interpolator = AccelerateDecelerateInterpolator()
                addAnimation(scale)
                addAnimation(alpha)
            }
        orbPulse.startAnimation(set)
    }

    private fun stopPulse() {
        pulseRunning = false
        orbPulse.clearAnimation()
        orbPulse.visibility = View.INVISIBLE
        orbPulse.alpha = 0f
    }

    private fun rememberHistory(text: String) {
        if (sessionHistory.firstOrNull() == text) return
        sessionHistory.add(0, text)
        if (sessionHistory.size > 20) {
            sessionHistory.removeAt(sessionHistory.lastIndex)
        }
        if (historyPanel.visibility == View.VISIBLE) {
            refreshHistoryList()
        }
    }

    private fun refreshHistoryList() {
        historyList.text =
            if (sessionHistory.isEmpty()) {
                "暂无历史。成功识别后会暂存在本会话。"
            } else {
                sessionHistory.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n\n")
            }
    }

    private fun rememberCopyable(state: VoiceUiState) {
        val text = state.finalText?.trim().orEmpty()
        if (state.phase == VoicePhase.Succeeded && text.isNotEmpty()) {
            lastCopyableText = text
        }
    }

    private fun maybeAutoCopyFinal(state: VoiceUiState) {
        if (state.phase != VoicePhase.Succeeded) return
        val text = state.finalText?.trim().orEmpty()
        if (text.isEmpty()) return
        val sessionId = state.sessionId
        if (sessionId != null && sessionId == lastCopiedSessionId) return
        lastCopiedSessionId = sessionId
        writeClipboard(text, toast = true)
    }

    private fun copyableText(): String? {
        val current = VoiceSessionBus.latest
        val fromCurrent =
            current.finalText
                ?.trim()
                .orEmpty()
                .takeIf { it.isNotEmpty() && current.phase == VoicePhase.Succeeded }
        return fromCurrent ?: lastCopyableText?.takeIf { it.isNotBlank() }
    }

    private fun updateCopyButtonEnabled() {
        copyButton.isEnabled = copyableText() != null
    }

    private fun writeClipboard(
        text: String,
        toast: Boolean,
    ) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("dobao-say", text))
        if (toast) {
            Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }
}
