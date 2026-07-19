package com.zeropointsix.dobaosay.audio

import com.zeropointsix.dobaosay.asr.AudioFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class InMemoryLoopbackTransportStressTest {
    @Test
    fun `real scheduler concurrent send receive and close preserves bounded accounting`() =
        runBlocking {
            val transport = InMemoryLoopbackTransport(capacity = 8, deliveryDelay = 1.milliseconds)
            val sequence = AtomicLong()

            withTimeout(10.seconds) {
                coroutineScope {
                    val senders =
                        List(12) {
                            launch(Dispatchers.Default) {
                                repeat(200) {
                                    val next = sequence.getAndIncrement()
                                    if (transport.send(frame(next)) == LoopbackSendResult.Closed) return@launch
                                }
                            }
                        }
                    val receivers =
                        List(4) {
                            launch(Dispatchers.Default) {
                                while (transport.receive() != null) {
                                    // Continue until close cancels the in-memory queue.
                                }
                            }
                        }
                    val closer =
                        launch(Dispatchers.Default) {
                            delay(50.milliseconds)
                            transport.close()
                        }

                    (senders + receivers + closer).joinAll()
                }
            }

            val stats = transport.stats.value
            assertTrue(transport.isClosed())
            assertTrue(stats.maxQueuedFrames <= stats.capacity)
            assertEquals(0, stats.queuedFrames)
            assertEquals(0, stats.inDeliveryFrames)
            assertEquals(0, stats.activeSenders)
            assertEquals(1, stats.closeCount)
            assertEquals(
                stats.acceptedFrames,
                stats.queuedFrames.toLong() +
                    stats.inDeliveryFrames +
                    stats.receivedFrames +
                    stats.droppedOnCloseFrames +
                    stats.droppedOnCancelFrames,
            )
        }

    private fun frame(sequence: Long): AudioFrame = AudioFrame(sequence, sequence * 20, ByteArray(640))
}
