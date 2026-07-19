package com.zeropointsix.dobaosay.asr

import com.zeropointsix.dobaosay.asr.testing.FakeAsrDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAsrSessionTimeoutTest {
    @Test
    fun `connect timeout closes and releases once`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = session(driver, connectSeconds = 1, sessionSeconds = 10)

            assertEquals(AsrCommandResult.Accepted, session.start())
            advanceTimeBy(1.seconds)
            runCurrent()

            assertTimeout(session, TimeoutPhase.CONNECT)
            assertEquals(1, driver.abortCount)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `manual stop final timeout closes and releases once`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver, finalSeconds = 1)

            assertEquals(AsrCommandResult.Accepted, session.stop(StopReason.MANUAL))
            advanceTimeBy(1.seconds)
            runCurrent()

            assertTimeout(session, TimeoutPhase.FINAL)
            assertEquals(1, driver.abortCount)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `VAD final timeout closes and releases once`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver, finalSeconds = 1)

            driver.emit(DriverSignal.SpeechEnded)
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()

            assertTimeout(session, TimeoutPhase.FINAL)
            assertEquals(1, driver.stopCount)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `session timeout applies after ready`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver, sessionSeconds = 2)

            advanceTimeBy(2.seconds)
            runCurrent()

            assertTimeout(session, TimeoutPhase.SESSION)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `final before deadline is never reversed by stale timeout`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver, finalSeconds = 1, sessionSeconds = 10)

            session.stop()
            advanceTimeBy(999)
            driver.emit(DriverSignal.Final("result", "utterance", "按时完成"))
            runCurrent()
            advanceTimeBy(2.seconds)
            runCurrent()

            val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
            assertEquals(SessionOutcome.Succeeded("result", "按时完成"), outcome)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `timeout and final at same deadline commit one terminal outcome`() =
        runTest {
            val driver = FakeAsrDriver()
            val session = readySession(driver, finalSeconds = 1, sessionSeconds = 10)
            var closedEvents = 0
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { if (it is AsrEvent.Closed) closedEvents += 1 }
            }

            session.stop()
            backgroundScope.launch {
                delay(1.seconds)
                driver.emit(DriverSignal.Final("result", "utterance", "竞态候选"))
            }
            advanceTimeBy(1.seconds)
            runCurrent()

            assertIs<AsrSessionState.Closed>(session.snapshot.value.state)
            assertEquals(1, closedEvents)
            assertEquals(1, driver.releaseCount)
        }

    @Test
    fun `hanging connect cannot block connect timeout`() =
        runTest {
            val driver = HangingDriver(HangAt.CONNECT)
            val session = session(driver, connectSeconds = 1, sessionSeconds = 10)
            val closedEvents = collectClosedEvents(session)

            assertEquals(AsrCommandResult.Accepted, session.start())
            runCurrent()
            assertEquals(HangAt.CONNECT, driver.started.await())
            advanceTimeBy(1.seconds)
            runCurrent()

            assertTimeout(session, TimeoutPhase.CONNECT)
            assertEquals(HangAt.CONNECT, driver.cancelled.await())
            assertEquals(1, closedEvents.get())
            assertEquals(1, driver.abortCount)
            assertEquals(1, driver.releaseCount)
            assertEquals(listOf("connect", "abort", "release"), driver.effects.toList())
        }

    @Test
    fun `hanging send audio cannot block session timeout`() =
        runTest {
            val driver = HangingDriver(HangAt.AUDIO)
            val session = readySession(driver, sessionSeconds = 1)
            val closedEvents = collectClosedEvents(session)

            assertEquals(AsrCommandResult.Accepted, session.pushAudio(frame(0)))
            runCurrent()
            assertEquals(HangAt.AUDIO, driver.started.await())
            advanceTimeBy(1.seconds)
            runCurrent()

            assertTimeout(session, TimeoutPhase.SESSION)
            assertEquals(HangAt.AUDIO, driver.cancelled.await())
            assertEquals(1, closedEvents.get())
            assertEquals(1, driver.abortCount)
            assertEquals(1, driver.releaseCount)
            assertEquals(listOf("connect", "audio:0", "abort", "release"), driver.effects.toList())
        }

    @Test
    fun `hanging request stop cannot block final timeout`() =
        runTest {
            val driver = HangingDriver(HangAt.STOP)
            val session = readySession(driver, finalSeconds = 1, sessionSeconds = 10)
            val closedEvents = collectClosedEvents(session)

            assertEquals(AsrCommandResult.Accepted, session.stop())
            runCurrent()
            assertEquals(HangAt.STOP, driver.started.await())
            advanceTimeBy(1.seconds)
            runCurrent()

            assertTimeout(session, TimeoutPhase.FINAL)
            assertEquals(HangAt.STOP, driver.cancelled.await())
            assertEquals(1, closedEvents.get())
            assertEquals(1, driver.abortCount)
            assertEquals(1, driver.releaseCount)
            assertEquals(listOf("connect", "stop", "abort", "release"), driver.effects.toList())
        }

    private fun TestScope.session(
        driver: AsrDriver,
        connectSeconds: Int = 5,
        finalSeconds: Int = 5,
        sessionSeconds: Int = 30,
    ): DefaultAsrSession =
        DefaultAsrSession(
            config =
                AsrSessionConfig(
                    connectTimeout = connectSeconds.seconds,
                    stopFinalTimeout = finalSeconds.seconds,
                    sessionTimeout = sessionSeconds.seconds,
                ),
            driver = driver,
            scope = backgroundScope,
            clockMs = { testScheduler.currentTime },
        )

    private suspend fun TestScope.readySession(
        driver: FakeAsrDriver,
        finalSeconds: Int = 5,
        sessionSeconds: Int = 30,
    ): DefaultAsrSession {
        val session = session(driver, finalSeconds = finalSeconds, sessionSeconds = sessionSeconds)
        assertEquals(AsrCommandResult.Accepted, session.start())
        runCurrent()
        driver.emit(DriverSignal.Ready)
        runCurrent()
        assertEquals(AsrSessionState.Ready, session.snapshot.value.state)
        return session
    }

    private suspend fun TestScope.readySession(
        driver: HangingDriver,
        finalSeconds: Int = 5,
        sessionSeconds: Int = 30,
    ): DefaultAsrSession {
        val session = session(driver, finalSeconds = finalSeconds, sessionSeconds = sessionSeconds)
        assertEquals(AsrCommandResult.Accepted, session.start())
        runCurrent()
        assertEquals(AsrSessionState.Ready, session.snapshot.value.state)
        return session
    }

    private fun assertTimeout(
        session: DefaultAsrSession,
        phase: TimeoutPhase,
    ) {
        val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
        assertEquals(SessionOutcome.Failed(AsrFailure.Timeout(phase)), outcome)
    }

    private fun frame(sequence: Long): AudioFrame = AudioFrame(sequence, sequence * 20, ByteArray(640))

    private fun TestScope.collectClosedEvents(session: DefaultAsrSession): AtomicInteger =
        AtomicInteger().also { count ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { if (it is AsrEvent.Closed) count.incrementAndGet() }
            }
        }

    private enum class HangAt { CONNECT, AUDIO, STOP }

    private class HangingDriver(
        private val hangAt: HangAt,
    ) : AsrDriver {
        val effects = Collections.synchronizedList(mutableListOf<String>())
        val started = CompletableDeferred<HangAt>()
        val cancelled = CompletableDeferred<HangAt>()
        private val aborts = AtomicInteger()
        private val releases = AtomicInteger()

        val abortCount: Int get() = aborts.get()
        val releaseCount: Int get() = releases.get()

        override suspend fun connect(sink: suspend (DriverSignal) -> Unit) {
            effects += "connect"
            if (hangAt == HangAt.CONNECT) hang(HangAt.CONNECT) else sink(DriverSignal.Ready)
        }

        override suspend fun sendAudio(frame: AudioFrame) {
            effects += "audio:${frame.sequence}"
            if (hangAt == HangAt.AUDIO) hang(HangAt.AUDIO)
        }

        override suspend fun requestStop() {
            effects += "stop"
            if (hangAt == HangAt.STOP) hang(HangAt.STOP)
        }

        override suspend fun abort() {
            check(aborts.incrementAndGet() == 1)
            effects += "abort"
        }

        override suspend fun release() {
            check(releases.incrementAndGet() == 1)
            effects += "release"
        }

        private suspend fun hang(at: HangAt) {
            started.complete(at)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled.complete(at)
            }
        }
    }
}
