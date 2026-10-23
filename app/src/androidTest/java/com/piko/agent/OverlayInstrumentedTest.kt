package com.piko.agent

import android.provider.Settings
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piko.agent.agent.AgentRuntimeState
import com.piko.agent.agent.PendingConfirmation
import com.piko.agent.core.AgentState
import com.piko.agent.overlay.AgentOverlay
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayInstrumentedTest {
    @Test fun approvalButtonApprovesDisplayedRequestWithoutOpeningActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Overlay permission must be enabled", Settings.canDrawOverlays(context))
        instrumentation.runOnMainSync {
            val approvals = mutableListOf<String>()
            val overlay = AgentOverlay(context, object : AgentOverlay.Actions {
                override fun onPauseResume() { }
                override fun onStop() { }
                override fun onVoice() { }
                override fun onApproveConfirmation(confirmationId: String) { approvals += confirmationId }
            })
            try {
                val pending = PendingConfirmation("install-1", null, "Install TikTok?", "Installs an app.", "app install", 0)
                val state = AgentRuntimeState(state = AgentState.AWAITING_USER_CONFIRMATION, pendingConfirmation = pending)
                overlay.show(state)
                val button = AgentOverlay::class.java.getDeclaredField("reviewButton").apply { isAccessible = true }
                    .get(overlay) as View
                assertEquals(View.VISIBLE, button.visibility)
                assertTrue(button.performClick())
                assertEquals(listOf("install-1"), approvals)
                assertFalse(button.isEnabled)
                overlay.update(state.copy(pendingConfirmation = pending.copy(id = "install-2")))
                assertTrue(button.isEnabled)
                button.performClick()
                assertEquals(listOf("install-1", "install-2"), approvals)
                overlay.update(state.copy(state = AgentState.REASONING, pendingConfirmation = null))
                assertEquals(View.GONE, button.visibility)
                button.performClick()
                assertEquals(2, approvals.size)
            } finally { overlay.hide() }
        }
    }

    @Test fun updatesBeforeFirstMinimizationDoNotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Overlay permission must be enabled", Settings.canDrawOverlays(context))
        instrumentation.runOnMainSync {
            val overlay = AgentOverlay(context, object : AgentOverlay.Actions {
                override fun onPauseResume() { }
                override fun onStop() { }
                override fun onVoice() { }
                override fun onApproveConfirmation(confirmationId: String) { }
            })
            try {
                val state = AgentRuntimeState(taskId = "overlay-test", goal = "Overlay test", state = AgentState.PREPARING)
                overlay.show(state)
                overlay.update(state.copy(state = AgentState.REASONING))
                overlay.update(state.copy(state = AgentState.PAUSED))
                overlay.hide()
                overlay.show(state)
                overlay.update(state.copy(state = AgentState.EXECUTING))
            } finally { overlay.hide() }
        }
    }
}
