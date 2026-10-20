package com.piko.agent

import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.piko.agent.agent.AgentRuntime
import com.piko.agent.agent.PendingConfirmation
import com.piko.agent.core.AgentState
import com.piko.agent.service.AgentService
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApprovalNotificationInstrumentedTest {
    @Test fun approvalPostsActionableNotificationAndClearsWhenResolved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val notifications = context.getSystemService(NotificationManager::class.java)
        assumeTrue("Notification permission must be granted", notifications.areNotificationsEnabled())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity {
                    context.startService(Intent(context, AgentService::class.java).setAction("test.approval"))
                    AgentRuntime.begin("approval-test", "Approval notification test", "Test", "Test", 1)
                    AgentRuntime.setState(AgentState.AWAITING_USER_CONFIRMATION)
                    AgentRuntime.setPendingConfirmation(PendingConfirmation(
                        "approval-test-1", null, "Allow access to the camera?", "A permission request needs approval.",
                        "AUTHENTICATION", System.currentTimeMillis(),
                    ))
                }
                val deadline = System.currentTimeMillis() + 5_000
                while (notifications.activeNotifications.none { it.id == AgentService.APPROVAL_NOTIFICATION_ID } && System.currentTimeMillis() < deadline) Thread.sleep(100)
                val notification = notifications.activeNotifications.first { it.id == AgentService.APPROVAL_NOTIFICATION_ID }.notification
                assertEquals(PikoApplication.CHANNEL_APPROVALS, notification.channelId)
                assertEquals(setOf("Approve", "Deny", "Review"), notification.actions.map { it.title.toString() }.toSet())
                assertEquals(NotificationManager.IMPORTANCE_HIGH, notifications.getNotificationChannel(notification.channelId).importance)
                assertNotEquals(notification.actions[0].actionIntent, notification.actions[1].actionIntent)
                instrumentation.runOnMainSync { AgentRuntime.setPendingConfirmation(null) }
                val clearDeadline = System.currentTimeMillis() + 5_000
                while (notifications.activeNotifications.any { it.id == AgentService.APPROVAL_NOTIFICATION_ID } && System.currentTimeMillis() < clearDeadline) Thread.sleep(100)
                assertTrue(notifications.activeNotifications.none { it.id == AgentService.APPROVAL_NOTIFICATION_ID })
            } finally {
                instrumentation.runOnMainSync {
                    context.stopService(Intent(context, AgentService::class.java))
                    AgentRuntime.reset()
                }
            }
        }
    }
}
