package com.piko.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.piko.agent.core.AgentState
import com.piko.agent.ui.theme.PikoAmber
import com.piko.agent.ui.theme.PikoMint
import com.piko.agent.ui.theme.PikoMuted
import com.piko.agent.ui.theme.PikoRed
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PikoBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

@Composable
fun PikoMark(modifier: Modifier = Modifier, size: Int = 40) {
    Box(
        modifier.size(size.dp).clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text("P", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun PikoWordmark(subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PikoMark(size = 44)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Piko",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun PikoCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        tonalElevation = 0.dp,
        content = { Box(Modifier.padding(18.dp)) { content() } },
    )
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Transparent,

    ) {
        Row(
            modifier = Modifier.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            } else {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun StatePill(state: AgentState) {
    StatusPill(
        label = stateLabel(state),
        color = stateColor(state),
    )
}

@Composable
fun PermissionTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    ok: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (ok) PikoMint.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (ok) PikoMint else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) PikoMint else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (ok) PikoMint else PikoAmber),
            )
        }
    }
}

@Composable
fun MetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onBackground,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = accent, maxLines = 1)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(36.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
        )
    }
}

fun stateLabel(state: AgentState): String = when (state) {
    AgentState.IDLE -> "Idle"
    AgentState.PREPARING -> "Preparing"
    AgentState.OBSERVING -> "Observing"
    AgentState.REASONING -> "Thinking"
    AgentState.EXECUTING -> "Acting"
    AgentState.VERIFYING -> "Verifying"
    AgentState.AWAITING_USER_CONFIRMATION -> "Needs approval"
    AgentState.PAUSED -> "Paused"
    AgentState.COMPLETED -> "Completed"
    AgentState.FAILED -> "Failed"
    AgentState.CANCELLED -> "Stopped"
}

@Composable
fun stateColor(state: AgentState): Color = when (state) {
    AgentState.IDLE -> PikoMuted
    AgentState.COMPLETED -> PikoMint
    AgentState.FAILED, AgentState.CANCELLED -> PikoRed
    AgentState.PAUSED, AgentState.AWAITING_USER_CONFIRMATION -> PikoAmber
    else -> MaterialTheme.colorScheme.primary
}

fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}

fun formatTimestamp(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
}

fun formatClock(epochMs: Long): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault())
    return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(formatter)
}

fun formatUsd(value: Double): String = when {
    value <= 0.0 -> "$0.00"
    value < 0.01 -> "<$0.01"
    else -> "$" + "%.2f".format(Locale.US, value)
}
