package com.piko.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionParserTest {
    @Test
    fun `parses wrapped action with reasoning`() {
        val raw = """
            {
              "reasoning": "The Display button is visible.",
              "action": {"version": 1, "type": "tap", "elementId": "element_12"}
            }
        """.trimIndent()
        val result = ActionParser.parse(raw)
        assertTrue(result is ActionParser.ActionParseResult.Success)
        val success = result as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.TAP, success.action.type)
        assertEquals("element_12", success.action.elementId)
        assertEquals("The Display button is visible.", success.reasoning)
    }

    @Test
    fun `parses fenced JSON inside prose`() {
        val raw = """
            I will open Settings.
            ```json
            {"action":{"type":"launch_app","packageName":"com.android.settings"}}
            ```
        """.trimIndent()
        val result = ActionParser.parse(raw) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.LAUNCH_APP, result.action.type)
        assertEquals("com.android.settings", result.action.packageName)
    }

    @Test
    fun `parses direct action root`() {
        val result = ActionParser.parse("""{"type":"back"}""") as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.BACK, result.action.type)
    }

    @Test
    fun `parses tool arguments string nesting`() {
        val raw = """{"function":{"arguments":"{\"type\":\"wait\",\"milliseconds\":500}"}}"""
        val result = ActionParser.parse(raw) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.WAIT, result.action.type)
        assertEquals(500L, result.action.milliseconds)
    }

    @Test
    fun `parses pending confirmation action`() {
        val raw = """
            {"reasoning":"Needs approval","action":{"type":"request_confirmation","summary":"Send?",
            "pendingAction":{"type":"tap","elementId":"element_8"}}}
        """.trimIndent()
        val result = ActionParser.parse(raw) as ActionParser.ActionParseResult.Success
        assertEquals(ActionType.REQUEST_CONFIRMATION, result.action.type)
        assertEquals(ActionType.TAP, result.action.pendingAction?.type)
    }

    @Test
    fun `ignores braces inside strings`() {
        val raw = """{"action":{"type":"type_text","text":"a { brace }"}}"""
        val result = ActionParser.parse("prefix $raw suffix") as ActionParser.ActionParseResult.Success
        assertEquals("a { brace }", result.action.text)
    }

    @Test
    fun `reports invalid output`() {
        assertTrue(ActionParser.parse("I cannot do that.") is ActionParser.ActionParseResult.Failure)
        assertTrue(ActionParser.parse("") is ActionParser.ActionParseResult.Failure)
        assertTrue(
            ActionParser.parse("""{"action":{"type":"fly_to_moon"}}""") is ActionParser.ActionParseResult.Failure,
        )
    }

    @Test
    fun `extracts balanced objects correctly`() {
        val objects = ActionParser.extractBalancedObjects("""x {"a":"}"} y {"b":1}""")
        assertEquals(2, objects.size)
        assertEquals("""{"a":"}"}""", objects[0])
    }

    @Test
    fun `missing reasoning is allowed`() {
        val result = ActionParser.parse("""{"action":{"type":"home"}}""") as ActionParser.ActionParseResult.Success
        assertNull(result.reasoning)
    }
}
