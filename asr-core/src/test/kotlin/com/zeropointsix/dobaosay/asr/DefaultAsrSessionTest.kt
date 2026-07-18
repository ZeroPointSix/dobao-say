package com.zeropointsix.dobaosay.asr

import com.zeropointsix.dobaosay.asr.testing.FakeAsrDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultAsrSessionTest {
    @Test
    fun `manual stop emits one final and releases once`() = runTest {
        val driver = FakeAsrDriver()
        val session = DefaultAsrSession(AsrSessionConfig(), driver, clockMs = { testScheduler.currentTime })
        val events = async(start = CoroutineStart.UNDISPATCHED) { session.events.take(4).toList() }

        assertEquals(AsrCommandResult.Accepted, session.start())
        driver.emit(DriverSignal.Ready)
        assertEquals(AsrCommandResult.Accepted, session.pushAudio(frame(0)))
        assertEquals(AsrCommandResult.Accepted, session.stop())
        driver.emit(DriverSignal.Final("result-1", "utterance-1", "测试文本"))
        driver.emit(DriverSignal.Final("result-1", "utterance-1", "重复文本"))

        assertIs<SessionOutcome.Succeeded>((session.snapshot.value.state as AsrSessionState.Closed).outcome)
        assertEquals(1, driver.stopCount)
        assertEquals(1, driver.releaseCount)
        assertEquals(1, events.await().filterIsInstance<AsrEvent.Final>().size)
    }

    @Test
    fun `stop and close are idempotent`() = runTest {
        val driver = FakeAsrDriver()
        val session = DefaultAsrSession(AsrSessionConfig(), driver)

        session.start()
        driver.emit(DriverSignal.Ready)
        session.stop()
        assertEquals(AsrCommandResult.IgnoredAlreadyHandled, session.stop())
        session.close()
        assertEquals(AsrCommandResult.IgnoredAlreadyHandled, session.close())

        assertEquals(1, driver.stopCount)
        assertEquals(1, driver.releaseCount)
    }

    @Test
    fun `invalid audio is rejected before driver`() = runTest {
        val driver = FakeAsrDriver()
        val session = DefaultAsrSession(AsrSessionConfig(), driver)

        session.start()
        driver.emit(DriverSignal.Ready)
        val result = session.pushAudio(AudioFrame(0, 0, ByteArray(639)))

        assertIs<AsrCommandResult.Rejected>(result)
        assertIs<AsrFailure.InvalidAudio>(result.failure)
        assertEquals(0, driver.receivedFrames.size)
    }

    @Test
    fun `cancel never produces a success record`() = runTest {
        val driver = FakeAsrDriver()
        val session = DefaultAsrSession(AsrSessionConfig(), driver)

        session.start()
        driver.emit(DriverSignal.Ready)
        session.cancel(CancelReason.APP_BACKGROUNDED)

        val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
        assertIs<SessionOutcome.Cancelled>(outcome)
        assertEquals(null, session.snapshot.value.finalResult)
        assertEquals(1, driver.abortCount)
        assertEquals(1, driver.releaseCount)
    }

    private fun frame(sequence: Long): AudioFrame = AudioFrame(sequence, sequence * 20, ByteArray(640))
}
