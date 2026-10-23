package com.piko.agent

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only smoke checks: no provider requests, permission grants or personal-data mutations. */
@RunWith(AndroidJUnit4::class)
class AssistantScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun assistantExposesMemoryManagement() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Assistant").performClick()
        compose.onNodeWithText("Talk, type, or ask about your screen").assertIsDisplayed()
        compose.onNodeWithText("Memory", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Search memories").performScrollTo().assertIsDisplayed()
    }

    @Test fun assistantExposesWorkflowManagement() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Assistant").performClick()
        compose.onNodeWithText("Workflows", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Workflow name").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Structured steps").performScrollTo().assertIsDisplayed()
    }
}
