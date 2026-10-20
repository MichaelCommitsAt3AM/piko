package com.piko.agent.core

import com.piko.agent.model.ElementBounds
import com.piko.agent.model.ScreenObservation
import com.piko.agent.model.UiElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionContextGuardTest {
    private val target = UiElement(
        id = "element_1", text = "Continue", packageName = "test.app",
        bounds = ElementBounds(10, 20, 110, 80), clickable = true,
    )
    private val before = ScreenObservation(
        timestampMs = 1, screenWidth = 400, screenHeight = 800, rotation = 0,
        foregroundPackage = "test.app", elements = listOf(target), fingerprint = "same",
    )

    @Test fun freshCaptureOfSameScreenIsAccepted() {
        assertTrue(ActionContextGuard.isCurrent(before, before.copy(timestampMs = 200)))
    }

    @Test fun sameAppWithChangedButtonIsRejectedEvenIfFingerprintCollides() {
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(elements = listOf(target.copy(text = "Pay")))))
    }

    @Test fun movedOrDisabledTargetIsRejected() {
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(elements = listOf(target.copy(bounds = ElementBounds(10, 120, 110, 180))))))
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(elements = listOf(target.copy(enabled = false)))))
    }

    @Test fun changedFocusWithoutVisibleTextChangeIsRejected() {
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(focusedElementId = target.id)))
    }

    @Test fun rotationAndResizingAreRejected() {
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(rotation = 1)))
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(screenWidth = 800, screenHeight = 400)))
    }

    @Test fun dialogOrAppChangeIsRejected() {
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(hasDialog = true)))
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(foregroundPackage = "another.app")))
    }

    @Test fun unavailableOrUnknownAppIsRejected() {
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(rootAvailable = false)))
        assertFalse(ActionContextGuard.isCurrent(before.copy(foregroundPackage = null), before.copy(foregroundPackage = null)))
    }

    @Test fun changedElementIdsRequireReplanning() {
        assertFalse(ActionContextGuard.isCurrent(before, before.copy(elements = listOf(target.copy(id = "element_2")))))
    }
}
