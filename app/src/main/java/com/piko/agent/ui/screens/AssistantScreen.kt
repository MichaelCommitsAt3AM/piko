package com.piko.agent.ui.screens

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.PikoApplication
import com.piko.agent.VoiceInputActivity
import com.piko.agent.core.AgentState
import com.piko.agent.core.PikoJson
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.PikoCard
import com.piko.agent.ui.components.StatusPill
import com.piko.agent.ui.theme.PikoAmber
import com.piko.agent.ui.theme.PikoMint
import com.piko.agent.ui.theme.PikoMuted
import com.piko.agent.workflow.WorkflowStep
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun AssistantScreen(
    viewModel: PikoViewModel,
    onOpenMenu: () -> Unit,
) {
    val context = LocalContext.current
    val app = PikoApplication.from(context)
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val workflows by viewModel.workflows.collectAsStateWithLifecycle()
    val schedules by viewModel.schedules.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    var section by remember { mutableStateOf("Voice & access") }
    var memoryKey by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Preference") }
    var expiryDays by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var clearDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var workflowText by remember { mutableStateOf("") }
    var workflowId by remember { mutableStateOf<String?>(null) }
    var advanced by remember { mutableStateOf(false) }
    var variableWorkflow by remember { mutableStateOf<com.piko.agent.data.db.WorkflowEntity?>(null) }
    var variableValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var scheduleFor by remember { mutableStateOf<String?>(null) }
    var hour by remember { mutableStateOf("9") }
    var minute by remember { mutableStateOf("0") }
    var recurrence by remember { mutableStateOf("daily") }
    var weekday by remember { mutableStateOf("1") }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshPermissions() }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { viewModel.refreshPermissions() }
    fun launch(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { viewModel.emit("This settings screen is unavailable on this device.") }
    }
    val filteredMemories = memories.filter { "$it".contains(search, ignoreCase = true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Assistant", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Voice, memory, and workflows.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Voice & access", "Memory", "Workflows").forEach { label ->
                    FilterChip(
                        selected = section == label,
                        onClick = { section = label },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        if (section == "Voice & access") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ToggleRow(
                            title = "Floating assistant while idle",
                            subtitle = "Keep Piko available above other apps.",
                            checked = settings.floatingAssistantEnabled,
                            onCheckedChange = { value ->
                                viewModel.updateSettings { it.copy(floatingAssistantEnabled = value) }
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Talk, type, or ask about your screen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Add the Talk to Piko tile from Android Quick Settings, then open it over the target app. " +
                                "Screen questions ask for consent naming ${settings.selectedProvider?.name ?: "your provider"}; " +
                                "actions use the same task controller.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { VoiceInputActivity.open(context) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Talk or type")
                        }
                    }
                }
            }
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Permissions", style = MaterialTheme.typography.titleMedium)
                        PermissionRow(
                            title = "Microphone",
                            granted = permissions.microphoneAllowed,
                            onRequest = { request.launch(Manifest.permission.RECORD_AUDIO) },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PermissionRow(
                            title = "Camera (flashlight)",
                            granted = permissions.cameraAllowed,
                            onRequest = { request.launch(Manifest.permission.CAMERA) },
                        )
                    }
                }
            }
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Default assistant", style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                label = if (permissions.assistantSelected) "Selected" else "Not selected",
                                color = if (permissions.assistantSelected) PikoMint else PikoAmber,
                            )
                        }
                        Text(
                            "Select Piko, then use your device's assistant gesture or button. Selection is voluntary; " +
                                "availability and gestures vary by manufacturer. Assistant selection does not grant screen " +
                                "or microphone access.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = {
                                val manager = context.getSystemService(RoleManager::class.java)
                                if (manager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                                    role.launch(manager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                                } else {
                                    launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Select default assistant")
                        }
                        OutlinedButton(
                            onClick = { launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Assistant settings")
                        }
                    }
                }
            }
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Wake phrase", style = MaterialTheme.typography.titleMedium)
                        Text("Hey Piko", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Always-on activation is unavailable in this build. A licensed, trained local detector is " +
                                "required. No background microphone is running. Use the tile, gesture, notification or " +
                                "voice button.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = settings.recognitionLanguage,
                            onValueChange = { value ->
                                viewModel.updateSettings { it.copy(recognitionLanguage = value) }
                            },
                            label = { Text("Speech language (BCP-47, e.g. en-US)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(
                            title = "Spoken answers and verified results",
                            subtitle = "Read responses aloud when speech output is available.",
                            checked = settings.spokenResponses,
                            onCheckedChange = { value ->
                                viewModel.updateSettings { it.copy(spokenResponses = value) }
                                if (!value) app.speechOutput.stop()
                            },
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Speech rate", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "%.1f".format(settings.speechRate),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Slider(
                                value = settings.speechRate,
                                onValueChange = { value ->
                                    viewModel.updateSettings { it.copy(speechRate = value) }
                                },
                                valueRange = 0.5f..2f,
                            )
                        }
                        val voices by app.speechOutput.voices.collectAsStateWithLifecycle()
                        var showVoices by remember { mutableStateOf(false) }
                        OutlinedButton(
                            onClick = { showVoices = !showVoices },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Voice: ${settings.speechVoice ?: "system language default"}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (showVoices) {
                            TextButton(
                                onClick = {
                                    viewModel.updateSettings { it.copy(speechVoice = null) }
                                    showVoices = false
                                },
                            ) { Text("System language default") }
                            voices.take(40).forEach { voice ->
                                TextButton(
                                    onClick = {
                                        viewModel.updateSettings { it.copy(speechVoice = voice) }
                                        showVoices = false
                                    },
                                ) { Text(voice) }
                            }
                        }
                        val speechStatus by app.speechOutput.status.collectAsStateWithLifecycle()
                        if (speechStatus.isNotBlank()) {
                            Text(
                                speechStatus,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { app.speechOutput.stop() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Stop speaking") }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ToggleRow(
                            title = "Permit screenshots with supported models",
                            subtitle = "Only share screen images when the selected model supports them.",
                            checked = settings.sendScreenshots,
                            onCheckedChange = { value ->
                                viewModel.updateSettings { it.copy(sendScreenshots = value) }
                            },
                        )
                    }
                }
            }
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Media and capabilities", style = MaterialTheme.typography.titleMedium)
                        CapabilityRow("Media session access", permissions.notificationAccess)
                        OutlinedButton(
                            onClick = { launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Media notification access") }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Capabilities", style = MaterialTheme.typography.titleMedium)
                        CapabilityRow("Accessibility", permissions.accessibilityConnected)
                        CapabilityRow("Overlay", permissions.overlayAllowed)
                        CapabilityRow("Notifications", permissions.notificationsAllowed)
                        CapabilityRow("Screen projection", permissions.projectionActive)
                        Text(
                            "Accessibility screenshots require accessibility; MediaProjection is optional and " +
                                "session-scoped. Calendar and navigation tools open the system app, so Piko does not " +
                                "need calendar or location access. Protected system settings open their settings screen. " +
                                "Shizuku is not installed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = { launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Accessibility settings") }
                        OutlinedButton(
                            onClick = {
                                launch(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Overlay settings") }
                        OutlinedButton(
                            onClick = {
                                launch(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Notification settings") }
                        OutlinedButton(
                            onClick = {
                                launch(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("App permissions") }
                    }
                }
            }
        }

        if (section == "Memory") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("About memory", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Approved preferences stay on this device. Up to five relevant memories are included in " +
                                "requests to the configured model. No synchronization or raw conversation storage. " +
                                "Use the same key to replace a conflicting preference.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ToggleRow(
                            title = "Allow model memory suggestions",
                            subtitle = "Suggestions require your approval before they are saved.",
                            checked = settings.memorySuggestions,
                            onCheckedChange = { value ->
                                viewModel.updateSettings { it.copy(memorySuggestions = value) }
                            },
                        )
                        TextButton(onClick = { com.piko.agent.assistant.AssistantConversation.clear() }) {
                            Text("Clear in-memory conversation")
                        }
                    }
                }
            }
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Save a preference", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = memoryKey,
                            onValueChange = { memoryKey = it.take(120) },
                            label = { Text("Memory key, e.g. music app") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it.take(1000) },
                            label = { Text("Non-sensitive preference") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = category,
                            onValueChange = { category = it.take(64) },
                            label = { Text("Category") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = expiryDays,
                            onValueChange = { expiryDays = it.filter(Char::isDigit).take(4) },
                            label = { Text("Expire after days (optional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                viewModel.saveMemory(
                                    memoryKey,
                                    content,
                                    category,
                                    expiresAt = expiryDays.toLongOrNull()?.let {
                                        System.currentTimeMillis() + it * 86_400_000L
                                    },
                                )
                            },
                            enabled = memoryKey.isNotBlank() && content.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Approve and save memory") }
                        Text(
                            "You can also type: Remember music app = I prefer Spotify. To delete: Forget music app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search memories") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (filteredMemories.isEmpty()) {
                item {
                    Text(
                        if (memories.isEmpty()) "No memories saved yet." else "No memories match your search.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(filteredMemories, key = { it.id }) { memory ->
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(memory.memoryKey, style = MaterialTheme.typography.titleMedium)
                            StatusPill(
                                label = if (memory.approved) "Approved" else "Pending",
                                color = if (memory.approved) PikoMint else PikoAmber,
                            )
                        }
                        Text(memory.content)
                        Text(
                            "${memory.category} · ${memory.source}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        memory.expiresAt?.let {
                            Text(
                                "Expires: ${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row {
                            TextButton(
                                onClick = {
                                    memoryKey = memory.memoryKey
                                    content = memory.content
                                    category = memory.category
                                },
                            ) { Text("Edit") }
                            if (!memory.approved) {
                                TextButton(
                                    onClick = {
                                        viewModel.saveMemory(
                                            memory.memoryKey,
                                            memory.content,
                                            memory.category,
                                            true,
                                            memory.expiresAt,
                                        )
                                    },
                                ) { Text("Approve") }
                            }
                            TextButton(onClick = { viewModel.deleteMemory(memory.id) }) { Text("Delete") }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { clearDialog = true }) {
                    Text("Clear all memories", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (section == "Workflows") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Text(
                        "Review reusable instructions or semantic steps before saving. Coordinates are never replayed. " +
                            "Every action retains Piko's verification and confirmation gates. Execution history appears " +
                            "in History, with one entry per executed step.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Create a workflow", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.take(120) },
                            label = { Text("Workflow name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it.take(2000) },
                            label = { Text("Description") },
                            minLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ToggleRow(
                            title = "Structured steps",
                            subtitle = "Use advanced JSON instead of one instruction per line.",
                            checked = advanced,
                            onCheckedChange = {
                                advanced = it
                                workflowText = ""
                            },
                        )
                        OutlinedTextField(
                            value = workflowText,
                            onValueChange = { workflowText = it.take(30000) },
                            label = { Text(if (advanced) "JSON array of steps" else "One AI instruction per line") },
                            minLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "Use {{variable_name}} in instructions or tool values to request a value each time you run.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (advanced) {
                            Text(
                                "Example: [{\"kind\":\"tool\",\"toolId\":\"media_volume\",\"parameters\":{\"percent\":30}}," +
                                    "{\"kind\":\"wait\",\"milliseconds\":500}]",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (runtime.state == AgentState.COMPLETED) {
                            TextButton(
                                onClick = {
                                    workflowText = PikoJson.encodeToString(
                                        kotlinx.serialization.builtins.ListSerializer(WorkflowStep.serializer()),
                                        com.piko.agent.workflow.ReusableExecutionTrace.draft(),
                                    )
                                    name = "Saved task"
                                    advanced = true
                                    workflowId = null
                                },
                            ) { Text("Review reusable steps from last successful task") }
                        }
                        Button(
                            onClick = {
                                runCatching {
                                    if (advanced) {
                                        PikoJson.decodeFromString<List<WorkflowStep>>(workflowText)
                                    } else {
                                        workflowText.lines()
                                            .filter { it.isNotBlank() }
                                            .map { WorkflowStep(instruction = it.trim()) }
                                    }
                                }
                                    .onSuccess { viewModel.saveWorkflow(name, description, it, workflowId) }
                                    .onFailure {
                                        viewModel.emit("Invalid workflow JSON: ${it.message?.take(120)}")
                                    }
                            },
                            enabled = name.isNotBlank() && workflowText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Review complete — save workflow") }
                        if (workflowId != null) {
                            TextButton(
                                onClick = {
                                    workflowId = null
                                    name = ""
                                    description = ""
                                    workflowText = ""
                                },
                            ) { Text("New workflow") }
                        }
                    }
                }
            }
            items(workflows, key = { it.id }) { workflow ->
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(workflow.name, style = MaterialTheme.typography.titleMedium)
                        if (workflow.description.isNotBlank()) Text(workflow.description)
                        Row {
                            TextButton(
                                onClick = {
                                    val steps = PikoJson.decodeFromString<List<WorkflowStep>>(workflow.stepsJson)
                                    val names = com.piko.agent.workflow.WorkflowVariables.names(steps)
                                    if (names.isNotEmpty()) {
                                        variableWorkflow = workflow
                                        variableValues = names.associateWith { "" }
                                    } else if (viewModel.runWorkflow(workflow)) {
                                        (context as? Activity)?.let { activity ->
                                            activity.startActivity(
                                                Intent(Intent.ACTION_MAIN)
                                                    .addCategory(Intent.CATEGORY_HOME)
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                    }
                                },
                                enabled = !runtime.isActive,
                            ) { Text("Run") }
                            TextButton(
                                onClick = {
                                    workflowId = workflow.id
                                    name = workflow.name
                                    description = workflow.description
                                    advanced = true
                                    workflowText = workflow.stepsJson
                                },
                            ) { Text("Edit") }
                            TextButton(onClick = { scheduleFor = workflow.id }) { Text("Schedule") }
                            TextButton(onClick = { viewModel.deleteWorkflow(workflow.id) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (scheduleFor != null) {
                item {
                    PikoCard(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Schedule a reminder", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Android may defer it; UI execution requires your interaction.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = hour,
                                    onValueChange = { hour = it.take(2) },
                                    label = { Text("Hour 0–23") },
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = minute,
                                    onValueChange = { minute = it.take(2) },
                                    label = { Text("Minute") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("once", "daily", "weekly").forEach { value ->
                                    FilterChip(
                                        selected = recurrence == value,
                                        onClick = { recurrence = value },
                                        label = { Text(value) },
                                    )
                                }
                            }
                            if (recurrence == "weekly") {
                                OutlinedTextField(
                                    value = weekday,
                                    onValueChange = { weekday = it.take(1) },
                                    label = { Text("Weekday: Monday=1 … Sunday=7") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Button(
                                onClick = {
                                    viewModel.scheduleWorkflow(
                                        scheduleFor!!,
                                        hour.toIntOrNull() ?: -1,
                                        minute.toIntOrNull() ?: -1,
                                        recurrence,
                                        weekday.toIntOrNull() ?: -1,
                                    )
                                    scheduleFor = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Save reminder") }
                        }
                    }
                }
            }
            items(schedules, key = { it.id }) { schedule ->
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val time = Instant.ofEpochMilli(schedule.nextRunAt)
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("EEE d MMM, HH:mm z"))
                        Text(
                            "${workflows.firstOrNull { it.id == schedule.workflowId }?.name ?: "Deleted workflow"}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${schedule.recurrence} · $time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            schedule.lastResult ?: "Not run yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ToggleRow(
                            title = "Reminder enabled",
                            subtitle = "Run this reminder on its schedule.",
                            checked = schedule.enabled,
                            onCheckedChange = { viewModel.toggleSchedule(schedule) },
                        )
                        TextButton(onClick = { viewModel.cancelSchedule(schedule.id) }) {
                            Text("Cancel schedule", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    variableWorkflow?.let { workflow ->
        AlertDialog(
            onDismissRequest = { variableWorkflow = null },
            title = { Text("Run ${workflow.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Values are used for this run. Review sensitive actions in their confirmation prompts.")
                    variableValues.forEach { (key, value) ->
                        OutlinedTextField(
                            value = value,
                            onValueChange = { variableValues = variableValues + (key to it.take(4000)) },
                            label = { Text(key) },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = variableValues.values.all { it.isNotBlank() },
                    onClick = {
                        if (viewModel.runWorkflow(workflow, variableValues)) {
                            variableWorkflow = null
                            context.startActivity(
                                Intent(Intent.ACTION_MAIN)
                                    .addCategory(Intent.CATEGORY_HOME)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { variableWorkflow = null }) { Text("Cancel") } },
        )
    }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("Delete all memories?") },
            text = { Text("This permanently removes all locally stored memories.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearMemories()
                        clearDialog = false
                    },
                ) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (granted) "Granted" else "Not granted",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) PikoMint else PikoMuted,
            )
        }
        OutlinedButton(onClick = onRequest, enabled = !granted) {
            Text(if (granted) "Granted" else "Allow")
        }
    }
}

@Composable
private fun CapabilityRow(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        StatusPill(
            label = if (enabled) "Available" else "Off",
            color = if (enabled) PikoMint else PikoMuted,
        )
    }
}
