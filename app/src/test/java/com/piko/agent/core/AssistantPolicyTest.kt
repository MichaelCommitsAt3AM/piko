package com.piko.agent.core

import com.piko.agent.tools.*
import com.piko.agent.voice.VoiceSessionGate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantPolicyTest {
    @Test fun screenQuestionsRejectEveryDeviceAndMemoryAction() {
        ActionType.entries.forEach { type ->
            assertEquals(type in setOf(ActionType.FINISH, ActionType.FAIL, ActionType.OBSERVE), AssistantExecutionPolicy.permits(true, AgentAction(type = type)))
            assertTrue(AssistantExecutionPolicy.permits(false, AgentAction(type = type)))
        }
    }
    @Test fun cancelledAndReplacedSpeechCallbacksCannotChangeState() {
        val gate = VoiceSessionGate()
        val old = gate.begin()
        assertTrue(gate.move(old, VoiceSessionGate.State.LISTENING))
        gate.cancel()
        assertFalse(gate.move(old, VoiceSessionGate.State.COMPLETE))
        val current = gate.begin()
        assertFalse(gate.move(old, VoiceSessionGate.State.ERROR))
        assertTrue(gate.move(current, VoiceSessionGate.State.TRANSCRIBING))
        assertTrue(gate.move(current, VoiceSessionGate.State.COMPLETE))
        assertFalse(gate.isCurrent(current))
    }
    @Test fun resolutionChecksPermissionsAndDistinguishesIntentsFromStateSetters() {
        val params = buildJsonObject { put("enabled", true) }
        assertTrue(CapabilityResolver.resolve("flashlight", params) { "Permission denied" } is CapabilityResolution.Unavailable)
        val native = CapabilityResolver.resolve("flashlight", params) { null } as CapabilityResolution.Available
        assertEquals("native_api", native.method)
        val intent = CapabilityResolver.resolve("wifi_settings", buildJsonObject {}) { null } as CapabilityResolution.Available
        assertEquals("supported_intent", intent.method)
    }
    @Test fun acceptedOperationsCannotBlindlyFallBackAndReplay() {
        assertFalse(CapabilityResolver.mayFallback(true, false))
        assertTrue(CapabilityResolver.mayFallback(false, false))
        assertTrue(CapabilityResolver.mayFallback(true, true))
    }
    @Test fun differentNativeStateSettersAreNotMistakenForRepeatedActionLoops() {
        val detector = LoopDetector()
        repeat(5) { index ->
            val action = AgentAction(type = ActionType.NATIVE_TOOL, toolId = "media_volume", parameters = buildJsonObject { put("percent", index * 10) })
            assertFalse(detector.record("same-screen", action) is LoopDetector.LoopSignal.Abort)
        }
    }
}
