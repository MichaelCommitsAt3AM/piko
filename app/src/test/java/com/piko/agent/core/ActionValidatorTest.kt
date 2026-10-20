package com.piko.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionValidatorTest {
    private val bounds = ScreenBounds(1080, 2400)

    private fun validate(action: AgentAction) = ActionValidator.validate(action, bounds)

    @Test
    fun `tap by element id is valid`() {
        val result = validate(AgentAction(type = ActionType.TAP, elementId = "element_12"))
        assertTrue(result is ActionValidator.ValidationResult.Valid)
    }

    @Test
    fun `tap by coordinates is valid inside bounds`() {
        val result = validate(AgentAction(type = ActionType.TAP, x = 540, y = 1200))
        assertTrue(result is ActionValidator.ValidationResult.Valid)
    }

    @Test
    fun `tap outside screen is rejected`() {
        val result = validate(AgentAction(type = ActionType.TAP, x = 1080, y = 1200))
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `tap with both element and coordinates is rejected`() {
        val result = validate(AgentAction(type = ActionType.TAP, elementId = "element_1", x = 1, y = 1))
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `malformed element id is rejected`() {
        val result = validate(AgentAction(type = ActionType.TAP, elementId = "button_submit"))
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `schema version must match`() {
        val result = validate(AgentAction(version = 2, type = ActionType.BACK))
        assertTrue(result is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `swipe requires duration within safety limit`() {
        val valid = validate(
            AgentAction(
                type = ActionType.SWIPE,
                startX = 500,
                startY = 1600,
                endX = 500,
                endY = 500,
                durationMs = 400,
            ),
        )
        val tooLong = validate(
            AgentAction(
                type = ActionType.SWIPE,
                startX = 500,
                startY = 1600,
                endX = 500,
                endY = 500,
                durationMs = 60_000,
            ),
        )
        assertTrue(valid is ActionValidator.ValidationResult.Valid)
        assertTrue(tooLong is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `empty and oversized text are rejected`() {
        assertTrue(validate(AgentAction(type = ActionType.TYPE_TEXT, text = "")) is ActionValidator.ValidationResult.Invalid)
        assertTrue(
            validate(AgentAction(type = ActionType.TYPE_TEXT, text = "a".repeat(4001))) is
                ActionValidator.ValidationResult.Invalid,
        )
    }

    @Test
    fun `scroll requires direction and bounded amount`() {
        assertTrue(validate(AgentAction(type = ActionType.SCROLL)) is ActionValidator.ValidationResult.Invalid)
        assertTrue(
            validate(AgentAction(type = ActionType.SCROLL, direction = ScrollDirection.DOWN, amount = 2)) is
                ActionValidator.ValidationResult.Valid,
        )
    }

    @Test
    fun `launch app requires valid package or name`() {
        assertTrue(validate(AgentAction(type = ActionType.LAUNCH_APP)) is ActionValidator.ValidationResult.Invalid)
        assertTrue(
            validate(AgentAction(type = ActionType.LAUNCH_APP, packageName = "not a package")) is
                ActionValidator.ValidationResult.Invalid,
        )
        assertTrue(
            validate(AgentAction(type = ActionType.LAUNCH_APP, packageName = "com.android.settings")) is
                ActionValidator.ValidationResult.Valid,
        )
    }

    @Test
    fun `confirmation with nested action validates recursively`() {
        val valid = AgentAction(
            type = ActionType.REQUEST_CONFIRMATION,
            summary = "Send this message?",
            pendingAction = AgentAction(type = ActionType.TAP, elementId = "element_4"),
        )
        val invalid = AgentAction(
            type = ActionType.REQUEST_CONFIRMATION,
            summary = "Finish?",
            pendingAction = AgentAction(type = ActionType.FINISH, summary = "Done"),
        )
        assertTrue(validate(valid) is ActionValidator.ValidationResult.Valid)
        assertTrue(validate(invalid) is ActionValidator.ValidationResult.Invalid)
    }

    @Test
    fun `finish and fail require a report`() {
        assertTrue(validate(AgentAction(type = ActionType.FINISH)) is ActionValidator.ValidationResult.Invalid)
        assertTrue(validate(AgentAction(type = ActionType.FAIL)) is ActionValidator.ValidationResult.Invalid)
        assertEquals(
            true,
            validate(AgentAction(type = ActionType.FINISH, summary = "Done", verified = true, verification = "Visible")) is
                ActionValidator.ValidationResult.Valid,
        )
    }
}
