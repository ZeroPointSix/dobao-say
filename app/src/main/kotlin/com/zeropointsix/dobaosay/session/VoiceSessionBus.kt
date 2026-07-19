package com.zeropointsix.dobaosay.session

import java.util.concurrent.CopyOnWriteArraySet

enum class VoicePhase {
    Idle,
    Connecting,
    Recording,
    Recognizing,
    Stopping,
    Succeeded,
    Failed,
    Cancelled,
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Idle,
    val title: String = "空闲",
    val detail: String = "按住下方按钮开始录音，松开结束。",
    val finalText: String? = null,
    val sessionId: String? = null,
)

/**
 * Process-local fan-out so Activity can observe Service session state without AndroidX.
 */
object VoiceSessionBus {
    @Volatile
    var latest: VoiceUiState = VoiceUiState()
        private set

    private val listeners = CopyOnWriteArraySet<(VoiceUiState) -> Unit>()

    fun publish(state: VoiceUiState) {
        latest = state
        listeners.forEach { listener ->
            runCatching { listener(state) }
        }
    }

    fun subscribe(listener: (VoiceUiState) -> Unit): () -> Unit {
        listeners += listener
        listener(latest)
        return { listeners -= listener }
    }

    fun resetIdle(detail: String = "按住下方按钮开始录音，松开结束。") {
        publish(
            VoiceUiState(
                phase = VoicePhase.Idle,
                title = "空闲",
                detail = detail,
            ),
        )
    }
}
