package com.zeropointsix.dobaosay.asr

sealed interface AsrSessionState {
    data object Created : AsrSessionState
    data object Connecting : AsrSessionState
    data object Ready : AsrSessionState
    data object Streaming : AsrSessionState
    data class Stopping(val reason: StopReason) : AsrSessionState
    data class Closed(val outcome: SessionOutcome) : AsrSessionState
}

sealed interface SessionOutcome {
    data class Succeeded(val resultId: String, val text: String) : SessionOutcome
    data class Failed(val failure: AsrFailure) : SessionOutcome
    data class Cancelled(val reason: CancelReason) : SessionOutcome
    data object ClosedWithoutResult : SessionOutcome
}

data class AsrSessionSnapshot(
    val state: AsrSessionState = AsrSessionState.Created,
    val partialText: String? = null,
    val finalResult: SessionOutcome.Succeeded? = null,
)
