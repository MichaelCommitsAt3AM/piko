package com.piko.agent.core

import com.piko.agent.workflow.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WorkflowTest {
    @Test fun workflowVariablesAreDataAndRevalidatedAtExecution() {
        val template = listOf(WorkflowStep(kind = "tool", toolId = "media_volume", parameters = buildJsonObject { put("percent", "{{volume}}") }), WorkflowStep(instruction = "Open {{app}}"))
        assertEquals(setOf("volume", "app"), WorkflowVariables.names(template))
        assertTrue(WorkflowVariables.templateErrors(template).isEmpty())
        val valid = WorkflowVariables.instantiate(template, mapOf("volume" to "30", "app" to "Spotify"))
        assertTrue(WorkflowPolicy.validate(valid).isEmpty())
        val invalid = WorkflowVariables.instantiate(template, mapOf("volume" to "130", "app" to "Spotify"))
        assertTrue(WorkflowPolicy.validate(invalid).isNotEmpty())
        assertTrue(runCatching { WorkflowVariables.instantiate(template, emptyMap()) }.isFailure)
    }
    @Test fun structuredStepsRoundTripAndRejectCoordinates() {
        val steps = listOf(WorkflowStep(kind = "tool", toolId = "media_volume", parameters = buildJsonObject { put("percent", 30) }), WorkflowStep(kind = "wait"))
        val json = PikoJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(WorkflowStep.serializer()), steps)
        assertEquals(steps, PikoJson.decodeFromString<List<WorkflowStep>>(json))
        assertTrue(WorkflowPolicy.validate(steps).isEmpty())
        assertTrue(WorkflowPolicy.validate(listOf(WorkflowStep(kind = "tap"))).isNotEmpty())
    }
    @Test fun stepsRunInOrderAndStopOnFailure() = runBlocking {
        val order = mutableListOf<Int>()
        val result = WorkflowEngine().run(List(3) { WorkflowStep(instruction = "Step $it") }, { "app" }) { index, _ -> order += index; index == 0 }
        assertFalse(result)
        assertEquals(listOf(0, 1), order)
    }
    @Test fun conditionalBranchSkipsOnlyForward() = runBlocking {
        val order = mutableListOf<Int>()
        val steps = listOf(WorkflowStep(kind = "condition", foregroundPackage = "expected", skipIfFalse = 1), WorkflowStep(instruction = "Skipped"), WorkflowStep(instruction = "Run"))
        assertTrue(WorkflowEngine().run(steps, { "different" }) { index, _ -> order += index; true })
        assertEquals(listOf(2), order)
    }
    @Test fun cancellationStopsBeforeAnotherStep() = runBlocking {
        var executed = 0
        val job = launch {
            WorkflowEngine().run(List(3) { WorkflowStep(instruction = "Step") }, { null }) { _, _ -> executed++; currentCoroutineContext().cancel(); true }
        }
        job.join()
        assertEquals(1, executed)
        assertTrue(job.isCancelled)
    }
    @Test fun retriesAreBoundedAndForbiddenForNonIdempotentActions() = runBlocking {
        var attempts = 0
        assertTrue(WorkflowEngine().run(listOf(WorkflowStep(kind = "wait", maxAttempts = 3)), { null }) { _, _ -> ++attempts == 2 })
        assertEquals(2, attempts)
        assertTrue(WorkflowPolicy.validate(listOf(WorkflowStep(kind = "ai", instruction = "Send a message", maxAttempts = 2))).isNotEmpty())
    }
    @Test fun schedulesUseDeviceZoneAndAdvanceDailyWeeklyAndPastTimes() {
        val zone = ZoneId.of("Africa/Nairobi")
        val after = Instant.parse("2026-10-21T06:00:00Z")
        assertEquals("2026-10-22T06:00:00Z", Instant.ofEpochMilli(ScheduleCalculator.next(9, 0, "daily", 1, after, zone)).toString())
        assertEquals(DayOfWeek.MONDAY, Instant.ofEpochMilli(ScheduleCalculator.next(9, 0, "weekly", 1, after, zone)).atZone(zone).dayOfWeek)
        assertTrue(ScheduleCalculator.next(10, 0, "once", 1, after, zone) > after.toEpochMilli())
    }
    @Test fun daylightSavingGapMovesToAValidFutureTime() {
        val zone = ZoneId.of("America/New_York")
        val next = ScheduleCalculator.next(2, 30, "daily", 1, Instant.parse("2026-03-08T05:00:00Z"), zone)
        assertEquals(3, Instant.ofEpochMilli(next).atZone(zone).hour)
    }
}
