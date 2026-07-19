package com.zeropointsix.dobaosay

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.zeropointsix.dobaosay.infra.PermissionGate
import com.zeropointsix.dobaosay.session.VoiceCaptureService
import com.zeropointsix.dobaosay.session.VoicePhase
import com.zeropointsix.dobaosay.session.VoiceSessionBus
import com.zeropointsix.dobaosay.session.VoiceUiState

class MainActivity : Activity() {
    private lateinit var stateText: TextView
    private lateinit var resultText: TextView
    private lateinit var recordButton: Button
    private lateinit var copyButton: Button

    private var unsubscribe: (() -> Unit)? = null
    private var lastCopiedSessionId: String? = null
    /** Last non-blank successful text the user can manually re-copy. */
    private var lastCopyableText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        stateText = findViewById(R.id.stateText)
        resultText = findViewById(R.id.resultText)
        recordButton = findViewById(R.id.recordButton)
        copyButton = findViewById(R.id.copyButton)

        configureRecordButton()
        configureCopyButton()
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
                VoiceSessionBus.resetIdle("权限已开启，按住按钮开始。")
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

    @SuppressLint("ClickableViewAccessibility")
    private fun configureRecordButton() {
        recordButton.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    VoiceSessionBus.holdPressed.set(true)
                    startHoldRecording()
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    VoiceSessionBus.holdPressed.set(false)
                    finishHoldRecording()
                    true
                }

                else -> false
            }
        }
    }

    private fun configureCopyButton() {
        copyButton.setOnClickListener {
            val text = copyableText() ?: return@setOnClickListener
            writeClipboard(text, toast = true)
        }
        updateCopyButtonEnabled()
    }

    private fun startHoldRecording() {
        PermissionGate.ensureVoicePermissions(this) {
            VoiceCaptureService.start(this)
        }
    }

    private fun finishHoldRecording() {
        // Works even while service is still Connecting — Service cancels/stops appropriately.
        VoiceCaptureService.stop(this)
    }

    private fun renderState(state: VoiceUiState) {
        runOnUiThread {
            stateText.text = state.title
            resultText.text = state.detail
            rememberCopyable(state)
            maybeAutoCopyFinal(state)
            updateCopyButtonEnabled()
        }
    }

    private fun rememberCopyable(state: VoiceUiState) {
        val text = state.finalText?.trim().orEmpty()
        if (state.phase == VoicePhase.Succeeded && text.isNotEmpty()) {
            lastCopyableText = text
        }
    }

    /**
     * Auto-copy only non-blank Succeeded finals, once per session.
     * Cancelled / Failed / ClosedWithoutResult / blank must never reach here with copyable text.
     */
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
