package com.piko.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveActionClassifierTest {
    private fun assess(
        action: AgentAction,
        text: String? = null,
        description: String? = null,
        resourceId: String? = null,
        password: Boolean = false,
        foregroundPackage: String? = "com.example",
    ) = SensitiveActionClassifier.classify(
        SensitiveActionClassifier.Context(
            action = action,
            targetText = text,
            targetDescription = description,
            targetResourceId = resourceId,
            targetPackage = foregroundPackage,
            isPasswordField = password,
            foregroundPackage = foregroundPackage,
        ),
    )

    @Test
    fun `safe navigation does not need confirmation`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Dark theme")
        assertEquals(SensitiveActionClassifier.Level.NONE, result.level)
    }

    @Test
    fun `send needs confirmation`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Send")
        assertEquals(SensitiveActionClassifier.Level.CONFIRM, result.level)
        assertEquals(SensitiveActionClassifier.Category.MESSAGE_SEND, result.category)
    }

    @Test
    fun `payment needs confirmation`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Buy now")
        assertEquals(SensitiveActionClassifier.Category.PAYMENT, result.category)
    }

    @Test
    fun `deletion needs confirmation`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Delete account")
        assertEquals(SensitiveActionClassifier.Category.DATA_DELETION, result.category)
    }

    @Test
    fun `install needs confirmation`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Install")
        assertEquals(SensitiveActionClassifier.Category.APP_INSTALL, result.category)
    }

    @Test
    fun `permission allow needs confirmation`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Allow")
        assertEquals(SensitiveActionClassifier.Category.AUTHENTICATION, result.category)
    }

    @Test
    fun `security setting needs confirmation`() {
        val result = assess(
            AgentAction(type = ActionType.TAP, elementId = "element_1"),
            text = "Screen lock",
            foregroundPackage = "com.android.settings",
        )
        assertEquals(SensitiveActionClassifier.Category.SECURITY_SETTINGS, result.category)
    }

    @Test
    fun `external share needs confirmation`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Share")
        assertEquals(SensitiveActionClassifier.Category.EXTERNAL_SHARE, result.category)
    }

    @Test
    fun `password entry is blocked`() {
        val result = assess(
            AgentAction(type = ActionType.TYPE_TEXT, text = "hunter2"),
            password = true,
        )
        assertEquals(SensitiveActionClassifier.Level.BLOCK, result.level)
        assertEquals(SensitiveActionClassifier.Category.CREDENTIAL_ENTRY, result.category)
    }

    @Test
    fun `payment card number is blocked`() {
        val result = assess(AgentAction(type = ActionType.TYPE_TEXT, text = "4111 1111 1111 1111"))
        assertEquals(SensitiveActionClassifier.Level.BLOCK, result.level)
    }

    @Test
    fun `resource id can identify a send control`() {
        val result = assess(
            AgentAction(type = ActionType.TAP, elementId = "element_1"),
            resourceId = "com.example:id/send_button",
        )
        assertEquals(SensitiveActionClassifier.Category.MESSAGE_SEND, result.category)
    }

    @Test
    fun `clear text is not treated as data deletion`() {
        val result = assess(AgentAction(type = ActionType.CLEAR_TEXT, elementId = "element_1"), text = "Clear")
        assertEquals(SensitiveActionClassifier.Level.NONE, result.level)
    }

    @Test
    fun `coordinates inherit the target element label at classification site`() {
        // The controller resolves the element under a coordinate before calling
        // the classifier; this verifies that label-based classification works.
        val result = assess(
            AgentAction(type = ActionType.TAP, x = 500, y = 900),
            text = "Place order",
        )
        assertEquals(SensitiveActionClassifier.Category.PAYMENT, result.category)
    }

    @Test
    fun `localized destructive labels are recognized`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Löschen")
        assertEquals(SensitiveActionClassifier.Category.DATA_DELETION, result.category)
    }

    @Test
    fun `localized send labels are recognized`() {
        val result = assess(AgentAction(type = ActionType.TAP, elementId = "element_1"), text = "Envoyer")
        assertEquals(SensitiveActionClassifier.Category.MESSAGE_SEND, result.category)
    }
}

class SensitiveTextRedactorTest {
    @Test
    fun `password flag always redacts`() {
        assertTrue(SensitiveTextRedactor.shouldRedact(null, null, null, isPassword = true))
    }

    @Test
    fun `sensitive resource ids are detected`() {
        assertTrue(SensitiveTextRedactor.shouldRedact("com.app:id/otp_input"))
        assertTrue(SensitiveTextRedactor.shouldRedact("com.app:id/card_number"))
        assertTrue(SensitiveTextRedactor.shouldRedact(null, hint = "CVV"))
        assertFalse(SensitiveTextRedactor.shouldRedact("com.app:id/search"))
    }

    @Test
    fun `trace values redact card-like content`() {
        assertEquals("[redacted]", SensitiveTextRedactor.forTrace("4111111111111111"))
        assertEquals("hello", SensitiveTextRedactor.forTrace("hello"))
    }

    @Test
    fun `visible otp messages and card runs are redacted`() {
        val otp = SensitiveTextRedactor.redactVisibleText("Your verification code is 483920")
        assertTrue(otp!!.contains("[redacted]"))
        assertTrue(!otp.contains("483920"))
        val card = SensitiveTextRedactor.redactVisibleText("Pay with 4111 1111 1111 1111")
        assertTrue(card!!.contains("[redacted]"))
        assertTrue(!card.contains("4111"))
    }

    @Test
    fun `ordinary text is unchanged`() {
        assertEquals("Display settings", SensitiveTextRedactor.redactVisibleText("Display settings"))
        assertEquals("Result: 336", SensitiveTextRedactor.redactVisibleText("Result: 336"))
    }}

class SensitiveAppsTest {
    @Test
    fun `credential managers are blocked`() {
        assertTrue(SensitiveApps.isBlocked("com.bitwarden"))
        assertTrue(SensitiveApps.isBlocked("com.google.android.apps.authenticator2"))
        assertTrue(SensitiveApps.blockReason("com.x8bit.bitwarden")!!.contains("password managers"))
    }

    @Test
    fun `terminals are blocked to prevent shell execution through the UI`() {
        assertTrue(SensitiveApps.isBlocked("com.termux"))
        assertTrue(SensitiveApps.blockReason("jackpal.androidterm")!!.contains("shell"))
    }

    @Test
    fun `normal apps are allowed`() {
        assertFalse(SensitiveApps.isBlocked("com.android.settings"))
        assertFalse(SensitiveApps.isBlocked(null))
    }
}

class UiFingerprintTest {
    private val base = listOf(
        FingerprintElement(
            text = "Settings",
            description = null,
            resourceId = "com.android.settings:id/title",
            bounds = FingerprintRect(0, 100, 500, 200),
        ),
        FingerprintElement(
            text = "Display",
            description = "Display settings",
            resourceId = "com.android.settings:id/display",
            bounds = FingerprintRect(0, 200, 500, 300),
        ),
    )

    @Test
    fun `fingerprint is stable for equivalent screens`() {
        val first = UiFingerprint.compute("com.android.settings", base)
        val second = UiFingerprint.compute("com.android.settings", base.reversed())
        assertEquals(first, second)
    }

    @Test
    fun `fingerprint changes when text changes`() {
        val changed = base.map { if (it.text == "Display") it.copy(text = "Battery") else it }
        assertTrue(UiFingerprint.compute("com.android.settings", base) != UiFingerprint.compute("com.android.settings", changed))
    }

    @Test
    fun `fingerprint changes when checked state changes`() {
        val changed = base.map { it.copy(checked = true) }
        assertTrue(UiFingerprint.compute("com.android.settings", base) != UiFingerprint.compute("com.android.settings", changed))
    }

    @Test
    fun `fingerprint changes when enabled state changes`() {
        val changed = base.map { it.copy(enabled = false) }
        assertTrue(UiFingerprint.compute("com.android.settings", base) != UiFingerprint.compute("com.android.settings", changed))
    }

    @Test
    fun `package identity is part of fingerprint`() {
        assertTrue(UiFingerprint.compute("a", base) != UiFingerprint.compute("b", base))
    }
}

class UsageTest {
    @Test
    fun `cost calculation uses millions of tokens`() {
        val usage = TokenUsage.estimate(
            inputTokens = 1_000_000,
            outputTokens = 500_000,
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
        )
        assertEquals(2.0, usage.costUsd, 0.0001)
    }

    @Test
    fun `cached input is discounted`() {
        val usage = TokenUsage.estimate(
            inputTokens = 1_000_000,
            outputTokens = 0,
            inputPricePerMillion = 10.0,
            outputPricePerMillion = 0.0,
            cachedInputTokens = 1_000_000,
        )
        assertEquals(1.0, usage.costUsd, 0.0001)
    }

    @Test
    fun `usage accumulates`() {
        val total = TokenUsage(10, 20).plus(TokenUsage(5, 7))
        assertEquals(15, total.inputTokens)
        assertEquals(27, total.outputTokens)
    }
}
