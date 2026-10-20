package com.piko.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke test. Included for a real-device run; it was not executed in
 * the sandbox because no emulator image or physical device was available.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeShowsBrandingAndGoalComposer() {
        composeRule.onNodeWithText("An AI agent that operates your phone").assertIsDisplayed()
        composeRule.onNodeWithText("What should I do?").assertIsDisplayed()
        composeRule.onNodeWithText("Start task").assertIsDisplayed()
    }
}
