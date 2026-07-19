package com.zeropointsix.dobaosay.asr

import com.zeropointsix.dobaosay.asr.testing.FakeAsrDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAsrSessionTest {
    @Test
    fun `manual stop emits one final and releases once`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = DefaultAsrSession(AsrSessionConfig(), driver, clockMs = { testScheduler.currentTime })
            val events = async(start = CoroutineStart.UNDISPATCHED) { session.events.take(4).toList() }

            assertEquals(AsrCommandResult.Accepted, session.start())
            driver.awaitConnected()
            driver.emit(DriverSignal.Ready)
            assertEquals(AsrCommandResult.Accepted, session.pushAudio(frame(0)))
            assertEquals(AsrCommandResult.Accepted, session.stop())
            driver.awaitEffects { stopCount == 1 }
            driver.emit(DriverSignal.Final("result-1", "utterance-1", "测试文本"))
            driver.emit(DriverSignal.Final("result-1", "utterance-1", "重复文本"))
            session.awaitClosed()
            driver.awaitEffects { releaseCount == 1 }

            assertIs<SessionOutcome.Succeeded>((session.snapshot.value.state as AsrSessionState.Closed).outcome)
            assertEquals(1, driver.stopCount)
            assertEquals(1, driver.releaseCount)
            assertEquals(1, events.await().filterIsInstance<AsrEvent.Final>().size)
        }

    @Test
    fun `different concurrent finals commit only one result`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)
            val events = Collections.synchronizedList(mutableListOf<AsrEvent>())
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { events += it }
            }

            coroutineScope {
                listOf(
                    async(Dispatchers.Default) {
                        driver.emit(DriverSignal.Final("result-a", "utterance", "甲"))
                    },
                    async(Dispatchers.Default) {
                        driver.emit(DriverSignal.Final("result-b", "utterance", "乙"))
                    },
                ).awaitAll()
            }
            session.awaitClosed()
            driver.awaitEffects { releaseCount == 1 }
            runCurrent()

            assertEquals(1, events.filterIsInstance<AsrEvent.Final>().size)
            assertEquals(1, events.filterIsInstance<AsrEvent.Closed>().size)
            assertEquals(1, driver.releaseCount)
            assertIs<SessionOutcome.Succeeded>((session.snapshot.value.state as AsrSessionState.Closed).outcome)
        }

    @Test
    fun `final failure and cancel race commits only one terminal outcome`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)
            val events = Collections.synchronizedList(mutableListOf<AsrEvent>())
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { events += it }
            }

            coroutineScope {
                listOf(
                    async(Dispatchers.Default) {
                        driver.emit(DriverSignal.Final("result", "utterance", "成功候选"))
                    },
                    async(Dispatchers.Default) {
                        driver.emit(DriverSignal.Failed(AsrFailure.NetworkUnavailable))
                    },
                    async(Dispatchers.Default) {
                        session.cancel(CancelReason.SUPERSEDED)
                    },
                ).awaitAll()
            }
            session.awaitClosed()
            driver.awaitEffects { releaseCount == 1 }
            runCurrent()

            assertEquals(1, events.filterIsInstance<AsrEvent.Closed>().size)
            assertTrue(events.filterIsInstance<AsrEvent.Final>().size <= 1)
            assertTrue(events.filterIsInstance<AsrEvent.Error>().size <= 1)
            assertEquals(1, driver.releaseCount)
            assertIs<AsrSessionState.Closed>(session.snapshot.value.state)
        }

    @Test
    fun `concurrent pushes preserve actor submission order`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)

            val pushes =
                (0L until 16L).map { sequence ->
                    async(start = CoroutineStart.UNDISPATCHED) { session.pushAudio(frame(sequence)) }
                }

            assertTrue(pushes.awaitAll().all { it == AsrCommandResult.Accepted })
            driver.awaitEffects { receivedFrames.size == 16 }
            assertEquals((0L until 16L).toList(), driver.receivedFrames.map(AudioFrame::sequence))
            session.cancel(CancelReason.USER)
        }

    @Test
    fun `push and stop are ordered and no push effect occurs after stop`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)

            val push = async(start = CoroutineStart.UNDISPATCHED) { session.pushAudio(frame(0)) }
            val stop = async(start = CoroutineStart.UNDISPATCHED) { session.stop() }

            assertEquals(AsrCommandResult.Accepted, push.await())
            assertEquals(AsrCommandResult.Accepted, stop.await())
            driver.awaitEffects { effects.size >= 3 }
            assertEquals(listOf("connect", "push:0", "stop"), driver.effects.toList())
            assertIs<AsrCommandResult.Rejected>(session.pushAudio(frame(1)))
            assertEquals(1, driver.receivedFrames.size)
            session.cancel(CancelReason.USER)
        }

    @Test
    fun `duplicate concurrent cancel aborts and releases once`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)

            val results =
                coroutineScope {
                    listOf(
                        async(Dispatchers.Default) { session.cancel(CancelReason.USER) },
                        async(Dispatchers.Default) { session.cancel(CancelReason.APP_BACKGROUNDED) },
                    ).awaitAll()
                }
            driver.awaitEffects { releaseCount == 1 }

            assertEquals(1, results.count { it == AsrCommandResult.Accepted })
            assertEquals(1, results.count { it == AsrCommandResult.IgnoredAlreadyHandled })
            assertEquals(1, driver.abortCount)
            assertEquals(1, driver.releaseCount)
            assertEquals("release", driver.effects.last())
        }

    @Test
    fun `VAD requests stop exactly once`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)

            driver.emit(DriverSignal.SpeechEnded)
            driver.emit(DriverSignal.SpeechEnded)
            session.awaitState { it is AsrSessionState.Stopping }
            driver.awaitEffects { stopCount == 1 }
            assertEquals(AsrCommandResult.IgnoredAlreadyHandled, session.stop())

            assertEquals(1, driver.stopCount)
            assertEquals(AsrSessionState.Stopping(StopReason.VAD), session.snapshot.value.state)
            session.cancel(CancelReason.USER)
        }

    @Test
    fun `remote close before final is a deterministic failure`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)

            driver.emit(DriverSignal.RemoteClosed)
            session.awaitClosed()
            driver.awaitEffects { releaseCount == 1 }

            val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
            val failure = assertIs<SessionOutcome.Failed>(outcome).failure
            assertEquals(AsrFailure.ProtocolViolation("remote_closed_before_final"), failure)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `connect callback from another coroutine never deadlocks the actor`() =
        runTest {
            val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val driver =
                object : AsrDriver {
                    override suspend fun connect(sink: suspend (DriverSignal) -> Unit) {
                        callbackScope.async { sink(DriverSignal.Ready) }.await()
                    }

                    override suspend fun sendAudio(frame: AudioFrame) = Unit

                    override suspend fun requestStop() = Unit

                    override suspend fun abort() = Unit

                    override suspend fun release() = Unit
                }
            val session = DefaultAsrSession(AsrSessionConfig(), driver)

            assertEquals(
                AsrCommandResult.Accepted,
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { session.start() } },
            )
            session.awaitState { it == AsrSessionState.Ready }
            session.cancel(CancelReason.USER)
            callbackScope.cancel()
        }

    @Test
    fun `stop and close are idempotent`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)

            session.stop()
            driver.awaitEffects { stopCount == 1 }
            assertEquals(AsrCommandResult.IgnoredAlreadyHandled, session.stop())
            session.close()
            driver.awaitEffects { releaseCount == 1 }
            assertEquals(AsrCommandResult.IgnoredAlreadyHandled, session.close())

            assertEquals(1, driver.stopCount)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `invalid audio is rejected before driver`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)
            val result = session.pushAudio(AudioFrame(0, 0, ByteArray(639)))

            assertIs<AsrCommandResult.Rejected>(result)
            assertIs<AsrFailure.InvalidAudio>(result.failure)
            assertEquals(0, driver.receivedFrames.size)
            session.cancel(CancelReason.USER)
        }

    @Test
    fun `cancel never produces a success record`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver)
            session.cancel(CancelReason.APP_BACKGROUNDED)
            driver.awaitEffects { releaseCount == 1 }

            val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
            assertIs<SessionOutcome.Cancelled>(outcome)
            assertEquals(null, session.snapshot.value.finalResult)
            assertEquals(1, driver.abortCount)
            assertEquals(1, driver.releaseCount)
        }

    private suspend fun readySession(driver: FakeAsrDriver): DefaultAsrSession {
        val session = DefaultAsrSession(AsrSessionConfig(), driver)
        assertEquals(AsrCommandResult.Accepted, session.start())
        driver.awaitConnected()
        driver.emit(DriverSignal.Ready)
        session.awaitState { it == AsrSessionState.Ready }
        assertEquals(AsrSessionState.Ready, session.snapshot.value.state)
        return session
    }

    private suspend fun DefaultAsrSession.awaitClosed() {
        awaitState { it is AsrSessionState.Closed }
        close()
    }

    private suspend fun DefaultAsrSession.awaitState(predicate: (AsrSessionState) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                snapshot.first { predicate(it.state) }
            }
        }
    }

    private fun frame(sequence: Long): AudioFrame = AudioFrame(sequence, sequence * 20, ByteArray(640))

    private suspend fun FakeAsrDriver.awaitConnected() {
        awaitEffects { connectCount == 1 }
    }

    private suspend fun FakeAsrDriver.awaitEffects(predicate: FakeAsrDriver.() -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (!predicate()) yield()
            }
        }
    }
}
