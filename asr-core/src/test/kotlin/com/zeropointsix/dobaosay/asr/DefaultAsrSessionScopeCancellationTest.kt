package com.zeropointsix.dobaosay.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

class DefaultAsrSessionScopeCancellationTest {
    @Test
    fun `external scope cancellation completes queued and later API calls`() =
        runBlocking {
            val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val driver = FloodingHangingDriver()
            val session = DefaultAsrSession(AsrSessionConfig(), driver, scope = sessionScope)
            val firstEvent = CompletableDeferred<Unit>()
            val collector =
                collectorScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.events.collect {
                        firstEvent.complete(Unit)
                        awaitCancellation()
                    }
                }

            try {
                assertEquals(
                    AsrCommandResult.Accepted,
                    withTimeout(5.seconds) { session.start() },
                )
                withTimeout(5.seconds) {
                    firstEvent.await()
                    driver.signalsQueued.await()
                }

                val pendingPush =
                    async(Dispatchers.Default) {
                        session.pushAudio(AudioFrame(0, 0, ByteArray(640)))
                    }
                yield()
                assertFalse(pendingPush.isCompleted)

                val rootJob = checkNotNull(sessionScope.coroutineContext[Job])
                rootJob.cancelAndJoin()

                assertEquals(
                    AsrCommandResult.IgnoredAlreadyHandled,
                    withTimeout(5.seconds) { pendingPush.await() },
                )
                val laterResults =
                    withTimeout(5.seconds) {
                        listOf(
                            session.start(),
                            session.pushAudio(AudioFrame(1, 20, ByteArray(640))),
                            session.stop(),
                            session.cancel(CancelReason.USER),
                            session.close(),
                        )
                    }

                assertEquals(List(5) { AsrCommandResult.IgnoredAlreadyHandled }, laterResults)
                withTimeout(5.seconds) { driver.connectCancelled.await() }
                assertFalse(rootJob.children.any())
                assertEquals(listOf("connect"), driver.effects.toList())
            } finally {
                sessionScope.cancel()
                collectorScope.cancel()
                collector.cancelAndJoin()
            }
        }

    private class FloodingHangingDriver : AsrDriver {
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val signalsQueued = CompletableDeferred<Unit>()
        val connectCancelled = CompletableDeferred<Unit>()

        override suspend fun connect(sink: suspend (DriverSignal) -> Unit) {
            effects += "connect"
            try {
                sink(DriverSignal.Ready)
                repeat(128) { revision ->
                    sink(
                        DriverSignal.Partial(
                            utteranceId = "scope-cancel",
                            text = "pending",
                            revision = revision.toLong(),
                        ),
                    )
                }
                signalsQueued.complete(Unit)
                awaitCancellation()
            } finally {
                connectCancelled.complete(Unit)
            }
        }

        override suspend fun sendAudio(frame: AudioFrame) {
            effects += "audio:${frame.sequence}"
        }

        override suspend fun requestStop() {
            effects += "stop"
        }

        override suspend fun abort() {
            effects += "abort"
        }

        override suspend fun release() {
            effects += "release"
        }
    }
}
