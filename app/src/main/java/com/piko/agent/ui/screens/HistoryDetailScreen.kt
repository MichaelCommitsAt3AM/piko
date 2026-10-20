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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.ai.ObservationFormatter
import com.piko.agent.core.AgentAction
import com.piko.agent.core.AgentState
import com.piko.agent.core.PikoJson
import com.piko.agent.data.db.TaskStepEntity
import com.piko.agent.data.db.TaskWithSteps
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.EmptyState
import com.piko.agent.ui.components.MetricCard
import com.piko.agent.ui.components.PikoCard
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
fun HistoryDetailScreen(
    viewModel: PikoViewModel,
    taskId: String,
    onBack: () -> Unit,
) {
    val flow = remember(taskId) { viewModel.taskWithSteps(taskId) }
    val data by flow.collectAsStateWithLifecycle(initialValue = null)

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Task trace", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Stored locally on this device",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val current = data
        if (current == null) {
            EmptyState(
                icon = Icons.Filled.History,
                title = "Loading task",
                body = "If this task was deleted, it is no longer available.",
                modifier = Modifier.weight(1f),
            )
        } else {
            TaskTrace(current)
        }
    }
}

@Composable
private fun TaskTrace(data: TaskWithSteps) {
    val task = data.task
    val status = runCatching { AgentState.valueOf(task.status) }.getOrDefault(AgentState.FAILED)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(
                    1.dp,
                    when (status) {
                        AgentState.COMPLETED -> PikoMint.copy(alpha = 0.4f)
                        AgentState.FAILED -> PikoRed.copy(alpha = 0.4f)
                        else -> PikoAmber.copy(alpha = 0.4f)
                    },
                ),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (status) {
                                AgentState.COMPLETED -> Icons.Filled.CheckCircle
                                AgentState.FAILED -> Icons.Filled.ErrorOutline
                                else -> Icons.Filled.WarningAmber
                            },
                            contentDescription = null,
                            tint = when (status) {
                                AgentState.COMPLETED -> PikoMint
                                AgentState.FAILED -> PikoRed
                                else -> PikoAmber
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (status) {
                                AgentState.COMPLETED -> "Completed"
                                AgentState.CANCELLED -> "Cancelled"
                                AgentState.FAILED -> "Failed"
                                else -> status.name.lowercase().replaceFirstChar { it.uppercase() }
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        StatusPill(
                            label = when (task.verified) {
                                true -> "Model-verified"
                                false -> "Unverified"
                                null -> "No claim"
                            },
                            color = if (task.verified == true) PikoMint else PikoAmber,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(task.goal, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatTimestamp(task.startedAt)} · ${formatDuration(task.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!task.resultSummary.isNullOrBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(task.resultSummary!!, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!task.verification.isNullOrBlank()) {
                        Text(
                            "Evidence: ${task.verification}",
                            style = MaterialTheme.typography.labelSmall,
                            color = PikoMint,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (!task.error.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = PikoRed.copy(alpha = 0.1f),
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        ) {
                            Text(
                                task.error!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = PikoRed,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("${task.stepCount}", "Steps", Modifier.weight(1f))
                MetricCard(formatDuration(task.durationMs), "Duration", Modifier.weight(1f))
                MetricCard(formatUsd(task.estimatedCostUsd), "Est. cost", Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            }
        }

        item {
            PikoCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Model", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${task.providerName} · ${task.modelName}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricCard("${task.inputTokens}", "Input tokens", Modifier.weight(1f))
                        MetricCard("${task.outputTokens}", "Output tokens", Modifier.weight(1f))
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Execution trace",
                subtitle = "${data.steps.size} recorded steps",
            )
        }

        if (data.steps.isEmpty()) {
            item {
                Text(
                    "No detailed steps were stored. History may have been disabled while this task ran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(data.steps, key = { _, step -> step.id }) { index, step ->
                TraceStepRow(step = step, index = index, isLast = index == data.steps.lastIndex)
            }
        }

        item {
            Text(
                "This trace is stored in Piko's local Room database. Screenshots are never saved. Password and credential fields are redacted.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TraceStepRow(step: TaskStepEntity, index: Int, isLast: Boolean) {
    val action = step.actionJson?.let { raw ->
        runCatching { PikoJson.decodeFromString(AgentAction.serializer(), raw) }.getOrNull()
    }
    val state = runCatching { AgentState.valueOf(step.state) }.getOrDefault(AgentState.VERIFYING)
    val isError = step.result?.contains("failed", ignoreCase = true) == true ||
        step.result?.contains("rejected", ignoreCase = true) == true
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(26.dp)) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (!isLast) {
                Box(
                    Modifier
                        .padding(top = 5.dp)
                        .width(1.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (isError) PikoRed.copy(alpha = 0.07f) else MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                1.dp,
                if (isError) PikoRed.copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.weight(1f).padding(bottom = 8.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        action?.let { ObservationFormatter.actionBrief(it) } ?: (step.actionType?.lowercase() ?: "observation"),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isError) PikoRed else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StatusPill(step.state.lowercase().replace('_', ' '), PikoMuted)
                }
                step.result?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    step.appPackage?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = PikoMuted)
                    }
                    if (step.screenshotIncluded) {
                        Spacer(Modifier.width(8.dp))
                        StatusPill("Screenshot used", PikoAmber)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatTimestamp(step.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = PikoMuted,
                    )
                }
            }
        }
    }
}
