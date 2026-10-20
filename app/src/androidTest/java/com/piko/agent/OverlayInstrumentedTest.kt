package com.piko.agent

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piko.agent.agent.AgentRuntimeState
import com.piko.agent.core.AgentState
import com.piko.agent.overlay.AgentOverlay
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayInstrumentedTest {
    @Test fun updatesBeforeFirstMinimizationDoNotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("Overlay permission must be enabled", Settings.canDrawOverlays(context))
        instrumentation.runOnMainSync {
            val overlay = AgentOverlay(context, object : AgentOverlay.Actions {
                override fun onPauseResume() { }
                override fun onStop() { }
                override fun onVoice() { }
                override fun onReviewConfirmation() { }
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
