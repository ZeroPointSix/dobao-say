package com.zeropointsix.dobaosay.audio

import com.zeropointsix.dobaosay.asr.AudioFrame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class InMemoryLoopbackTransportTest {
    @Test
    fun `queue is strictly bounded and slow consumer backpressures sender`() =
        runTest {
            val transport = InMemoryLoopbackTransport(capacity = 1)
            assertEquals(LoopbackSendResult.Accepted, transport.send(frame(0)))

            val second = async(start = CoroutineStart.UNDISPATCHED) { transport.send(frame(1)) }
            runCurrent()

            assertTrue(second.isActive)
            assertEquals(1, transport.stats.value.queuedFrames)
            assertEquals(1, transport.stats.value.maxQueuedFrames)
            assertEquals(1, transport.stats.value.activeSenders)

            assertEquals(0, transport.receive()?.sequence)
            assertEquals(LoopbackSendResult.Accepted, second.await())
            assertEquals(1, transport.receive()?.sequence)
            assertEquals(0, transport.stats.value.queuedFrames)
            assertEquals(2, transport.stats.value.receivedFrames)
            transport.close()
        }

    @Test
    fun `close wakes a sender suspended by full capacity`() =
        runTest {
            val transport = InMemoryLoopbackTransport(capacity = 1)
            transport.send(frame(0))
            val blocked = async(start = CoroutineStart.UNDISPATCHED) { transport.send(frame(1)) }
            runCurrent()
            assertTrue(blocked.isActive)

            assertTrue(transport.stop())

            assertEquals(LoopbackSendResult.Closed, blocked.await())
            assertEquals(0, transport.stats.value.activeSenders)
            assertEquals(0, transport.stats.value.queuedFrames)
            assertEquals(1, transport.stats.value.droppedOnCloseFrames)
            assertNull(transport.receive())
        }

    @Test
    fun `cancelling receive during delay releases capacity`() =
        runTest {
            val transport = InMemoryLoopbackTransport(capacity = 1, deliveryDelay = 10.seconds)
            transport.send(frame(0))
            val receive = async(start = CoroutineStart.UNDISPATCHED) { transport.receive() }
            runCurrent()
            assertEquals(1, transport.stats.value.inDeliveryFrames)

            receive.cancelAndJoin()

            assertEquals(0, transport.stats.value.inDeliveryFrames)
            assertEquals(0, transport.stats.value.receivedFrames)
            assertEquals(LoopbackSendResult.Accepted, transport.send(frame(1)))
            transport.close()
        }

    @Test
    fun `close during delayed receive has consistent accounting and idempotent lifecycle`() =
        runTest {
            val transport = InMemoryLoopbackTransport(capacity = 1, deliveryDelay = 10.seconds)
            transport.send(frame(0))
            val receive = async(start = CoroutineStart.UNDISPATCHED) { transport.receive() }
            runCurrent()
            assertEquals(1, transport.stats.value.inDeliveryFrames)

            assertTrue(transport.cancel())
            assertFalse(transport.stop())
            transport.close()
            advanceTimeBy(10.seconds)
            assertEquals(0, receive.await()?.sequence)

            val stats = transport.stats.value
            assertEquals(0, stats.queuedFrames)
            assertEquals(0, stats.inDeliveryFrames)
            assertEquals(1, stats.receivedFrames)
            assertEquals(0, stats.droppedOnCloseFrames)
            assertEquals(1, stats.closeCount)
            assertTrue(transport.isClosed())
        }

    @Test
    fun `cancelled blocked sender leaves no active operation`() =
        runTest {
            val transport = InMemoryLoopbackTransport(capacity = 1)
            transport.send(frame(0))
            val blocked = async(start = CoroutineStart.UNDISPATCHED) { transport.send(frame(1)) }
            runCurrent()

            blocked.cancelAndJoin()

            assertEquals(0, transport.stats.value.activeSenders)
            assertEquals(0, transport.receive()?.sequence)
            assertEquals(LoopbackSendResult.Accepted, transport.send(frame(2)))
            transport.close()
        }

    @Test
    fun `invalid construction is rejected`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            InMemoryLoopbackTransport(capacity = 0)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            InMemoryLoopbackTransport(capacity = 1, deliveryDelay = -1.seconds)
        }
    }

    private fun frame(sequence: Long): AudioFrame = AudioFrame(sequence, sequence * 20, ByteArray(640))
}
