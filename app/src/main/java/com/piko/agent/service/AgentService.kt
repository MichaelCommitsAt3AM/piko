package com.piko.agent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.piko.agent.MainActivity
import com.piko.agent.PikoApplication
import com.piko.agent.R
import com.piko.agent.accessibility.AccessibilityBridge
import com.piko.agent.accessibility.ActionExecutor
import com.piko.agent.accessibility.ObservationEngine
import com.piko.agent.accessibility.ScreenshotEngine
import com.piko.agent.agent.AgentController
import com.piko.agent.agent.AgentRuntime
import com.piko.agent.agent.AgentRuntimeState
import com.piko.agent.core.AgentState
import com.piko.agent.model.PikoSettings
import com.piko.agent.overlay.AgentOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: AgentController? = null
    private var taskJob: Job? = null
    private var cleanupJob: Job? = null
    private var overlay: AgentOverlay? = null
    private var startedForeground = false
    private var currentGoal: String = ""
    private var screenReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    controller?.pause("The screen was locked. Unlock the phone, then tap Resume.")
                }
            }
        }
        screenReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scope.launch {
            AgentRuntime.state.collectLatest { state ->
                withContext(Dispatchers.Main) {
                    overlay?.update(state)
                    if (startedForeground) updateNotification(state)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                currentGoal = intent.getStringExtra(EXTRA_GOAL).orEmpty().trim()
                cleanupJob?.cancel()
                cleanupJob = null
                startForegroundForTask()
                if (currentGoal.isBlank()) {
                    failPreflight("No instruction was supplied.")
                    return START_NOT_STICKY
                }
                if (taskJob?.isActive == true) {
                    updateNotification(AgentRuntime.state())
                    return START_NOT_STICKY
                }
                launchTask(currentGoal)
            }
            ACTION_PAUSE -> controller?.pause("Paused by the user.")
            ACTION_RESUME -> controller?.resume()
            ACTION_STOP -> stopTask()
            ACTION_APPROVE -> controller?.approveConfirmation()
            ACTION_DENY -> controller?.denyConfirmation()
            ACTION_OPEN_APP -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundForTask() {
        if (startedForeground) return
        val state = AgentRuntime.state()
        val notification = buildNotification(
            state,
            goal = currentGoal.ifBlank { state.goal },
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startedForeground = true
    }

    private fun launchTask(goal: String) {
        val app = application as PikoApplication
        taskJob = scope.launch {
            val settings = runCatching { app.settingsRepository.current() }.getOrDefault(PikoSettings())
            runCatching { app.taskRepository.prune(settings.retentionDays) }
            val provider = settings.selectedProvider
            val model = provider?.selectedModel
            val taskId = UUID.randomUUID().toString()
            if (provider == null || model == null) {
                failPreflight(
                    "No AI model is configured. Open Models, add a provider key and select a model.",
                    taskId = taskId,
                    goal = goal,
                    settings = settings,
                )
                return@launch
            }
            val apiKey = app.credentialStore.getApiKey(provider.id).orEmpty()
            if (apiKey.isBlank()) {
                failPreflight(
                    "No API key is stored for ${provider.name}. Open Models and save your key.",
                    taskId = taskId,
                    goal = goal,
                    settings = settings,
                    providerName = provider.name,
                    modelName = model.label,
                )
                return@launch
            }
            if (!AccessibilityBridge.connected.value) {
                withTimeoutOrNull(3_000) {
                    AccessibilityBridge.connected.first { it }
                }
            }
            if (!AccessibilityBridge.connected.value) {
                failPreflight(
                    "Accessibility is not enabled. Enable Piko in Android Settings > Accessibility, then try again.",
                    taskId = taskId,
                    goal = goal,
                    settings = settings,
                    providerName = provider.name,
                    modelName = model.label,
                )
                return@launch
            }
            if (provider.baseUrl.isBlank()) {
                failPreflight(
                    "The selected provider has no base URL.",
                    taskId = taskId,
                    goal = goal,
                    settings = settings,
                    providerName = provider.name,
                    modelName = model.label,
                )
                return@launch
            }

            val screenshotEngine = ScreenshotEngine(this@AgentService)
            val observationEngine = ObservationEngine(this@AgentService, screenshotEngine)
            val executor = ActionExecutor(this@AgentService)
            val taskController = AgentController(
                context = this@AgentService,
                taskId = taskId,
                goal = goal,
                settings = settings,
                provider = provider,
                model = model,
                apiKey = apiKey,
                taskRepository = app.taskRepository,
                llmClient = app.llmClient,
                observationEngine = observationEngine,
                actionExecutor = executor,
            )
            controller = taskController
            prepareOverlay(settings)
            try {
                taskController.run()
            } catch (cancelled: CancellationException) {
                // Cancellation is finalized inside the controller.
            } finally {
                controller = null
                onTaskFinished()
            }
        }
    }

    private fun failPreflight(
        message: String,
        taskId: String = UUID.randomUUID().toString(),
        goal: String = currentGoal,
        settings: PikoSettings = PikoSettings(),
        providerName: String = "",
        modelName: String = "",
    ) {
        val app = application as PikoApplication
        scope.launch {
            if (settings.historyEnabled) {
                runCatching {
                    app.taskRepository.startTask(taskId, goal, providerName, modelName)
                    app.taskRepository.finishTask(
                        id = taskId,
                        status = AgentState.FAILED,
                        summary = message,
                        error = message,
                        verified = false,
                        verification = null,
                        stepCount = 0,
                        inputTokens = 0,
                        outputTokens = 0,
                        costUsd = 0.0,
                    )
                }
            }
            AgentRuntime.begin(taskId, goal, providerName, modelName, settings.maxSteps)
            AgentRuntime.complete(
                summary = message,
                verified = false,
                verification = null,
                state = AgentState.FAILED,
                error = message,
            )
            AgentRuntime.addTimeline(AgentState.FAILED, "Cannot start task", message, isError = true)
            onTaskFinished()
        }
    }

    private fun prepareOverlay(settings: PikoSettings) {
        if (!settings.overlayEnabled) return
        val manager = AgentOverlay(
            context = this,
            actions = object : AgentOverlay.Actions {
                override fun onPauseResume() {
                    val state = AgentRuntime.state().state
                    if (state == AgentState.PAUSED) controller?.resume() else controller?.pause("Paused from the Piko overlay.")
                }

                override fun onStop() {
                    stopTask()
                }

                override fun onReviewConfirmation() {
                    startActivity(Intent(this@AgentService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
        if (manager.canShow()) {
            overlay = manager
            with(manager) {
                // show is main-thread only because it touches WindowManager.
            }
            scope.launch {
                withContext(Dispatchers.Main) {
                    manager.show(AgentRuntime.state())
                }
            }
        }
    }

    private fun onTaskFinished() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            delay(1200)
            if (taskJob?.isActive == true) return@launch
            withContext(Dispatchers.Main) {
                overlay?.hide()
                overlay = null
            }
            if (startedForeground) {
                stopForeground(STOP_FOREGROUND_DETACH)
                startedForeground = false
            }
            stopSelf()
        }
    }

    private fun stopTask() {
        val active = controller
        if (active == null) {
            AgentRuntime.markInterrupted("The task was stopped before it started.")
            onTaskFinished()
            return
        }
        active.requestStop()
        taskJob?.cancel(CancellationException("Stopped by user"))
    }

    private fun updateNotification(state: AgentRuntimeState) {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(state, state.goal.ifBlank { currentGoal }))
    }

    private fun buildNotification(state: AgentRuntimeState, goal: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (state.state) {
            AgentState.PREPARING -> "Piko is preparing"
            AgentState.OBSERVING -> "Piko is observing"
            AgentState.REASONING -> "Piko is thinking"
            AgentState.EXECUTING -> "Piko is acting"
            AgentState.VERIFYING -> "Piko is verifying"
            AgentState.AWAITING_USER_CONFIRMATION -> "Piko needs your approval"
            AgentState.PAUSED -> "Piko is paused"
            AgentState.COMPLETED -> "Piko finished"
            AgentState.FAILED -> "Piko stopped"
            AgentState.CANCELLED -> "Piko was stopped"
            AgentState.IDLE -> getString(R.string.notification_agent_title)
        }
        val detail = when (state.state) {
            AgentState.AWAITING_USER_CONFIRMATION -> state.pendingConfirmation?.summary.orEmpty()
            AgentState.PAUSED -> state.pauseReason ?: "Tap Resume to continue."
            AgentState.FAILED -> state.error ?: "The task did not complete."
            AgentState.COMPLETED -> state.resultSummary ?: "Task completed."
            else -> state.currentAction ?: goal
        }
        val builder = NotificationCompat.Builder(this, PikoApplication.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_stat_piko)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail\n\nGoal: $goal"))
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(state.state.isRunning || state.state == AgentState.PAUSED)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.maxSteps > 0 && state.state.isRunning) {
            builder.setProgress(state.maxSteps, state.step.coerceAtMost(state.maxSteps), false)
        }
        if (state.state == AgentState.PAUSED) {
            builder.addAction(0, "Resume", servicePendingIntent(ACTION_RESUME, 11))
        } else if (state.state.isRunning) {
            builder.addAction(0, "Pause", servicePendingIntent(ACTION_PAUSE, 10))
        }
        if (state.pendingConfirmation != null) {
            builder.addAction(0, "Review", openIntent)
        }
        if (state.state.isRunning || state.state == AgentState.PAUSED) {
            builder.addAction(0, "Stop", servicePendingIntent(ACTION_STOP, 12))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, AgentService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    override fun onDestroy() {
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        overlay?.hide()
        overlay = null
        val active = controller
        if (active != null && !active.isFinished) {
            active.requestStop()
            AgentRuntime.markInterrupted("Piko's task service was stopped by Android. The task was not resumed automatically.")
            taskJob?.cancel()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 41
        private const val ACTION_START = "com.piko.agent.action.START_TASK"
        private const val ACTION_PAUSE = "com.piko.agent.action.PAUSE_TASK"
        private const val ACTION_RESUME = "com.piko.agent.action.RESUME_TASK"
        private const val ACTION_STOP = "com.piko.agent.action.STOP_TASK"
        private const val ACTION_APPROVE = "com.piko.agent.action.APPROVE"
        private const val ACTION_DENY = "com.piko.agent.action.DENY"
        private const val ACTION_OPEN_APP = "com.piko.agent.action.OPEN_APP"
        private const val EXTRA_GOAL = "goal"

        fun start(context: Context, goal: String) {
            val intent = Intent(context, AgentService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_GOAL, goal)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_PAUSE))
        }

        fun resume(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_RESUME))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_STOP))
        }

        fun approve(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_APPROVE))
        }

        fun deny(context: Context) {
            context.startService(Intent(context, AgentService::class.java).setAction(ACTION_DENY))
        }
    }
}
