package com.piko.agent.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.piko.agent.model.ElementBounds
import com.piko.agent.model.ElementRef
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeFinderTest {
    private val ref = ElementRef(
        "element_1", "android.widget.Button", "Continue", null, "test:id/action",
        "test.app", ElementBounds(10, 20, 110, 80), true, false, false, false, false, false,
    )

    private fun node() = AccessibilityNodeInfo().apply {
        packageName = "test.app"
        className = "android.widget.Button"
        text = "Continue"
        viewIdResourceName = "test:id/action"
        isEnabled = true
        isVisibleToUser = true
        setBoundsInScreen(Rect(10, 20, 110, 80))
    }

    @Test fun sameTargetMatches() {
        assertTrue(NodeFinder.matches(node(), ref))
    }

    @Test fun labelsUseTheObservationWhitespaceAndLengthRules() {
        assertTrue(NodeFinder.matches(node().apply { text = "  Continue\n" }, ref))
        val longLabel = "A".repeat(200)
        assertTrue(NodeFinder.matches(node().apply { text = longLabel }, ref.copy(text = longLabel.take(180))))
    }

    @Test fun reusedResourceIdWithNewLabelDoesNotMatch() {
        assertFalse(NodeFinder.matches(node().apply { text = "Delete" }, ref))
    }

    @Test fun sameLabelElsewhereDoesNotMatch() {
        assertFalse(NodeFinder.matches(node().apply { setBoundsInScreen(Rect(10, 200, 110, 260)) }, ref))
    }

    @Test fun samePositionInAnotherPackageDoesNotMatch() {
        assertFalse(NodeFinder.matches(node().apply { packageName = "other.app" }, ref))
    }

    @Test fun hiddenOrDisabledTargetsDoNotMatch() {
        assertFalse(NodeFinder.matches(node().apply { isVisibleToUser = false }, ref))
        assertFalse(NodeFinder.matches(node().apply { isEnabled = false }, ref))
    }
}
