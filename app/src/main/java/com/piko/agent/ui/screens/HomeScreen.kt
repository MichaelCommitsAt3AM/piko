package com.piko.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.piko.agent.VoiceInputActivity
import com.piko.agent.service.AgentService
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.core.AgentState
import com.piko.agent.data.db.TaskEntity
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.StatePill
import com.piko.agent.ui.components.formatTimestamp

@Composable
fun HomeScreen(
    viewModel: PikoViewModel,
    onStartTask: (String?) -> Unit,
    onOpenTask: () -> Unit,
    onOpenHistoryTask: (String) -> Unit,
    onOpenModels: () -> Unit,
    onOpenAccessibility: () -> Unit,
) {
    val home by viewModel.homeState.collectAsStateWithLifecycle()
    val runtime by viewModel.runtime.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var instruction by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf("") }
    val active = runtime.state.isRunning || runtime.state == AgentState.PAUSED
    val hasModel = home.selectedProvider?.selectedModel != null && home.hasSelectedKey
    val ready = hasModel && home.permissions.accessibilityEnabled

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Piko", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenModels, modifier = Modifier.weight(1f)) {
                    Text(
                        home.selectedProvider?.selectedModel?.label ?: "Choose a model",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp))
                }
            }
        }

        if (active) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatePill(runtime.state)
                    Text(runtime.goal, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        runtime.pauseReason ?: runtime.currentAction ?: "Preparing your task…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onOpenTask, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(if (runtime.pendingConfirmation != null) "Review action" else "View task")
                    }
                    OutlinedTextField(
                        value = instruction, onValueChange = { instruction = it.take(2000) },
                        label = { Text("Add a task or change direction") },
                        modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { VoiceInputActivity.open(context) }) { Text("Talk") }
                        TextButton(onClick = {
                            runtime.taskId?.let { AgentService.instruct(context, it, instruction) }
                            instruction = ""
                        }, enabled = instruction.isNotBlank()) { Text("Send update") }
                    }
                    runtime.lastInstruction?.let { Text("You: $it", style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { if (runtime.state == AgentState.PAUSED) viewModel.resume() else viewModel.pause() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                if (runtime.state == AgentState.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                null, Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(if (runtime.state == AgentState.PAUSED) "Resume" else "Pause")
                        }
                        TextButton(onClick = { viewModel.stop() }, modifier = Modifier.weight(1f)) {
                            Text("Stop", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.height(16.dp))
                    Text("What should I do?", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "Give your phone a task.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = goal,
                        onValueChange = { goal = it.take(2000) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Your task") },
                        placeholder = { Text("Open Settings and turn on dark mode") },
                        minLines = 4,
                        maxLines = 8,
                        shape = MaterialTheme.shapes.medium,
                    )
                    Button(
                        onClick = {
                            val previousId = runtime.taskId
                            if (viewModel.startTask(goal)) onStartTask(previousId)
                        },
                        enabled = goal.isNotBlank() && ready,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text("Start task")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.ArrowUpward, null, Modifier.size(18.dp))
                    }
                    TextButton(onClick = { VoiceInputActivity.open(context) }) {
                        Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Use voice")
                    }
                    Text(
                        "Screen content is shared with your selected AI provider.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Show the next required setup step. Optional permissions live in Settings.
            if (!ready) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (!hasModel) "Connect a model" else "Enable device control",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    if (!hasModel) "Choose a provider and add your API key."
                                    else "Allow Piko to see and operate apps.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = if (!hasModel) onOpenModels else onOpenAccessibility) {
                                Text("Set up")
                            }
                        }
                    }
                }
            }
        }

        if (!active && runtime.taskId != null) {
            item {
                Surface(
                    onClick = onOpenTask,
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Last task", style = MaterialTheme.typography.labelMedium)
                            Text(
                                runtime.resultSummary ?: runtime.error ?: "Task ended",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = "View last task")
                    }
                }
            }
        }

        if (home.recentTasks.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        "Recent tasks",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(home.recentTasks.take(3), key = { it.id }) { task ->
                RecentTaskRow(task, onClick = { onOpenHistoryTask(task.id) })
            }
        }
    }
}

@Composable
private fun RecentTaskRow(task: TaskEntity, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.goal, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatTimestamp(task.startedAt)} · ${task.status.lowercase().replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
