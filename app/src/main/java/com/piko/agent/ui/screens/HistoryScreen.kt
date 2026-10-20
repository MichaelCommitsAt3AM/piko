package com.piko.agent.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.core.AgentState
import com.piko.agent.data.db.TaskEntity
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.EmptyState
import com.piko.agent.ui.components.MetricCard
import com.piko.agent.ui.components.SectionHeader
import com.piko.agent.ui.components.StatusPill
import com.piko.agent.ui.components.formatDuration
import com.piko.agent.ui.components.formatTimestamp
import com.piko.agent.ui.components.formatUsd
import com.piko.agent.ui.theme.PikoAmber
import com.piko.agent.ui.theme.PikoMint
import com.piko.agent.ui.theme.PikoMuted
import com.piko.agent.ui.theme.PikoRed

@Composable
fun HistoryScreen(
    viewModel: PikoViewModel,
    onOpenTask: (String) -> Unit,
) {
    val tasks by viewModel.observeAllTasks().collectAsStateWithLifecycle(initialValue = emptyList())
    var filter by remember { mutableStateOf("All") }
    var deleteDialog by remember { mutableStateOf(false) }
    val filters = listOf("All", "Completed", "Failed")
    val filtered = when (filter) {
        "Completed" -> tasks.filter { it.status == AgentState.COMPLETED.name }
        "Failed" -> tasks.filter { it.status == AgentState.FAILED.name || it.status == AgentState.CANCELLED.name }
        else -> tasks
    }
    val completed = tasks.count { it.status == AgentState.COMPLETED.name }
    val totalCost = tasks.sumOf { it.estimatedCostUsd }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("History", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "${tasks.size} tasks · ${tasks.sumOf { it.stepCount }} steps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (tasks.isNotEmpty()) {
                    TextButton(onClick = { deleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(17.dp), tint = PikoRed)
                        Spacer(Modifier.width(4.dp))
                        Text("Delete all", color = PikoRed)
                    }
                }
            }
        }

        if (tasks.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricCard(
                        value = if (tasks.isEmpty()) "—" else "${completed * 100 / tasks.size}%",
                        label = "Completion rate",
                        modifier = Modifier.weight(1f),
                        accent = PikoMint,
                    )
                    MetricCard(
                        value = "${tasks.sumOf { it.stepCount }}",
                        label = "Total steps",
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        value = formatUsd(totalCost),
                        label = "Est. cost",
                        modifier = Modifier.weight(1f),
                        accent = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { option ->
                    Surface(
                        shape = CircleShape,
                        color = if (filter == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        onClick = { filter = option },
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (filter == option) Color(0xFF141A00) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Filled.History,
                    title = if (tasks.isEmpty()) "No task history" else "No matching tasks",
                    body = if (tasks.isEmpty()) {
                        "Completed and failed tasks appear here with their execution traces. Disable history in Settings to keep nothing."
                    } else {
                        "Try a different filter."
                    },
                )
            }
        } else {
            items(filtered, key = { it.id }) { task ->
                HistoryCard(task, onClick = { onOpenTask(task.id) })
            }
        }

        if (tasks.isNotEmpty()) {
            item {
                Text(
                    "History is stored locally in Piko's database. Retention is controlled in Settings. Screenshots are never persisted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Delete all task history?") },
            text = { Text("This permanently removes every task and its execution trace from this device. API keys and settings are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllHistory()
                    deleteDialog = false
                }) { Text("Delete", color = PikoRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HistoryCard(task: TaskEntity, onClick: () -> Unit) {
    val status = runCatching { AgentState.valueOf(task.status) }.getOrDefault(AgentState.FAILED)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        onClick = onClick,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (status == AgentState.COMPLETED) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = when (status) {
                        AgentState.COMPLETED -> PikoMint
                        AgentState.CANCELLED -> PikoAmber
                        else -> PikoRed
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    task.goal,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                StatusPill(
                    label = when (status) {
                        AgentState.COMPLETED -> if (task.verified == true) "Verified" else "Completed"
                        AgentState.CANCELLED -> "Stopped"
                        else -> "Failed"
                    },
                    color = when (status) {
                        AgentState.COMPLETED -> if (task.verified == true) PikoMint else PikoAmber
                        AgentState.CANCELLED -> PikoAmber
                        else -> PikoRed
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${formatTimestamp(task.startedAt)} · ${formatDuration(task.durationMs)} · ${task.stepCount} steps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    task.modelName,
                    style = MaterialTheme.typography.labelSmall,
                    color = PikoMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!task.error.isNullOrBlank()) {
                Text(
                    task.error!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = PikoRed,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
