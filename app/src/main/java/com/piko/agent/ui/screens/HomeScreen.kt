package com.piko.agent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.core.AgentState
import com.piko.agent.data.db.TaskEntity
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.EmptyState
import com.piko.agent.ui.components.MetricCard
import com.piko.agent.ui.components.PikoCard
import com.piko.agent.ui.components.PikoMark
import com.piko.agent.ui.components.SectionHeader
import com.piko.agent.ui.components.StatePill
import com.piko.agent.ui.components.StatusPill
import com.piko.agent.ui.components.formatDuration
import com.piko.agent.ui.components.formatTimestamp
import com.piko.agent.ui.components.stateLabel
import com.piko.agent.ui.theme.PikoAmber
import com.piko.agent.ui.theme.PikoLime
import com.piko.agent.ui.theme.PikoMint
import com.piko.agent.ui.theme.PikoMuted
import com.piko.agent.ui.theme.PikoRed
import com.piko.agent.ui.theme.PikoViolet
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: PikoViewModel,
    onStartTask: () -> Unit,
    onOpenTask: () -> Unit,
    onOpenHistoryTask: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    val home by viewModel.homeState.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var goal by rememberSaveable { mutableStateOf("") }
    val active = runtime.state.isRunning || runtime.state == AgentState.PAUSED
    val hasModel = home.selectedProvider?.selectedModel != null && home.hasSelectedKey
    val examples = listOf(
        "Open Settings and turn on dark mode",
        "Open Calculator and calculate 48 × 7",
        "Open Chrome and search for the weather today",
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PikoMark(size = 44)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Piko",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "An AI agent that operates your phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                StatusPill(
                    label = if (active) "Active" else "Personal",
                    color = if (active) PikoLime else PikoMuted,
                )
            }
        }

        item {
            GoalComposer(
                goal = goal,
                onGoalChange = { goal = it },
                active = active,
                canStart = hasModel && home.permissions.accessibilityEnabled,
                notificationsAllowed = home.permissions.notificationsAllowed,
                onRequestNotifications = onRequestNotifications,
                onStart = {
                    if (viewModel.startTask(goal)) {
                        if (!home.permissions.notificationsAllowed) onRequestNotifications()
                        onStartTask()
                    }
                },
                onOpenTask = onOpenTask,
            )
        }

        if (active || runtime.taskId != null) {
            item {
                ActiveTaskCard(
                    state = runtime.state,
                    goal = runtime.goal,
                    action = runtime.currentAction,
                    step = runtime.step,
                    maxSteps = runtime.maxSteps,
                    app = runtime.currentApp,
                    pauseReason = runtime.pauseReason,
                    elapsed = runtime.elapsedMs,
                    onOpen = onOpenTask,
                    onPauseResume = { if (runtime.state == AgentState.PAUSED) viewModel.resume() else viewModel.pause() },
                    onStop = { viewModel.stop() },
                )
            }
        }

        if (!home.permissions.accessibilityEnabled) {
            item {
                SetupBanner(
                    icon = Icons.Filled.AccessibilityNew,
                    title = "Accessibility is off",
                    body = "Piko needs the accessibility service to see and operate apps on this device.",
                    action = "Enable",
                    onAction = onOpenAccessibility,
                    color = PikoAmber,
                )
            }
        }
        if (!home.permissions.overlayAllowed) {
            item {
                SetupBanner(
                    icon = Icons.Filled.NotificationsActive,
                    title = "Floating controls are optional",
                    body = "Allow the overlay to watch and control Piko while it operates other apps.",
                    action = "Allow",
                    onAction = onOpenOverlay,
                    color = PikoViolet,
                )
            }
        }
        if (!hasModel) {
            item {
                SetupBanner(
                    icon = Icons.Filled.SmartToy,
                    title = "Add an AI model",
                    body = "Connect OpenCode Go or another provider and save an API key to start.",
                    action = "Models",
                    onAction = onOpenModels,
                    color = PikoViolet,
                )
            }
        }

        if (runtime.state == AgentState.PAUSED && runtime.pauseReason?.contains("interface", ignoreCase = true) == true) {
            item {
                SetupBanner(
                    icon = Icons.Filled.WarningAmber,
                    title = "Piko is waiting for the screen",
                    body = "Opening Piko pauses the agent. Press Home or switch to another app; the task resumes automatically.",
                    action = "Home",
                    onAction = {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_HOME)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    color = PikoAmber,
                )
            }
        }

        item {
            SectionHeader(
                title = "Capabilities",
                subtitle = "Everything Piko needs for a task",
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CapabilityTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.AccessibilityNew,
                    title = "Control",
                    value = if (home.permissions.accessibilityEnabled) "Ready" else "Off",
                    ok = home.permissions.accessibilityEnabled,
                    onClick = onOpenAccessibility,
                )
                CapabilityTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.PhotoCamera,
                    title = "Screen",
                    value = when {
                        home.permissions.projectionActive -> "Capture"
                        home.permissions.accessibilityEnabled -> "Ready"
                        else -> "Off"
                    },
                    ok = home.permissions.screenshotsAvailable,
                    onClick = onOpenAccessibility,
                )
                CapabilityTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.SmartToy,
                    title = "Model",
                    value = home.selectedProvider?.selectedModel?.label?.take(14) ?: "None",
                    ok = hasModel,
                    onClick = onOpenModels,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(
                    title = "Selected model",
                    subtitle = home.selectedProvider?.let { provider ->
                        "${provider.name} · ${home.selectedProvider?.selectedModel?.id ?: "no model"}"
                    } ?: "No provider",
                )
                TextButton(onClick = onOpenModels) {
                    Text("Change")
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }

        item {
            PikoCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(listOf(PikoViolet.copy(alpha = 0.9f), PikoLime.copy(alpha = 0.8f))),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.SmartToy, contentDescription = null, tint = Color.White)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            home.selectedProvider?.selectedModel?.label ?: "No model selected",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (home.hasSelectedKey) "API key saved securely" else "API key required",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (home.hasSelectedKey) PikoMint else PikoAmber,
                        )
                    }
                    StatusPill(
                        label = if (home.hasSelectedKey) "Ready" else "Setup",
                        color = if (home.hasSelectedKey) PikoMint else PikoAmber,
                    )
                }
            }
        }

        item {
            SectionHeader(
                title = "Recent tasks",
                subtitle = if (home.recentTasks.isEmpty()) "Your task history will appear here" else "Tap a task for its trace",
            )
        }
        if (home.recentTasks.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = "No tasks yet",
                    body = "Try one of the examples above. Piko will show every action it takes.",
                )
            }
        } else {
            items(home.recentTasks, key = { it.id }) { task ->
                RecentTaskRow(task = task, onClick = { onOpenHistoryTask(task.id) })
            }
        }

        item {
            Text(
                "While a task runs, Piko sends a structured description of the visible screen to your selected model. Passwords and credential fields are redacted. Piko pauses when the device locks.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun GoalComposer(
    goal: String,
    onGoalChange: (String) -> Unit,
    active: Boolean,
    canStart: Boolean,
    notificationsAllowed: Boolean,
    onRequestNotifications: () -> Unit,
    onStart: () -> Unit,
    onOpenTask: () -> Unit,
) {
    val examples = listOf(
        "Open Settings and turn on dark mode",
        "Open Calculator and calculate 48 × 7",
        "Open Chrome and search for the weather today",
    )
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, PikoLime.copy(alpha = 0.3f)),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "What should I do?",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusPill("On-device control", PikoLime)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = goal,
                onValueChange = { if (it.length <= 2000) onGoalChange(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("e.g. Open Settings and turn on dark mode", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                minLines = 3,
                maxLines = 6,
                shape = MaterialTheme.shapes.large,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PikoLime.copy(alpha = 0.65f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                ),
                enabled = !active,
            )
            Spacer(Modifier.height(10.dp))
            if (!notificationsAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = PikoAmber,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Enable notifications for persistent task controls",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRequestNotifications) { Text("Enable") }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (active) {
                Button(
                    onClick = onOpenTask,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("View active task", style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = MaterialTheme.shapes.large,
                    enabled = goal.isNotBlank() && canStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PikoLime,
                        contentColor = Color(0xFF141A00),
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text("Start task", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(examples) { example ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        onClick = { if (!active) onGoalChange(example) },
                    ) {
                        Text(
                            example,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Piko asks for approval before sending messages, paying, deleting data, installing apps, or changing security settings.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActiveTaskCard(
    state: AgentState,
    goal: String,
    action: String?,
    step: Int,
    maxSteps: Int,
    app: String?,
    pauseReason: String?,
    elapsed: Long,
    onOpen: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, PikoLime.copy(alpha = 0.45f)),
        onClick = onOpen,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatePill(state)
                Spacer(Modifier.weight(1f))
                Text(
                    if (maxSteps > 0) "Step $step/$maxSteps" else "Step $step",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    " · ${formatDuration(elapsed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(goal, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                action ?: pauseReason ?: app ?: stateLabel(state),
                style = MaterialTheme.typography.bodySmall,
                color = if (state == AgentState.PAUSED) PikoAmber else PikoLime,
                modifier = Modifier.padding(top = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (state == AgentState.PAUSED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    pauseReason ?: "Task is paused.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isTerminal) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        onClick = onPauseResume,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (state == AgentState.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(if (state == AgentState.PAUSED) "Resume" else "Pause", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = PikoRed.copy(alpha = 0.15f),
                        onClick = onStop,
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Stop, contentDescription = null, tint = PikoRed, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Stop", style = MaterialTheme.typography.labelMedium, color = PikoRed)
                        }
                    }
                } else {
                    Text(
                        if (state == AgentState.COMPLETED) "Finished" else "Ended",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = "Open task", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SetupBanner(
    icon: ImageVector,
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    color: Color,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun CapabilityTile(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    ok: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (ok) PikoMint.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (ok) PikoMint else PikoAmber,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(6.dp).clip(CircleShape)
                        .background(if (ok) PikoMint else PikoAmber),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentTaskRow(task: TaskEntity, onClick: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        onClick = onClick,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        when (task.status) {
                            AgentState.COMPLETED.name -> PikoMint.copy(alpha = 0.15f)
                            AgentState.FAILED.name, AgentState.CANCELLED.name -> PikoRed.copy(alpha = 0.12f)
                            else -> PikoLime.copy(alpha = 0.12f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (task.status) {
                        AgentState.COMPLETED.name -> Icons.Filled.ChevronRight
                        AgentState.FAILED.name -> Icons.Filled.ErrorOutline
                        else -> Icons.Filled.History
                    },
                    contentDescription = null,
                    tint = when (task.status) {
                        AgentState.COMPLETED.name -> PikoMint
                        AgentState.FAILED.name, AgentState.CANCELLED.name -> PikoRed
                        else -> PikoLime
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.goal,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatTimestamp(task.startedAt)} · ${task.stepCount} steps · ${task.modelName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(
                label = task.status.lowercase().replaceFirstChar { it.uppercase() },
                color = when (task.status) {
                    AgentState.COMPLETED.name -> PikoMint
                    AgentState.FAILED.name, AgentState.CANCELLED.name -> PikoRed
                    else -> PikoAmber
                },
            )
        }
    }
}
