package com.piko.agent.ui.screens

import com.piko.agent.VoiceInputActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Mic
import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.agent.AgentRuntimeState
import com.piko.agent.agent.TimelineEntry
import com.piko.agent.core.AgentState
import com.piko.agent.core.TokenUsage
import com.piko.agent.model.ScreenObservation
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.EmptyState
import com.piko.agent.ui.components.MetricCard
import com.piko.agent.ui.components.PikoCard
import com.piko.agent.ui.components.SectionHeader
import com.piko.agent.ui.components.StatePill
import com.piko.agent.ui.components.StatusPill
import com.piko.agent.ui.components.formatClock
import com.piko.agent.ui.components.formatDuration
import com.piko.agent.ui.components.formatUsd
import com.piko.agent.ui.components.stateColor
import com.piko.agent.ui.components.stateLabel
import com.piko.agent.ui.theme.PikoAmber
import com.piko.agent.ui.theme.PikoMint
import com.piko.agent.ui.theme.PikoMuted
import com.piko.agent.ui.theme.PikoRed

@Composable
fun TaskScreen(
    viewModel: PikoViewModel,
    onBack: () -> Unit,
    onNewTask: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.runtime.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showDetails by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("Live task", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.modelName.ifBlank { "Piko agent" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.isActive) {
                IconButton(onClick = { VoiceInputActivity.open(context) }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Talk to Piko")
                }
            }
            StatePill(state.state)
        }

        if (state.taskId == null) {
            EmptyState(
                icon = Icons.Filled.PlayArrow,
                title = "No task is running",
                body = "Start a task from the Home screen.",
                modifier = Modifier.weight(1f),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TaskSummary(state)
            }

            if (state.pendingConfirmation != null) {
                item {
                    ConfirmationCard(
                        summary = state.pendingConfirmation!!.summary,
                        reason = state.pendingConfirmation!!.reason,
                        category = state.pendingConfirmation!!.category,
                        actionDetail = state.pendingConfirmation!!.action?.let { action ->
                            buildString {
                                append(action.type.name.lowercase().replace('_', ' '))
                                action.elementId?.let { append(" · ").append(it) }
                                action.text?.let { append(" · \"").append(it.take(60)).append("\"") }
                                action.packageName?.let { append(" · ").append(it) }
                                action.appName?.let { append(" · ").append(it) }
                            }
                        },
                        onApprove = { viewModel.approve() },
                        onDeny = { viewModel.deny() },
                    )
                }
            }

            if (state.error != null) {
                item {
                    ErrorCard(
                        title = if (state.state == AgentState.FAILED) "Task failed" else "Notice",
                        message = state.error!!,
                    )
                }
            }

            if (state.state == AgentState.PAUSED) {
                item {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    PauseCard(
                        reason = state.pauseReason ?: "Piko is paused.",
                        showHandoff = state.pauseReason?.contains("interface", ignoreCase = true) == true ||
                            state.pauseReason?.contains("foreground", ignoreCase = true) == true,
                        onContinueInBackground = {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_MAIN)
                                    .addCategory(android.content.Intent.CATEGORY_HOME)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                }
            }

            if (state.state == AgentState.COMPLETED) {
                item {
                    CompletionCard(state)
                }
            }

            item {
                TextButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Hide details" else "Show details")
                }
            }
            if (showDetails) {
                if (state.screenshotPreviewJpeg != null) {
                    item { ScreenshotCard(state.screenshotPreviewJpeg!!) }
                }
                if (state.observation != null) {
                    item { ObservationCard(state.observation!!) }
                }
                item { UsageCard(state.usage) }
            }

            item {
                SectionHeader(
                    title = "Activity",
                    subtitle = "${state.timeline.size} events · step ${state.step}" +
                        if (state.maxSteps > 0) " of ${state.maxSteps}" else "",
                )
            }

            if (state.timeline.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.PlayArrow,
                        title = "Waiting for the first observation",
                        body = "Piko will show every action, verification result and recovery step here.",
                    )
                }
            } else {
                items(state.timeline.size, key = { state.timeline[it].id }) { index ->
                    TimelineRow(state.timeline[index], isLast = index == state.timeline.lastIndex)
                }
            }

        }

        TaskControls(
            state = state,
            onPauseResume = { if (state.state == AgentState.PAUSED) viewModel.resume() else viewModel.pause() },
            onStop = { viewModel.stop() },
            onNewTask = onNewTask,
        )
    }
}

@Composable
private fun TaskSummary(state: AgentRuntimeState) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(stateLabel(state.state), stateColor(state.state))
                Spacer(Modifier.weight(1f))
                Text(
                    formatDuration(state.elapsedMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(state.goal, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            if (state.state.isRunning || state.state == AgentState.PAUSED) {
                LinearProgressIndicator(
                    progress = {
                        if (state.maxSteps > 0) (state.step.toFloat() / state.maxSteps).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                "Step ${state.step} of ${state.maxSteps} · ${state.currentApp ?: "Waiting for an app"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.currentAction.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.state == AgentState.REASONING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(stateColor(state.state)))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        state.currentAction!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmationCard(
    summary: String,
    reason: String,
    category: String,
    actionDetail: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = PikoAmber.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, PikoAmber.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = PikoAmber)
                Spacer(Modifier.width(8.dp))
                Text("Your approval is required", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(summary, style = MaterialTheme.typography.bodyLarge)
            actionDetail?.let {
                Text(
                    "Action: $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = PikoAmber,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Category: ${category.lowercase().replace('_', ' ')}",
                style = MaterialTheme.typography.labelSmall,
                color = PikoAmber,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = PikoAmber, contentColor = Color.Black),
                ) { Text("Approve") }
                OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun PauseCard(
    reason: String,
    showHandoff: Boolean,
    onContinueInBackground: () -> Unit,
) {
    PikoCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Pause, contentDescription = null, tint = PikoAmber)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Paused", style = MaterialTheme.typography.titleMedium)
                    Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showHandoff) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onContinueInBackground,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Continue in background")
                }
                Text(
                    "Piko will resume automatically once its own interface is no longer in front.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CompletionCard(state: AgentRuntimeState) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = PikoMint.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, PikoMint.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = PikoMint)
                Spacer(Modifier.width(8.dp))
                Text("Task completed", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                StatusPill(
                    label = when (state.verified) {
                        true -> "Model-verified"
                        false -> "Unverified"
                        null -> "Reported"
                    },
                    color = if (state.verified == true) PikoMint else PikoAmber,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(state.resultSummary ?: "The agent reports completion.", style = MaterialTheme.typography.bodyLarge)
            state.verification?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(title: String, message: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = PikoRed.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, PikoRed.copy(alpha = 0.4f)),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = PikoRed)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ScreenshotCard(bytes: ByteArray) {
    val bitmap = remember(bytes) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
    }
    if (bitmap == null) return
    PikoCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Latest screenshot", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill("Not saved", PikoMuted)
            }
            Text(
                "Sent to the model only when the agent needs visual context. This image is held in memory.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Latest screen observation",
                modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

@Composable
private fun ObservationCard(observation: ScreenObservation) {
    PikoCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Live observation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (observation.secureScreen) StatusPill("Secure screen", PikoAmber)
            }
            Text(
                "${observation.appLabel ?: observation.foregroundPackage ?: "Unknown app"} · " +
                    "${observation.screenWidth}×${observation.screenHeight} · ${observation.elements.size} elements",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp, bottom = 8.dp),
            )
            if (observation.hasDialog) {
                StatusPill("Dialog detected", PikoAmber, modifier = Modifier.padding(bottom = 8.dp))
            }
            val labels = observation.elements.asSequence()
                .mapNotNull { element -> element.text ?: element.description }
                .filter { it.isNotBlank() && it != "[redacted]" }
                .distinct()
                .take(18)
                .toList()
            if (labels.isEmpty()) {
                Text("No meaningful text is visible.", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    labels.forEach { label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(PikoMuted))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            Text(
                "fingerprint ${observation.fingerprint.take(12)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry, isLast: Boolean) {
    val color = stateColor(entry.state)
    Row(Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(24.dp)) {
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .size(if (entry.isError) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (entry.isError) PikoRed else color),
            )
            if (!isLast) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .width(1.dp)
                        .height(38.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (entry.isError) PikoRed else MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatClock(entry.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.step > 0) {
                Text(
                    "Step ${entry.step}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun UsageCard(usage: TokenUsage) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(
            value = "${usage.inputTokens}",
            label = "Input tokens",
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = "${usage.outputTokens}",
            label = "Output tokens",
            modifier = Modifier.weight(1f),
        )
        MetricCard(
            value = formatUsd(usage.costUsd),
            label = "Est. cost",
            modifier = Modifier.weight(1f),
            accent = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun TaskControls(
    state: AgentRuntimeState,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onNewTask: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.state.isTerminal) {
                Button(
                    onClick = onNewTask,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("New task")
                }
                OutlinedButton(onClick = onStop, enabled = false) {
                    Text("Task ended")
                }
            } else {
                OutlinedButton(
                    onClick = onPauseResume,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    enabled = state.state.isRunning || state.state == AgentState.PAUSED,
                ) {
                    Icon(
                        if (state.state == AgentState.PAUSED) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.state == AgentState.PAUSED) "Resume" else "Pause")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(containerColor = PikoRed, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Stop")
                }
            }
        }
    }
}
