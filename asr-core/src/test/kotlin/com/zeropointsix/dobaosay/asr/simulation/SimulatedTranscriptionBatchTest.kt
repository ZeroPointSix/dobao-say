package com.zeropointsix.dobaosay.asr.simulation

import com.zeropointsix.dobaosay.asr.AsrCommandResult
import com.zeropointsix.dobaosay.asr.AsrEvent
import com.zeropointsix.dobaosay.asr.AsrFailure
import com.zeropointsix.dobaosay.asr.AsrSessionConfig
import com.zeropointsix.dobaosay.asr.AsrSessionState
import com.zeropointsix.dobaosay.asr.CancelReason
import com.zeropointsix.dobaosay.asr.DefaultAsrSession
import com.zeropointsix.dobaosay.asr.SessionOutcome
import com.zeropointsix.dobaosay.asr.StopReason
import com.zeropointsix.dobaosay.asr.TimeoutPhase
import com.zeropointsix.dobaosay.audio.InMemoryLoopbackTransport
import com.zeropointsix.dobaosay.audio.LoopbackSendResult
import com.zeropointsix.dobaosay.audio.LoopbackStats
import com.zeropointsix.dobaosay.audio.Pcm16Framer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedTranscriptionBatchTest {
    @Test
    fun `fixed virtual-time batch produces eight success three failed and one cancelled`() =
        runTest(timeout = 30.seconds) {
            val results = scenarios.map { runScenario(it) }

            assertEquals(12, results.size)
            assertEquals(8, results.count { it.outcome == "success" })
            assertEquals(3, results.count { it.outcome == "failed" })
            assertEquals(1, results.count { it.outcome == "cancelled" })
            writeReport(results)
        }

    @Test
    fun `Dispatchers Default smoke closes without leaked work`() =
        runBlocking {
            withTimeout(10.seconds) {
                val scenarioJob = SupervisorJob(coroutineContext[Job])
                val scenarioScope = CoroutineScope(scenarioJob + Dispatchers.Default)
                val driver = SimulatedTranscriptionDriver(SimulatedDriverMode.SUCCESS, "real-smoke")
                val session = DefaultAsrSession(AsrSessionConfig(), driver, scenarioScope)
                val events = CopyOnWriteArrayList<AsrEvent>()
                val collector =
                    scenarioScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        session.events.collect { events += it }
                    }
                val frames = framedPcm(frameCount = 8)
                val transport = InMemoryLoopbackTransport(capacity = 2, deliveryDelay = 2.milliseconds)

                assertEquals(AsrCommandResult.Accepted, session.start())
                session.snapshot.first { it.state == AsrSessionState.Ready }
                val sender =
                    scenarioScope.async {
                        frames.forEach { assertEquals(LoopbackSendResult.Accepted, transport.send(it)) }
                    }
                repeat(frames.size) {
                    assertEquals(AsrCommandResult.Accepted, session.pushAudio(checkNotNull(transport.receive())))
                }
                sender.await()
                awaitCondition { events.any { it is AsrEvent.Partial } }
                assertEquals(AsrCommandResult.Accepted, session.stop(StopReason.MANUAL))
                session.snapshot.first { it.state is AsrSessionState.Closed }
                driver.released.await()
                transport.close()
                collector.cancelAndJoin()
                awaitCondition { scenarioJob.children.none() }

                assertIs<SessionOutcome.Succeeded>((session.snapshot.value.state as AsrSessionState.Closed).outcome)
                assertEquals(1, events.count { it is AsrEvent.Final })
                assertEquals(1, events.count { it is AsrEvent.Closed })
                assertEquals(1, driver.releaseCount)
                assertEquals(0, driver.activeCallCount)
                assertConserved(transport.stats.value)
                scenarioJob.cancelAndJoin()
            }
        }

    private suspend fun TestScope.runScenario(spec: ScenarioSpec): ScenarioResult {
        val startedAt = testScheduler.currentTime
        val scenarioJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scenarioScope = CoroutineScope(backgroundScope.coroutineContext + scenarioJob)
        val driver = SimulatedTranscriptionDriver(spec.mode, spec.id)
        val config =
            AsrSessionConfig(
                connectTimeout = 1.seconds,
                stopFinalTimeout = 1.seconds,
                sessionTimeout = 60.seconds,
            )
        val session = DefaultAsrSession(config, driver, scenarioScope, clockMs = { testScheduler.currentTime })
        val events = CopyOnWriteArrayList<AsrEvent>()
        val collector =
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { events += it }
            }
        val frames = framedPcm(spec.frameCount)
        val transport = InMemoryLoopbackTransport(capacity = 2)

        assertEquals(AsrCommandResult.Accepted, session.start())
        runCurrent()
        if (spec.mode == SimulatedDriverMode.CONNECT_TIMEOUT) {
            val sender =
                backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                    frames.forEach { frame ->
                        if (transport.send(frame) == LoopbackSendResult.Closed) return@async
                    }
                }
            advanceTimeBy(1.seconds)
            runCurrent()
            transport.close()
            sender.await()
        } else {
            session.snapshot.first { it.state == AsrSessionState.Ready }
            runAudioPipeline(spec, session, driver, transport, frames)
            finishScenario(spec, session, driver, events)
        }

        session.snapshot.first { it.state is AsrSessionState.Closed }
        runCurrent()
        driver.released.await()
        runCurrent()
        transport.close()
        collector.cancelAndJoin()
        runCurrent()
        assertTrue(scenarioJob.children.none(), "${spec.id}: residual session child")

        val state = assertIs<AsrSessionState.Closed>(session.snapshot.value.state)
        assertScenario(spec, state.outcome, events, driver, transport.stats.value)
        val result =
            ScenarioResult(
                id = spec.id,
                outcome = state.outcome.reportName(),
                failureCode = (state.outcome as? SessionOutcome.Failed)?.failure?.code,
                inputFrames = frames.size,
                driverFrames = driver.receivedFrames.size,
                eventCount = events.size,
                elapsedMs = testScheduler.currentTime - startedAt,
                releaseCount = driver.releaseCount,
                abortCount = driver.abortCount,
                loopback = transport.stats.value,
            )
        scenarioJob.cancelAndJoin()
        return result
    }

    private suspend fun TestScope.runAudioPipeline(
        spec: ScenarioSpec,
        session: DefaultAsrSession,
        driver: SimulatedTranscriptionDriver,
        transport: InMemoryLoopbackTransport,
        frames: List<com.zeropointsix.dobaosay.asr.AudioFrame>,
    ) {
        val sender =
            backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
                frames.forEach { frame ->
                    if (transport.send(frame) == LoopbackSendResult.Closed) return@async
                }
            }
        var consumed = 0
        while (consumed < frames.size && session.snapshot.value.state !is AsrSessionState.Closed) {
            if (spec.consumerDelay.isPositive()) delay(spec.consumerDelay)
            val frame = transport.receive() ?: break
            val command = session.pushAudio(frame)
            if (command !is AsrCommandResult.Accepted) break
            consumed += 1
            runCurrent()
            if (spec.cancelAfterFrames == consumed) {
                assertEquals(AsrCommandResult.Accepted, session.cancel(CancelReason.USER))
                runCurrent()
            }
        }
        if (session.snapshot.value.state is AsrSessionState.Closed) transport.cancel()
        sender.await()
        transport.stop()
        if (spec.mode != SimulatedDriverMode.DRIVER_FAILED) {
            assertEquals(frames.size, consumed, spec.id)
        }
        if (consumed > 0) {
            runCurrent()
            assertTrue(driver.receivedFrames.isNotEmpty(), "${spec.id}: Driver received no frame")
        }
    }

    private suspend fun TestScope.finishScenario(
        spec: ScenarioSpec,
        session: DefaultAsrSession,
        driver: SimulatedTranscriptionDriver,
        events: List<AsrEvent>,
    ) {
        if (spec.mode == SimulatedDriverMode.DRIVER_FAILED || spec.cancelAfterFrames != null) return
        runCurrent()
        assertTrue(events.any { it is AsrEvent.Partial }, "${spec.id}: partial missing")
        if (spec.vad) {
            driver.emitSpeechEnded()
        } else {
            assertEquals(AsrCommandResult.Accepted, session.stop(StopReason.MANUAL))
        }
        runCurrent()
        if (spec.mode == SimulatedDriverMode.FINAL_TIMEOUT) {
            advanceTimeBy(1.seconds)
            runCurrent()
        }
    }

    private fun assertScenario(
        spec: ScenarioSpec,
        outcome: SessionOutcome,
        events: List<AsrEvent>,
        driver: SimulatedTranscriptionDriver,
        stats: LoopbackStats,
    ) {
        assertTrue(events.zipWithNext().all { (left, right) -> left.sequence < right.sequence }, "${spec.id}: sequence")
        assertEquals(1, events.count { it is AsrEvent.Closed }, "${spec.id}: Closed count")
        assertEquals(1, driver.releaseCount, "${spec.id}: release count")
        assertEquals(0, driver.activeCallCount, "${spec.id}: active Driver call")
        assertEquals(0, stats.activeSenders, "${spec.id}: active sender")
        assertEquals(0, stats.queuedFrames, "${spec.id}: queued frame")
        assertEquals(0, stats.inDeliveryFrames, "${spec.id}: in-delivery frame")
        assertTrue(stats.maxQueuedFrames <= stats.capacity, "${spec.id}: capacity exceeded")
        assertConserved(stats)

        if (spec.cancelAfterFrames != null) {
            assertEquals(SessionOutcome.Cancelled(CancelReason.USER), outcome)
            assertEquals(1, driver.abortCount)
        } else {
            when (spec.mode) {
                SimulatedDriverMode.SUCCESS -> {
                    assertIs<SessionOutcome.Succeeded>(outcome)
                    assertTrue(events.any { it is AsrEvent.Partial }, "${spec.id}: partial missing")
                    assertEquals(1, events.count { it is AsrEvent.Final }, "${spec.id}: Final count")
                    if (spec.vad) {
                        assertEquals(1, events.count { it is AsrEvent.SpeechEnded }, "${spec.id}: VAD")
                    }
                    assertEquals(0, driver.abortCount)
                }

                SimulatedDriverMode.CONNECT_TIMEOUT -> {
                    assertEquals(SessionOutcome.Failed(AsrFailure.Timeout(TimeoutPhase.CONNECT)), outcome)
                    assertEquals(1, driver.abortCount)
                }

                SimulatedDriverMode.FINAL_TIMEOUT -> {
                    assertEquals(SessionOutcome.Failed(AsrFailure.Timeout(TimeoutPhase.FINAL)), outcome)
                    assertTrue(events.any { it is AsrEvent.Partial }, "${spec.id}: partial missing")
                    assertEquals(1, driver.abortCount)
                }

                SimulatedDriverMode.DRIVER_FAILED -> {
                    assertEquals(SessionOutcome.Failed(AsrFailure.NetworkUnavailable), outcome)
                    assertEquals(0, driver.abortCount)
                }
            }
        }
    }

    private fun framedPcm(frameCount: Int): List<com.zeropointsix.dobaosay.asr.AudioFrame> {
        val framer = Pcm16Framer()
        val bytes =
            ByteArray(Math.multiplyExact(frameCount, framer.format.bytesPerFrame)) { index ->
                (index % 251).toByte()
            }
        return framer.push(bytes) + framer.finish()
    }

    private fun writeReport(results: List<ScenarioResult>) {
        val report = Path.of("build", "reports", "simulated-transcription", "batch.json")
        Files.createDirectories(report.parent)
        Files.writeString(report, results.toJson())
        assertTrue(Files.isRegularFile(report))
    }

    private fun List<ScenarioResult>.toJson(): String =
        buildString {
            append("{\n  \"schemaVersion\": 1,\n  \"simulated\": true,\n")
            append("  \"summary\": {\"total\": 12, \"success\": 8, \"failed\": 3, \"cancelled\": 1},\n")
            append("  \"scenarios\": [\n")
            forEachIndexed { index, result ->
                append(result.toJsonObject())
                if (index != lastIndex) append(',')
                append('\n')
            }
            append("  ]\n}\n")
        }

    private fun ScenarioResult.toJsonObject(): String =
        "    {\"id\": \"$id\", \"outcome\": \"$outcome\", \"failureCode\": " +
            (failureCode?.let { "\"$it\"" } ?: "null") +
            ", \"inputFrames\": $inputFrames, \"driverFrames\": $driverFrames, " +
            "\"eventCount\": $eventCount, \"elapsedMs\": $elapsedMs, " +
            "\"cleanup\": {\"releaseCount\": $releaseCount, \"abortCount\": $abortCount, " +
            "\"activeSenders\": ${loopback.activeSenders}, \"queuedFrames\": ${loopback.queuedFrames}, " +
            "\"inDeliveryFrames\": ${loopback.inDeliveryFrames}}, " +
            "\"loopback\": {\"capacity\": ${loopback.capacity}, \"maxQueuedFrames\": ${loopback.maxQueuedFrames}, " +
            "\"acceptedFrames\": ${loopback.acceptedFrames}, \"receivedFrames\": ${loopback.receivedFrames}, " +
            "\"droppedOnCloseFrames\": ${loopback.droppedOnCloseFrames}, " +
            "\"droppedOnCancelFrames\": ${loopback.droppedOnCancelFrames}}}"

    private fun SessionOutcome.reportName(): String =
        when (this) {
            is SessionOutcome.Succeeded -> "success"
            is SessionOutcome.Failed -> "failed"
            is SessionOutcome.Cancelled -> "cancelled"
            SessionOutcome.ClosedWithoutResult -> "closed_without_result"
        }

    private fun assertConserved(stats: LoopbackStats) {
        assertEquals(
            stats.acceptedFrames,
            stats.queuedFrames.toLong() +
                stats.inDeliveryFrames +
                stats.receivedFrames +
                stats.droppedOnCloseFrames +
                stats.droppedOnCancelFrames,
        )
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        while (!predicate()) yield()
    }

    private data class ScenarioSpec(
        val id: String,
        val frameCount: Int,
        val vad: Boolean = false,
        val mode: SimulatedDriverMode = SimulatedDriverMode.SUCCESS,
        val cancelAfterFrames: Int? = null,
        val consumerDelay: Duration = Duration.ZERO,
    )

    private data class ScenarioResult(
        val id: String,
        val outcome: String,
        val failureCode: String?,
        val inputFrames: Int,
        val driverFrames: Int,
        val eventCount: Int,
        val elapsedMs: Long,
        val releaseCount: Int,
        val abortCount: Int,
        val loopback: LoopbackStats,
    )

    private companion object {
        val scenarios =
            listOf(
                ScenarioSpec("manual-short-1", 4),
                ScenarioSpec("manual-short-2", 5),
                ScenarioSpec("manual-short-3", 6),
                ScenarioSpec("vad-short-1", 4, vad = true),
                ScenarioSpec("vad-short-2", 5, vad = true),
                ScenarioSpec("vad-short-3", 6, vad = true),
                ScenarioSpec("manual-slow-8s", 400, consumerDelay = 2.milliseconds),
                ScenarioSpec("vad-slow-12s", 600, vad = true, consumerDelay = 2.milliseconds),
                ScenarioSpec("connect-timeout", 4, mode = SimulatedDriverMode.CONNECT_TIMEOUT),
                ScenarioSpec("final-timeout-after-partial", 4, mode = SimulatedDriverMode.FINAL_TIMEOUT),
                ScenarioSpec("driver-failed", 4, mode = SimulatedDriverMode.DRIVER_FAILED),
                ScenarioSpec("user-cancelled", 4, cancelAfterFrames = 2),
            )
    }
}
