package com.piko.agent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun homeShowsBrandingAndGoalComposer() {
        composeRule.onNodeWithText("Piko").assertIsDisplayed()
        composeRule.onNodeWithText("A little less to do.").assertIsDisplayed()
        composeRule.onNodeWithText("Start task").assertIsDisplayed()
    }

    @Test fun suggestionFillsComposerWithoutStartingTask() {
        composeRule.onNodeWithText("Turn on dark mode").performScrollTo().performClick()
        composeRule.onNode(hasSetTextAction()).assertTextContains("Open Settings and turn on dark mode")
    }

    @Test fun settingsCategoriesExpandAndCollapse() {
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Permissions").performClick()
        composeRule.onNodeWithText("Accessibility control").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Permissions").performScrollTo().performClick()
        composeRule.onNodeWithText("Accessibility control").assertDoesNotExist()
        composeRule.onNodeWithText("Models").performClick()
        composeRule.onNodeWithText("The mind behind your tasks.").assertIsDisplayed()
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("All").assertIsDisplayed()
    }
}
