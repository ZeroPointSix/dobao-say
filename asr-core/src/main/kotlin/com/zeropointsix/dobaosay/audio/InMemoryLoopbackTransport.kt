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
    val inDeliveryFrames: Int = 0,
    val maxQueuedFrames: Int = 0,
    val acceptedFrames: Long = 0,
    val receivedFrames: Long = 0,
    val droppedOnCloseFrames: Long = 0,
    val activeSenders: Int = 0,
    val closeCount: Int = 0,
)

/**
 * Provider-neutral, fully in-memory audio transport with strict bounded backpressure.
 *
 * At most [capacity] frames are queued. Additional [send] calls suspend without allocating queue
 * entries. Closing the transport drops queued frames and wakes suspended senders with
 * [LoopbackSendResult.Closed]. No worker coroutine is created, so close/cancel cannot leave a
 * background worker behind.
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
            if (!acquired) return LoopbackSendResult.Closed

            return synchronized(statsLock) {
                if (closed.get()) {
                    permits.trySend(Unit)
                    LoopbackSendResult.Closed
                } else if (frames.trySend(frame).isFailure) {
                    permits.trySend(Unit)
                    LoopbackSendResult.Closed
                } else {
                    val current = mutableStats.value
                    val queued = current.queuedFrames + 1
                    mutableStats.value =
                        current.copy(
                            queuedFrames = queued,
                            maxQueuedFrames = maxOf(current.maxQueuedFrames, queued),
                            acceptedFrames = current.acceptedFrames + 1,
                        )
                    LoopbackSendResult.Accepted
                }
            }
        } finally {
            updateStats { it.copy(activeSenders = (it.activeSenders - 1).coerceAtLeast(0)) }
        }
    }

    suspend fun receive(): AudioFrame? {
        val frame = frames.receiveCatching().getOrNull() ?: return null
        updateStats {
            it.copy(
                queuedFrames = it.queuedFrames - 1,
                inDeliveryFrames = it.inDeliveryFrames + 1,
            )
        }
        var delivered = false
        try {
            if (deliveryDelay.isPositive()) delay(deliveryDelay)
            delivered = true
            return frame
        } finally {
            updateStats {
                it.copy(
                    inDeliveryFrames = it.inDeliveryFrames - 1,
                    receivedFrames = it.receivedFrames + if (delivered) 1 else 0,
                )
            }
            permits.trySend(Unit)
        }
    }

    fun stop(): Boolean = closeOnce()

    fun cancel(): Boolean = closeOnce()

    override fun close() {
        closeOnce()
    }

    fun isClosed(): Boolean = closed.get()

    private fun closeOnce(): Boolean =
        synchronized(statsLock) {
            if (!closed.compareAndSet(false, true)) return@synchronized false
            closedSignal.complete(Unit)
            frames.cancel()
            permits.close()
            val current = mutableStats.value
            mutableStats.value =
                current.copy(
                    queuedFrames = 0,
                    droppedOnCloseFrames = current.droppedOnCloseFrames + current.queuedFrames,
                    closeCount = current.closeCount + 1,
                )
            true
        }

    private inline fun updateStats(transform: (LoopbackStats) -> LoopbackStats) {
        synchronized(statsLock) {
            mutableStats.value = transform(mutableStats.value)
        }
    }
}
