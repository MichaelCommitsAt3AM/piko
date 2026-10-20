package com.piko.agent.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotPrivacyTest {
    @Test fun passwordFieldsWithholdEvenWhenEmpty() {
        assertTrue(SensitiveTextRedactor.shouldWithholdScreenshot(null, null, null, true, "", null))
    }

    @Test fun credentialHintsWithholdPixels() {
        assertTrue(SensitiveTextRedactor.shouldWithholdScreenshot(null, "One-time code", null, false, "123456", null))
    }

    @Test fun otpInVisibleMessageWithholdsPixels() {
        assertTrue(SensitiveTextRedactor.shouldWithholdScreenshot(null, null, null, false, "Your verification code is 123456", null))
    }

    @Test fun cardNumberInDescriptionWithholdsPixels() {
        assertTrue(SensitiveTextRedactor.shouldWithholdScreenshot(null, null, null, false, null, "Card 4111 1111 1111 1111"))
    }

    @Test fun ordinaryContentCanBeCaptured() {
        assertFalse(SensitiveTextRedactor.shouldWithholdScreenshot("test:id/search", "Search", "EditText", false, "weather today", null))
    }
}
