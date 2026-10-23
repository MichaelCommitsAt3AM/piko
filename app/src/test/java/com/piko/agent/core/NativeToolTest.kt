package com.piko.agent.core

import com.piko.agent.tools.ToolCatalog
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeToolTest {
    @Test fun plansExactNativeMultiStepRequestsWithoutModelCalls() {
        val plan = com.piko.agent.tools.NativeCommandPlanner.plan("Turn on my flashlight, set volume to 30%, and open YouTube.")!!
        assertEquals(listOf("flashlight", "media_volume", null), plan.map { it.toolId })
        assertEquals("YouTube", plan.last().appName)
        assertEquals(30, plan[1].parameters["percent"]!!.jsonPrimitive.int)
    }
    @Test fun anyAmbiguousClauseDelegatesTheEntireRequestToTheAgent() {
        assertNull(com.piko.agent.tools.NativeCommandPlanner.plan("Open Spotify and play my liked songs"))
        assertNull(com.piko.agent.tools.NativeCommandPlanner.plan("Open my preferred browser"))
        assertNull(com.piko.agent.tools.NativeCommandPlanner.plan("Set volume to 140%"))
        assertNull(com.piko.agent.tools.NativeCommandPlanner.plan("Send a message to John"))
    }
    @Test fun rejectsUnknownToolsAndUnexpectedArguments() {
        assertTrue(ToolCatalog.validate("shell", buildJsonObject { put("command", "id") }).isNotEmpty())
        assertTrue(ToolCatalog.validate("wifi_settings", buildJsonObject { put("component", "evil") }).isNotEmpty())
    }
    @Test fun validatesTypesAndRangesWithoutCoercion() {
        assertTrue(ToolCatalog.validate("media_volume", buildJsonObject { put("percent", 101) }).isNotEmpty())
        assertTrue(ToolCatalog.validate("media_volume", buildJsonObject { put("percent", "30") }).isNotEmpty())
        assertTrue(ToolCatalog.validate("media_volume", buildJsonObject { put("percent", 30) }).isEmpty())
        assertTrue(ToolCatalog.validate("flashlight", buildJsonObject { put("enabled", true) }).isEmpty())
        assertTrue(ToolCatalog.validate("flashlight", buildJsonObject { put("enabled", "true") }).isNotEmpty())
    }
    @Test fun rejectsExecutableLinksAndInvalidPhoneNumbers() {
        for (url in listOf("javascript:alert(1)", "intent://attack", "file:///secret")) {
            assertTrue(ToolCatalog.validate("website", buildJsonObject { put("url", url) }).isNotEmpty())
        }
        assertTrue(ToolCatalog.validate("website", buildJsonObject { put("url", "https://example.com/search?q=kotlin") }).isEmpty())
        assertTrue(ToolCatalog.validate("dialer", buildJsonObject { put("number", "*#06#") }).isNotEmpty())
    }
    @Test fun requiresReviewForSharingAndDrafts() {
        val action = AgentAction(type = ActionType.NATIVE_TOOL, toolId = "sms", parameters = buildJsonObject { put("number", "123"); put("text", "Hi") })
        assertTrue(SensitiveActionClassifier.classify(SensitiveActionClassifier.Context(action)).requiresConfirmation)
        assertTrue(ActionValidator.validate(action, ScreenBounds(100, 100)) is ActionValidator.ValidationResult.Valid)
    }
    @Test fun parsesTheSameProtocolForNativeAndJsonModels() {
        val parsed = ActionParser.parse("""{"type":"native_tool","toolId":"media_volume","parameters":{"percent":30}}""") as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.NATIVE_TOOL, parsed.action.type)
        assertTrue(ToolCatalog.validate(parsed.action.toolId, parsed.action.parameters).isEmpty())
    }
}
