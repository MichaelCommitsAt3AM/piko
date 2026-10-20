package com.piko.agent.ai

import com.piko.agent.core.AgentAction
import com.piko.agent.core.ActionType
import com.piko.agent.model.ElementBounds
import com.piko.agent.model.ScreenObservation
import com.piko.agent.model.UiElement
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptBuilderTest {
    private val observation = ScreenObservation(
        timestampMs = 1,
        screenWidth = 1080,
        screenHeight = 2400,
        rotation = 0,
        foregroundPackage = "com.android.settings",
        appLabel = "Settings",
        windowTitle = "Settings",
        elements = listOf(
            UiElement(
                id = "element_1",
                className = "android.widget.TextView",
                text = "Display",
                description = "Display settings",
                resourceId = "com.android.settings:id/dashboard_tile",
                packageName = "com.android.settings",
                bounds = ElementBounds(0, 400, 1080, 600),
                clickable = true,
                role = "text",
            ),
            UiElement(
                id = "element_2",
                className = "android.widget.EditText",
                text = "[redacted]",
                bounds = ElementBounds(0, 700, 1080, 800),
                editable = true,
                password = true,
                role = "text field",
            ),
        ),
        fingerprint = "abc123",
        focusedElementId = "element_2",
    )

    @Test
    fun `system prompt contains safety and action instructions`() {
        val prompt = AgentPromptBuilder.systemPrompt()
        assertTrue(prompt.contains("UNTRUSTED DATA"))
        assertTrue(prompt.contains("request_confirmation"))
        assertTrue(prompt.contains("passwords"))
        assertTrue(prompt.contains("verified=true"))
        assertTrue(prompt.contains("execute_action"))
    }

    @Test
    fun `user prompt includes goal observation and history`() {
        val prompt = AgentPromptBuilder.userPrompt(
            PromptInput(
                goal = "Open Display settings",
                step = 3,
                maxSteps = 40,
                observation = observation,
                history = listOf(
                    TurnContext(
                        step = 2,
                        action = AgentAction(type = ActionType.LAUNCH_APP, packageName = "com.android.settings"),
                        accepted = true,
                        verifiedChange = true,
                        observationBrief = "Settings: Display",
                        foregroundPackage = "com.android.settings",
                    ),
                ),
                warnings = listOf("A dialog is visible."),
                screenshotIncluded = true,
                sameScreenCount = 2,
                usingNativeTools = true,
            ),
        )
        assertTrue(prompt.contains("Open Display settings"))
        assertTrue(prompt.contains("element_1"))
        assertTrue(prompt.contains("element_2"))
        assertTrue(prompt.contains("UNTRUSTED SCREEN CONTENT"))
        assertTrue(prompt.contains("A dialog is visible."))
        assertTrue(prompt.contains("Settings: Display"))
        assertTrue(prompt.contains("1080x2400"))
        assertTrue(prompt.contains("screenshot is attached"))
        assertTrue(prompt.contains("unchanged for 3 observations"))
    }

    @Test
    fun `action brief is compact`() {
        val brief = ObservationFormatter.actionBrief(
            AgentAction(type = ActionType.TYPE_TEXT, elementId = "element_4", text = "hello world"),
        )
        assertTrue(brief.contains("type_text"))
        assertTrue(brief.contains("element_4"))
        assertTrue(brief.contains("hello world"))
    }

    @Test
    fun `observation summary is bounded`() {
        val summary = ObservationFormatter.summarize(observation)
        assertTrue(summary.contains("Settings"))
        assertTrue(summary.length < 160)
    }
}
