package com.zeropointsix.dobaosay.asr

import com.zeropointsix.dobaosay.asr.testing.FakeAsrDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAsrSessionTimeoutTest {
    @Test
    fun `connect timeout closes and releases once`() = runTest {
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
    fun `manual stop final timeout closes and releases once`() = runTest {
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
    fun `VAD final timeout closes and releases once`() = runTest {
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
    fun `session timeout applies after ready`() = runTest {
        val driver = FakeAsrDriver()
        val session = readySession(driver, sessionSeconds = 2)

        advanceTimeBy(2.seconds)
        runCurrent()

        assertTimeout(session, TimeoutPhase.SESSION)
        assertEquals(1, driver.releaseCount)
    }

    @Test
    fun `final before deadline is never reversed by stale timeout`() = runTest {
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
    fun `timeout and final at same deadline commit one terminal outcome`() = runTest {
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

    private fun TestScope.session(
        driver: FakeAsrDriver,
        connectSeconds: Int = 5,
        finalSeconds: Int = 5,
        sessionSeconds: Int = 30,
    ): DefaultAsrSession = DefaultAsrSession(
        config = AsrSessionConfig(
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
        driver.emit(DriverSignal.Ready)
        runCurrent()
        assertEquals(AsrSessionState.Ready, session.snapshot.value.state)
        return session
    }

    private fun assertTimeout(session: DefaultAsrSession, phase: TimeoutPhase) {
        val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
        assertEquals(SessionOutcome.Failed(AsrFailure.Timeout(phase)), outcome)
    }
}
