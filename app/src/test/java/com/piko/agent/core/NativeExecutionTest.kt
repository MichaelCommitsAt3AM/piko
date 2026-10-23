package com.piko.agent.core

import android.content.Context
import android.media.AudioManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.piko.agent.accessibility.*
import com.piko.agent.agent.*
import com.piko.agent.ai.LlmClient
import com.piko.agent.data.TaskRepository
import com.piko.agent.data.db.PikoDatabase
import com.piko.agent.model.*
import com.piko.agent.tools.NativeCommandPlanner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeExecutionTest {
    @Test fun volumeExecutesAndVerifiesWithNoKeyOrAccessibility() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, PikoDatabase::class.java).build()
        try {
            AccessibilityBridge.service?.let { AccessibilityBridge.detach(it) }
            val provider = ProviderCatalog.openCodeGo()
            AgentController(context, "native-offline-test", "Set volume to 30%", PikoSettings(historyEnabled = false),
                provider, provider.selectedModel!!, "", TaskRepository(db), LlmClient(),
                ObservationEngine(context, ScreenshotEngine(context)), ActionExecutor(context),
                nativePlan = NativeCommandPlanner.plan("Set volume to 30%")).run()
            assertEquals(AgentState.COMPLETED, AgentRuntime.state().state)
            assertEquals(true, AgentRuntime.state().verified)
            assertEquals(0L, AgentRuntime.state().usage.inputTokens)
            assertFalse(AgentRuntime.state().screenHandoffRequired)
        } finally { db.close(); AgentRuntime.reset() }
    }
}
