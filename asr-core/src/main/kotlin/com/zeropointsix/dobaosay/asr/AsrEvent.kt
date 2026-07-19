package com.zeropointsix.dobaosay.asr

sealed interface AsrEvent {
    val sequence: Long
    val elapsedMs: Long

    data class Connecting(
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class Ready(
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class SpeechStarted(
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class Partial(
        val utteranceId: String,
        val text: String,
        val revision: Long,
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class Final(
        val resultId: String,
        val utteranceId: String,
        val text: String,
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class SpeechEnded(
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class Retrying(
        val attempt: Int,
        val failure: AsrFailure,
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class Error(
        val failure: AsrFailure,
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent

    data class Closed(
        val outcome: SessionOutcome,
        override val sequence: Long,
        override val elapsedMs: Long,
    ) : AsrEvent
}

sealed interface DriverSignal {
    data object Ready : DriverSignal

    data object SpeechStarted : DriverSignal

    data class Partial(
        val utteranceId: String,
        val text: String,
        val revision: Long,
    ) : DriverSignal

    data class Final(
        val resultId: String,
        val utteranceId: String,
        val text: String,
    ) : DriverSignal

    data object SpeechEnded : DriverSignal

    data class Retrying(
        val attempt: Int,
        val failure: AsrFailure,
    ) : DriverSignal

    data class Failed(
        val failure: AsrFailure,
    ) : DriverSignal

    data object RemoteClosed : DriverSignal
}
