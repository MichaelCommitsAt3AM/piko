package com.piko.agent

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.piko.agent.ai.LlmClient
import com.piko.agent.data.SecureCredentialStore
import com.piko.agent.data.SettingsRepository
import com.piko.agent.data.TaskRepository
import com.piko.agent.data.db.PikoDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PikoApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: PikoDatabase by lazy { PikoDatabase.get(this) }
    val taskRepository: TaskRepository by lazy { TaskRepository(database) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val credentialStore: SecureCredentialStore by lazy { SecureCredentialStore(this) }
    val llmClient: LlmClient by lazy { LlmClient() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
        applicationScope.launch {
            runCatching {
                val settings = settingsRepository.current()
                taskRepository.prune(settings.retentionDays)
                taskRepository.markOrphansInterrupted()
            }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val agent = NotificationChannel(
            CHANNEL_AGENT,
            getString(R.string.notification_channel_agent),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Task status and stop controls while Piko operates your phone."
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        val setup = NotificationChannel(
            CHANNEL_SETUP,
            getString(R.string.notification_channel_setup),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Permission and setup reminders."
            setShowBadge(true)
        }
        val approvals = NotificationChannel(
            CHANNEL_APPROVALS, "Approval requests", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Permissions and other actions waiting for your approval."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannels(listOf(agent, setup, approvals))
    }

    companion object {
        const val CHANNEL_AGENT = "piko_agent"
        const val CHANNEL_SETUP = "piko_setup"
        const val CHANNEL_APPROVALS = "piko_approvals"

        lateinit var instance: PikoApplication
            private set

        fun from(context: android.content.Context): PikoApplication =
            context.applicationContext as PikoApplication
    }
}
