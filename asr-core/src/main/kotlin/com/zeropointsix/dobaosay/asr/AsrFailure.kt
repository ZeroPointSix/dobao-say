package com.zeropointsix.dobaosay.asr

import kotlin.time.Duration

sealed interface RetryDisposition {
    data object Never : RetryDisposition
    data object Immediate : RetryDisposition
    data object Backoff : RetryDisposition
    data class After(val delay: Duration) : RetryDisposition
}

sealed interface AsrFailure {
    val code: String
    val retry: RetryDisposition

    data class InvalidAudio(val reason: String) : AsrFailure {
        override val code = "invalid_audio"
        override val retry = RetryDisposition.Never
    }

    data class InvalidState(val command: String, val state: AsrSessionState) : AsrFailure {
        override val code = "invalid_state"
        override val retry = RetryDisposition.Never
    }

    data object PermissionDenied : AsrFailure {
        override val code = "permission_denied"
        override val retry = RetryDisposition.Never
    }

    data object NetworkUnavailable : AsrFailure {
        override val code = "network_unavailable"
        override val retry = RetryDisposition.Backoff
    }

    data class RateLimited(val retryAfter: Duration) : AsrFailure {
        override val code = "rate_limited"
        override val retry = RetryDisposition.After(retryAfter)
    }

    data object RiskControlled : AsrFailure {
        override val code = "risk_controlled"
        override val retry = RetryDisposition.Never
    }

    data class ProtocolViolation(val diagnosticCode: String) : AsrFailure {
        override val code = "protocol_violation"
        override val retry = RetryDisposition.Never
    }

    data class Timeout(val phase: TimeoutPhase) : AsrFailure {
        override val code = "timeout_${phase.name.lowercase()}"
        override val retry = RetryDisposition.Backoff
    }

    data class Internal(val diagnosticCode: String) : AsrFailure {
        override val code = "internal"
        override val retry = RetryDisposition.Never
    }
}

enum class TimeoutPhase { CONNECT, FINAL, SESSION }
