package com.zeropointsix.dobaosay.asr

import com.zeropointsix.dobaosay.asr.testing.FakeAsrDriver
import com.zeropointsix.dobaosay.asr.testing.FakeDriverStep
import com.zeropointsix.dobaosay.asr.testing.FakeDriverTrigger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeAsrDriverTest {
    @Test
    fun `script emits delayed partial and final`() =
        runTest {
            val driver =
                FakeAsrDriver(
                    mapOf(
                        FakeDriverTrigger.CONNECT to listOf(FakeDriverStep.Emit(DriverSignal.Ready)),
                        FakeDriverTrigger.AUDIO to
                            listOf(
                                FakeDriverStep.Delay(1.seconds),
                                FakeDriverStep.Emit(DriverSignal.Partial("utterance", "部分文本", 1)),
                            ),
                        FakeDriverTrigger.STOP to
                            listOf(
                                FakeDriverStep.Emit(DriverSignal.Final("result", "utterance", "最终文本")),
                            ),
                    ),
                )
            val session =
                DefaultAsrSession(
                    AsrSessionConfig(sessionTimeout = 30.seconds),
                    driver,
                    scope = backgroundScope,
                    clockMs = { testScheduler.currentTime },
                )

            assertEquals(AsrCommandResult.Accepted, session.start())
            runCurrent()
            val push = async { session.pushAudio(AudioFrame(0, 0, ByteArray(640))) }
            runCurrent()
            advanceTimeBy(1.seconds)
            runCurrent()

            assertEquals(AsrCommandResult.Accepted, push.await())
            assertEquals("部分文本", session.snapshot.value.partialText)
            assertEquals(AsrCommandResult.Accepted, session.stop())
            runCurrent()

            val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
            assertEquals(SessionOutcome.Succeeded("result", "最终文本"), outcome)
            assertEquals(listOf("connect", "push:0", "stop", "release"), driver.effects.toList())
        }

    @Test
    fun `scripted audio error becomes deterministic session failure`() =
        runTest {
            val driver =
                FakeAsrDriver(
                    mapOf(
                        FakeDriverTrigger.CONNECT to listOf(FakeDriverStep.Emit(DriverSignal.Ready)),
                        FakeDriverTrigger.AUDIO to listOf(FakeDriverStep.Throw()),
                    ),
                )
            val session =
                DefaultAsrSession(
                    AsrSessionConfig(),
                    driver,
                    scope = backgroundScope,
                    clockMs = { testScheduler.currentTime },
                )

            session.start()
            runCurrent()
            assertEquals(AsrCommandResult.Accepted, session.pushAudio(AudioFrame(0, 0, ByteArray(640))))
            runCurrent()

            val outcome = (session.snapshot.value.state as AsrSessionState.Closed).outcome
            val failure = assertIs<SessionOutcome.Failed>(outcome).failure
            assertEquals(AsrFailure.Internal("driver_send_audio"), failure)
            assertEquals(1, driver.releaseCount)
        }
}
