package com.zeropointsix.dobaosay.audio

import com.zeropointsix.dobaosay.asr.AudioFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.ZERO

sealed interface LoopbackSendResult {
    data object Accepted : LoopbackSendResult

    data object Closed : LoopbackSendResult
}

data class LoopbackStats(
    val capacity: Int,
    val queuedFrames: Int = 0,
    val maxQueuedFrames: Int = 0,
    val acceptedFrames: Long = 0,
    val receivedFrames: Long = 0,
    val activeSenders: Int = 0,
    val closeCount: Int = 0,
)

/**
 * Provider-neutral, fully in-memory audio transport with strict bounded backpressure.
 *
 * At most [capacity] frames are queued. Additional [send] calls suspend without allocating queue
 * entries. Closing the transport wakes suspended senders with [LoopbackSendResult.Closed].
 * No worker coroutine is created, so close/cancel cannot leave a background worker behind.
 */
class InMemoryLoopbackTransport(
    private val capacity: Int,
    private val deliveryDelay: Duration = ZERO,
) : AutoCloseable {
    private val frames: Channel<AudioFrame>
    private val permits: Channel<Unit>
    private val closedSignal = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private val statsLock = Any()
    private val mutableStats: MutableStateFlow<LoopbackStats>

    val stats: StateFlow<LoopbackStats>
        get() = mutableStats.asStateFlow()

    init {
        require(capacity > 0) { "Capacity must be positive" }
        require(!deliveryDelay.isNegative()) { "Delivery delay must not be negative" }
        frames = Channel(capacity)
        permits = Channel(capacity)
        repeat(capacity) {
            check(permits.trySend(Unit).isSuccess)
        }
        mutableStats = MutableStateFlow(LoopbackStats(capacity = capacity))
    }

    suspend fun send(frame: AudioFrame): LoopbackSendResult {
        updateStats { it.copy(activeSenders = it.activeSenders + 1) }
        try {
            if (closed.get()) return LoopbackSendResult.Closed
            val acquired =
                select {
                    permits.onReceiveCatching { it.isSuccess }
                    closedSignal.onAwait { false }
                }
            if (!acquired || closed.get()) {
                if (acquired) permits.trySend(Unit)
                return LoopbackSendResult.Closed
            }

            if (frames.trySend(frame).isFailure) {
                permits.trySend(Unit)
                return LoopbackSendResult.Closed
            }
            updateStats {
                val queued = it.queuedFrames + 1
                it.copy(
                    queuedFrames = queued,
                    maxQueuedFrames = maxOf(it.maxQueuedFrames, queued),
                    acceptedFrames = it.acceptedFrames + 1,
                )
            }
            return LoopbackSendResult.Accepted
        } finally {
            updateStats { it.copy(activeSenders = (it.activeSenders - 1).coerceAtLeast(0)) }
        }
    }

    suspend fun receive(): AudioFrame? {
        val frame = frames.receiveCatching().getOrNull() ?: return null
        if (deliveryDelay.isPositive()) delay(deliveryDelay)
        updateStats {
            it.copy(
                queuedFrames = (it.queuedFrames - 1).coerceAtLeast(0),
                receivedFrames = it.receivedFrames + 1,
            )
        }
        permits.trySend(Unit)
        return frame
    }

    fun stop(): Boolean = closeOnce()

    fun cancel(): Boolean = closeOnce()

    override fun close() {
        closeOnce()
    }

    fun isClosed(): Boolean = closed.get()

    private fun closeOnce(): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        closedSignal.complete(Unit)
        frames.cancel()
        permits.close()
        updateStats { it.copy(queuedFrames = 0, closeCount = it.closeCount + 1) }
        return true
    }

    private inline fun updateStats(transform: (LoopbackStats) -> LoopbackStats) {
        synchronized(statsLock) {
            mutableStats.value = transform(mutableStats.value)
        }
    }
}
