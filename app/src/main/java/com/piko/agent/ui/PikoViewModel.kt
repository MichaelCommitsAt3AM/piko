package com.piko.agent.ui

import android.app.Application
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piko.agent.PikoApplication
import com.piko.agent.accessibility.AccessibilityBridge
import com.piko.agent.agent.AgentRuntime
import com.piko.agent.ai.ConnectionTestResult
import com.piko.agent.ai.LlmClient
import com.piko.agent.core.AgentState
import com.piko.agent.data.db.TaskEntity
import com.piko.agent.model.PikoSettings
import com.piko.agent.model.ProviderCatalog
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ProviderKind
import com.piko.agent.model.RemoteModel
import com.piko.agent.service.AgentService
import com.piko.agent.service.ProjectionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class PermissionState(
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val overlayAllowed: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val projectionActive: Boolean = false,
    val microphoneAllowed: Boolean = false,
    val cameraAllowed: Boolean = false,
    val assistantSelected: Boolean = false,
    val notificationAccess: Boolean = false,
) {
    val screenshotsAvailable: Boolean get() = accessibilityEnabled || projectionActive
}

data class HomeUiState(
    val settings: PikoSettings = PikoSettings(),
    val recentTasks: List<TaskEntity> = emptyList(),
    val permissions: PermissionState = PermissionState(),
    val selectedProvider: ProviderConfig? = null,
    val hasSelectedKey: Boolean = false,
)

class PikoViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as PikoApplication
    private val context: Context get() = getApplication()
    private val settingsRepository = app.settingsRepository
    private val taskRepository = app.taskRepository
    private val credentials = app.credentialStore
    private val llmClient = app.llmClient

    val settings: StateFlow<PikoSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PikoSettings())

    val runtime = AgentRuntime.state
    val logs = AgentRuntime.logs
    val openAssistant = MutableStateFlow(false)
    val projectionActive = ProjectionService.active

    private val _permissions = MutableStateFlow(readPermissions())
    val permissions: StateFlow<PermissionState> = _permissions.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _credentialRevision = MutableStateFlow(0L)
    val credentialRevision: StateFlow<Long> = _credentialRevision.asStateFlow()

    init {
        viewModelScope.launch {
            AccessibilityBridge.connected.collect { connected ->
                _permissions.value = _permissions.value.copy(accessibilityConnected = connected)
            }
        }
        viewModelScope.launch {
            ProjectionService.active.collect { active ->
                _permissions.value = _permissions.value.copy(projectionActive = active)
            }
        }
    }

    val memories = app.memoryRepository.memories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val workflows = app.database.assistantDao().workflows().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val schedules = app.database.assistantDao().schedules().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveMemory(key: String, content: String, category: String = "Preference", approved: Boolean = true, expiresAt: Long? = null) {
        viewModelScope.launch {
            runCatching { app.memoryRepository.save(key, content, category, approved, expiresAt) }
                .onSuccess { emit(if (approved) "Memory saved locally. Relevant preferences may be sent with model requests." else "Suggestion saved for review.") }
                .onFailure { emit(it.message ?: "Could not save memory.") }
        }
    }
    fun deleteMemory(id: String) { viewModelScope.launch { app.memoryRepository.delete(id) } }
    fun clearMemories() { viewModelScope.launch { app.memoryRepository.clear() } }
    fun saveWorkflow(name: String, description: String, steps: List<com.piko.agent.workflow.WorkflowStep>, id: String? = null) {
        viewModelScope.launch {
            runCatching {
                require(name.isNotBlank() && name.length <= 120) { "Enter a workflow name (1–120 characters)." }
                val errors = com.piko.agent.workflow.WorkflowVariables.templateErrors(steps)
                require(errors.isEmpty()) { errors.joinToString(" ") }
                require(steps.all { com.piko.agent.data.MemoryRepository.isSafe(it.instruction.ifBlank { "Step" }) && it.parameters.values.all { value ->
                    com.piko.agent.data.MemoryRepository.isSafe((value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
                } }) { "Remove credential-like or private data before saving." }
                val now = System.currentTimeMillis()
                val previous = id?.let { app.database.assistantDao().workflow(it) }
                app.database.assistantDao().putWorkflow(com.piko.agent.data.db.WorkflowEntity(id ?: UUID.randomUUID().toString(), name.trim(), description.take(2000),
                    com.piko.agent.core.PikoJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.piko.agent.workflow.WorkflowStep.serializer()), steps), previous?.createdAt ?: now, now))
                emit("Workflow saved after your review.")
            }.onFailure { emit(it.message ?: "Could not save workflow.") }
        }
    }
    fun runWorkflow(workflow: com.piko.agent.data.db.WorkflowEntity, variables: Map<String, String> = emptyMap()): Boolean {
        if (runtime.value.isActive) { emit("Stop or finish the current task first."); return false }
        return runCatching { AgentService.start(context, "Run workflow: ${workflow.name}", workflowId = workflow.id, workflowArguments = variables); true }
            .getOrElse { emit(it.message ?: "Could not start workflow."); false }
    }
    fun deleteWorkflow(id: String) {
        viewModelScope.launch {
            app.database.assistantDao().schedules().first().filter { it.workflowId == id }.forEach { schedule ->
                com.piko.agent.workflow.WorkflowScheduler(context).cancel(schedule.id)
                app.database.assistantDao().deleteSchedule(schedule.id)
            }
            app.database.assistantDao().deleteWorkflow(id)
        }
    }
    fun scheduleWorkflow(id: String, hour: Int, minute: Int, recurrence: String, weekday: Int) {
        viewModelScope.launch {
            runCatching {
                val next = com.piko.agent.workflow.ScheduleCalculator.next(hour, minute, recurrence, weekday, java.time.Instant.now(), java.time.ZoneId.systemDefault())
                val schedule = com.piko.agent.data.db.ScheduleEntity(UUID.randomUUID().toString(), id, hour, minute, recurrence, weekday, next)
                app.database.assistantDao().putSchedule(schedule)
                com.piko.agent.workflow.WorkflowScheduler(context).arm(schedule)
                emit("Reminder scheduled. Android may delay delivery; unlock and review before running.")
            }.onFailure { emit(it.message ?: "Could not schedule workflow.") }
        }
    }
    fun toggleSchedule(schedule: com.piko.agent.data.db.ScheduleEntity) {
        viewModelScope.launch {
            val updated = schedule.copy(enabled = !schedule.enabled, nextRunAt = com.piko.agent.workflow.ScheduleCalculator.next(schedule.hour, schedule.minute, schedule.recurrence, schedule.weekday, java.time.Instant.now(), java.time.ZoneId.systemDefault()))
            app.database.assistantDao().putSchedule(updated)
            com.piko.agent.workflow.WorkflowScheduler(context).arm(updated)
        }
    }
    fun cancelSchedule(id: String) {
        viewModelScope.launch { com.piko.agent.workflow.WorkflowScheduler(context).cancel(id); app.database.assistantDao().deleteSchedule(id) }
    }

    val recentTasks: StateFlow<List<TaskEntity>> = taskRepository.observeRecent(8)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val homeState: StateFlow<HomeUiState> = combine(
        settings,
        recentTasks,
        permissions,
        runtime,
        _credentialRevision,
    ) { settings, tasks, permissions, _, _ ->
        val provider = settings.selectedProvider
        HomeUiState(
            settings = settings,
            recentTasks = tasks,
            permissions = permissions,
            selectedProvider = provider,
            hasSelectedKey = provider?.let { credentials.hasKey(it.id) } == true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refreshPermissions() {
        _permissions.value = readPermissions()
    }

    private fun readPermissions(): PermissionState {
        val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
        val enabled = accessibilityManager
            ?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info ->
                info.resolveInfo?.serviceInfo?.packageName == context.packageName
            } == true
        return PermissionState(
            accessibilityEnabled = enabled,
            accessibilityConnected = AccessibilityBridge.connected.value,
            overlayAllowed = Settings.canDrawOverlays(context),
            notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            projectionActive = ProjectionService.isActive(),
            microphoneAllowed = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            cameraAllowed = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED,
            assistantSelected = context.getSystemService(android.app.role.RoleManager::class.java).let { it.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT) },
            notificationAccess = context.packageName in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context),
        )
    }

    fun startTask(goal: String, screenQuestion: Boolean = false): Boolean {
        val clean = goal.trim()
        if (clean.isEmpty()) {
            emit("Describe what you want Piko to do.")
            return false
        }
        if (!screenQuestion && !runtime.value.isActive && com.piko.agent.data.MemoryCommand.parse(clean) != null) {
            return runCatching { AgentService.start(context, clean); true }.getOrElse { emit("Could not open local memory: ${it.message}"); false }
        }
        if (runtime.value.state.isRunning || runtime.value.state == AgentState.PAUSED) {
            emit("A task is already running.")
            return false
        }
        if (!screenQuestion && com.piko.agent.tools.NativeCommandPlanner.plan(clean) != null) {
            return runCatching { AgentService.start(context, clean); true }.getOrElse { emit(it.message ?: "Could not start native task."); false }
        }
        if (!readPermissions().accessibilityEnabled) {
            emit("Enable Piko's accessibility service before starting.")
            return false
        }
        val provider = settings.value.selectedProvider
        if (provider?.selectedModel == null) {
            emit("Configure a model provider and select a model before starting.")
            return false
        }
        if (!credentials.hasKey(provider.id)) {
            emit("Save an API key for ${provider.name} before starting.")
            return false
        }
        return try {
            AgentService.start(context, clean, screenQuestion)
            true
        } catch (error: Exception) {
            emit("Could not start Piko: ${error.message}")
            false
        }
    }

    fun pause() = AgentService.pause(context)
    fun resume() = AgentService.resume(context)
    fun stop() = AgentService.stop(context)
    fun approve() = AgentService.approve(context)
    fun deny() = AgentService.deny(context)

    fun updateSettings(transform: (PikoSettings) -> PikoSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    suspend fun currentSettings(): PikoSettings = settingsRepository.current()

    fun hasApiKey(providerId: String): Boolean = credentials.hasKey(providerId)

    suspend fun saveProvider(provider: ProviderConfig, apiKey: String, clearKey: Boolean = false): Boolean {
        val normalized = provider.normalized()
        if (normalized.models.isEmpty()) {
            emit("Add at least one model ID before saving this provider.")
            return false
        }
        if (normalized.baseUrl.isBlank()) {
            emit("Enter the provider base URL.")
            return false
        }
        val credentialUpdate = withContext(Dispatchers.IO) {
            runCatching {
                if (clearKey) {
                    credentials.deleteApiKey(normalized.id)
                } else if (apiKey.isNotBlank()) {
                    credentials.setApiKey(normalized.id, apiKey.trim())
                }
            }
        }
        credentialUpdate.onFailure {
            emit("Could not update the secure credential: ${it.message ?: "Keystore error"}.")
            return false
        }
        settingsRepository.upsertProvider(normalized)
        _credentialRevision.value = _credentialRevision.value + 1
        emit("Saved ${normalized.name}.")
        return true
    }

    suspend fun deleteProvider(providerId: String) {
        withContext(Dispatchers.IO) { runCatching { credentials.deleteApiKey(providerId) } }
        settingsRepository.deleteProvider(providerId)
        _credentialRevision.value = _credentialRevision.value + 1
        emit("Provider removed.")
    }

    suspend fun selectProvider(providerId: String) {
        settingsRepository.selectProvider(providerId)
    }

    suspend fun createProvider(kind: ProviderKind): String {
        val current = settingsRepository.current()
        if (kind == ProviderKind.OPENCODE_GO) {
            current.providerConfigs.firstOrNull { it.kind == ProviderKind.OPENCODE_GO }?.let { existing ->
                settingsRepository.selectProvider(existing.id)
                return existing.id
            }
        }
        val base = when (kind) {
            ProviderKind.OPENCODE_GO -> ProviderCatalog.openCodeGo()
            ProviderKind.OPENAI -> ProviderCatalog.openAi()
            ProviderKind.ANTHROPIC -> ProviderCatalog.anthropic()
            ProviderKind.OPENAI_COMPATIBLE, ProviderKind.CUSTOM -> ProviderCatalog.compatible()
        }
        val id = when (kind) {
            ProviderKind.OPENCODE_GO -> ProviderCatalog.OPENCODE_GO_ID
            ProviderKind.OPENAI -> uniqueId("openai", current.providerConfigs)
            ProviderKind.ANTHROPIC -> uniqueId("anthropic", current.providerConfigs)
            ProviderKind.OPENAI_COMPATIBLE -> uniqueId("compatible", current.providerConfigs)
            ProviderKind.CUSTOM -> uniqueId("custom", current.providerConfigs)
        }
        val provider = base.copy(id = id)
        settingsRepository.upsertProvider(provider, select = true)
        return id
    }

    fun enrichModels(provider: ProviderConfig, remote: List<RemoteModel>): List<com.piko.agent.model.ModelConfig> {
        val supportedRemote = remote.filterNot { item ->
            provider.kind == ProviderKind.OPENCODE_GO && item.id.startsWith("gemini-")
        }
        val known = if (provider.kind == ProviderKind.OPENCODE_GO) {
            ProviderCatalog.openCodeGoModels().associateBy { it.id }
        } else {
            emptyMap()
        }
        val existing = provider.models.associateBy { it.id }
        val merged = supportedRemote.map { item ->
            existing[item.id]
                ?: known[item.id]?.copy(displayName = item.displayName)
                ?: ProviderCatalog.modelForModelId(provider.kind, item.id).copy(displayName = item.displayName)
        }.toMutableList()
        // Keep manually configured models that the endpoint did not advertise.
        provider.models.forEach { model ->
            val unsupported = provider.kind == ProviderKind.OPENCODE_GO && model.id.startsWith("gemini-")
            if (!unsupported && merged.none { it.id == model.id }) merged += model
        }
        return merged
    }

    suspend fun fetchModels(provider: ProviderConfig, apiKeyInput: String): Result<List<RemoteModel>> =
        withContext(Dispatchers.IO) {
            val key = resolveKey(provider.id, apiKeyInput)
            if (key.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Enter an API key first."))
            }
            runCatching {
                llmClient.listModels(
                    config = provider,
                    apiKey = key,
                    allowInsecureEndpoints = settingsRepository.current().allowInsecureEndpoints,
                )
            }
        }

    suspend fun testConnection(provider: ProviderConfig, apiKeyInput: String): ConnectionTestResult =
        withContext(Dispatchers.IO) {
            val key = resolveKey(provider.id, apiKeyInput)
            val settings = settingsRepository.current()
            if (key.isBlank()) {
                ConnectionTestResult(false, "Enter an API key first.")
            } else {
                llmClient.testConnection(provider, key, settings.allowInsecureEndpoints)
            }
        }

    private fun resolveKey(providerId: String, input: String): String =
        input.trim().ifBlank { credentials.getApiKey(providerId).orEmpty() }

    fun deleteAllHistory() {
        viewModelScope.launch {
            taskRepository.deleteAll()
            emit("All task history deleted.")
        }
    }

    suspend fun saveApiKey(providerId: String, value: String): Boolean {
        if (value.isBlank()) return false
        return withContext(Dispatchers.IO) {
            runCatching { credentials.setApiKey(providerId, value.trim()) }.fold(
                onSuccess = {
                    _credentialRevision.value += 1
                    emit("API key saved securely.")
                    true
                },
                onFailure = { emit("Could not save the API key."); false },
            )
        }
    }

    suspend fun removeApiKey(providerId: String) {
        withContext(Dispatchers.IO) { credentials.deleteApiKey(providerId) }
        _credentialRevision.value += 1
        emit("API key removed.")
    }

    fun deleteAllCredentials() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                settingsRepository.current().providerConfigs.forEach { credentials.deleteApiKey(it.id) }
            }
            _credentialRevision.value = _credentialRevision.value + 1
            emit("All saved API keys deleted.")
        }
    }

    fun clearLogs() {
        com.piko.agent.agent.AgentRuntime.clearLogs()
    }

    fun taskWithSteps(taskId: String) = taskRepository.observeTaskWithSteps(taskId)

    fun observeTask(taskId: String) = taskRepository.observeTask(taskId)

    fun observeAllTasks() = taskRepository.observeAll()

    fun emit(message: String) {
        _messages.tryEmit(message)
    }

    private fun uniqueId(base: String, providers: List<ProviderConfig>): String {
        if (providers.none { it.id == base }) return base
        var index = 2
        while (providers.any { it.id == "$base-$index" }) index++
        return "$base-$index"
    }
}
