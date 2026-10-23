package com.piko.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.BuildConfig
import com.piko.agent.service.ProjectionService
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.PermissionTile
import com.piko.agent.ui.components.PikoCard
import com.piko.agent.ui.components.StatusPill
import com.piko.agent.ui.theme.PikoMint
import com.piko.agent.ui.theme.PikoMuted
import com.piko.agent.ui.theme.PikoRed
import com.piko.agent.ui.theme.PikoViolet
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    viewModel: PikoViewModel,
    onOpenMenu: () -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestProjection: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val permissions by viewModel.permissions.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val projectionActive by viewModel.projectionActive.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val credentialRevision by viewModel.credentialRevision.collectAsStateWithLifecycle()
    var deleteHistoryDialog by remember { mutableStateOf(false) }
    var deleteKeysDialog by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var expandedSection by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Settings", style = MaterialTheme.typography.headlineLarge)
                    Text("Make Piko yours.", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            SettingsSection(
                title = "API keys", subtitle = "Connect your providers securely",
                expanded = expandedSection == "API keys",
                onClick = { expandedSection = if (expandedSection == "API keys") null else "API keys" },
            )
        }
        if (expandedSection == "API keys") {
            settings.providerConfigs.forEach { provider ->
                item(key = "key-${provider.id}") {
                    ApiKeySetting(
                        providerName = provider.name,
                        hasKey = remember(provider.id, credentialRevision) { viewModel.hasApiKey(provider.id) },
                        onSave = { viewModel.saveApiKey(provider.id, it) },
                        onRemove = { viewModel.removeApiKey(provider.id) },
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = "Permissions", subtitle = "Device control, capture and notifications",
                expanded = expandedSection == "Permissions",
                onClick = { expandedSection = if (expandedSection == "Permissions") null else "Permissions" },
            )
        }
        if (expandedSection == "Permissions") {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    PermissionTile(
                        icon = Icons.Filled.AccessibilityNew,
                        title = "Accessibility control",
                        subtitle = when {
                            permissions.accessibilityEnabled && permissions.accessibilityConnected -> "Enabled and connected"
                            permissions.accessibilityEnabled -> "Enabled, connecting..."
                            else -> "Required to see and operate apps"
                        },
                        ok = permissions.accessibilityEnabled,
                        onClick = onOpenAccessibility,
                    )
                    PermissionTile(
                        icon = Icons.Filled.PhotoCamera,
                        title = "Screen capture",
                        subtitle = when {
                            projectionActive -> "MediaProjection active"
                            permissions.accessibilityEnabled -> "Accessibility screenshots available"
                            else -> "Grant optional MediaProjection access"
                        },
                        ok = permissions.screenshotsAvailable,
                        onClick = {
                            if (projectionActive) ProjectionService.stop(context) else onRequestProjection()
                        },
                    )
                    PermissionTile(
                        icon = Icons.Filled.Layers,
                        title = "Floating overlay",
                        subtitle = if (permissions.overlayAllowed) "Allowed" else "Optional; notification controls remain",
                        ok = permissions.overlayAllowed,
                        onClick = onOpenOverlay,
                    )
                    PermissionTile(
                        icon = Icons.Filled.NotificationsActive,
                        title = "Task notifications",
                        subtitle = if (permissions.notificationsAllowed) "Enabled" else "Recommended for persistent controls",
                        ok = permissions.notificationsAllowed,
                        onClick = onRequestNotifications,
                    )
                    PermissionTile(
                        icon = Icons.Filled.BatteryFull,
                        title = "Battery optimization",
                        subtitle = "Exempt Piko if long tasks are interrupted",
                        ok = false,
                        onClick = onOpenBatterySettings,
                    )
                }
            }
        }

        item {
            SettingsSection(
                title = "Agent execution", subtitle = "Task limits and floating controls",
                expanded = expandedSection == "Agent execution",
                onClick = { expandedSection = if (expandedSection == "Agent execution") null else "Agent execution" },
            )
        }
        if (expandedSection == "Agent execution") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SliderSetting(
                            title = "Maximum steps",
                            valueText = "${settings.maxSteps}",
                            value = settings.maxSteps.toFloat(),
                            valueRange = 5f..100f,
                            steps = 18,
                            onChange = { value -> viewModel.updateSettings { it.copy(maxSteps = value.roundToInt()) } },
                        )
                        SliderSetting(
                            title = "Maximum task duration",
                            valueText = "${settings.maxDurationMinutes} min",
                            value = settings.maxDurationMinutes.toFloat(),
                            valueRange = 1f..60f,
                            steps = 58,
                            onChange = { value -> viewModel.updateSettings { it.copy(maxDurationMinutes = value.roundToInt()) } },
                        )
                        SliderSetting(
                            title = "Estimated spending limit",
                            valueText = if (settings.maxCostUsd <= 0) "No limit" else "$" + "%.2f".format(settings.maxCostUsd),
                            value = settings.maxCostUsd.toFloat(),
                            valueRange = 0f..10f,
                            steps = 19,
                            onChange = { value -> viewModel.updateSettings { it.copy(maxCostUsd = value.toDouble()) } },
                        )
                        Text(
                            "Spending is estimated from token usage and the per-model prices in Models. It is a safety limit, not a billing guarantee.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        ToggleRow(
                            title = "Confirm sensitive actions",
                            subtitle = "Sending, payments, deletion, installs, sharing, authentication and security settings.",
                            checked = settings.confirmSensitiveActions,
                            onCheckedChange = { value -> viewModel.updateSettings { it.copy(confirmSensitiveActions = value) } },
                        )
                        ToggleRow(
                            title = "Pause when the screen locks",
                            subtitle = "Piko never acts on a locked device. Resume manually after unlocking.",
                            checked = settings.autoPauseOnLock,
                            onCheckedChange = { value -> viewModel.updateSettings { it.copy(autoPauseOnLock = value) } },
                        )
                        ToggleRow(
                            title = "Floating overlay",
                            subtitle = "Draggable status panel with pause and stop. Requires overlay permission.",
                            checked = settings.overlayEnabled,
                            onCheckedChange = { value ->
                                viewModel.updateSettings { it.copy(overlayEnabled = value) }
                                if (value && !permissions.overlayAllowed) onOpenOverlay()
                            },
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = "Observations", subtitle = "Screenshots and model responses",
                expanded = expandedSection == "Observations",
                onClick = { expandedSection = if (expandedSection == "Observations") null else "Observations" },
            )
        }
        if (expandedSection == "Observations") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleRow(
                            title = "Stream provider responses",
                            subtitle = "Streams OpenAI Chat Completions over SSE. Responses and Anthropic protocols use complete responses for deterministic action parsing.",
                            checked = settings.streamResponses,
                            onCheckedChange = { value -> viewModel.updateSettings { it.copy(streamResponses = value) } },
                        )
                        ToggleRow(
                            title = "Attach screenshots for vision models",
                            subtitle = "Screenshots are selected for difficult or unchanged screens, not every request.",
                            checked = settings.sendScreenshots,
                            onCheckedChange = { value -> viewModel.updateSettings { it.copy(sendScreenshots = value) } },
                        )
                        SliderSetting(
                            title = "Screenshot quality",
                            valueText = "${settings.screenshotQuality}%",
                            value = settings.screenshotQuality.toFloat(),
                            valueRange = 30f..95f,
                            steps = 12,
                            onChange = { value -> viewModel.updateSettings { it.copy(screenshotQuality = value.roundToInt()) } },
                        )
                        SliderSetting(
                            title = "Maximum image dimension",
                            valueText = "${settings.screenshotMaxDimension}px",
                            value = settings.screenshotMaxDimension.toFloat(),
                            valueRange = 512f..2048f,
                            steps = 15,
                            onChange = { value ->
                                viewModel.updateSettings { it.copy(screenshotMaxDimension = ((value / 128).roundToInt() * 128).coerceIn(512, 2048)) }
                            },
                        )
                        Text(
                            "Screenshots are held in memory, sent only to the selected model when needed, and never written to task history.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = "Privacy & security", subtitle = "History, sensitive apps and data",
                expanded = expandedSection == "Privacy & security",
                onClick = { expandedSection = if (expandedSection == "Privacy & security") null else "Privacy & security" },
            )
        }
        if (expandedSection == "Privacy & security") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ToggleRow(
                            title = "Store task history",
                            subtitle = "Instructions, outcomes, timing, token counts and the action trace. Screenshots are excluded.",
                            checked = settings.historyEnabled,
                            onCheckedChange = { value -> viewModel.updateSettings { it.copy(historyEnabled = value) } },
                        )
                        SliderSetting(
                            title = "History retention",
                            valueText = "${settings.retentionDays} days",
                            value = settings.retentionDays.toFloat(),
                            valueRange = 1f..180f,
                            steps = 178,
                            enabled = settings.historyEnabled,
                            onChange = { value -> viewModel.updateSettings { it.copy(retentionDays = value.roundToInt()) } },
                        )
                        ToggleRow(
                            title = "Allow insecure HTTP endpoints",
                            subtitle = "For local development only. HTTPS is enforced otherwise. API keys are never sent over HTTP unless this is enabled.",
                            checked = settings.allowInsecureEndpoints,
                            onCheckedChange = { value -> viewModel.updateSettings { it.copy(allowInsecureEndpoints = value) } },
                        )
                        ToggleRow(
                            title = "Debug logging",
                            subtitle = "Keeps a small in-memory session log with provider metadata. API keys and headers are never logged.",
                            checked = settings.debugLogging,
                            onCheckedChange = { value -> viewModel.updateSettings { it.copy(debugLogging = value) } },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { deleteHistoryDialog = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = PikoRed)
                                Spacer(Modifier.width(5.dp))
                                Text("Delete history", color = PikoRed, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = { deleteKeysDialog = true },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text("Delete keys", maxLines = 1)
                            }
                        }
                        TextButton(onClick = { showLogs = !showLogs }) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (showLogs) "Hide session logs" else "Show session logs (${logs.size})")
                        }
                        if (showLogs) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    if (logs.isEmpty()) {
                                        Text(
                                            "No log entries. Enable debug logging and run a task.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    } else {
                                        logs.takeLast(40).forEach { line ->
                                            Text(
                                                line,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    TextButton(onClick = { viewModel.clearLogs() }) { Text("Clear logs") }
                                }
                            }
                        }
                        Text(
                            "Screen information is transmitted to your selected AI provider only while a task runs. Piko does not collect passwords, OTPs, payment credentials, or hidden monitoring data.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = "Device & app", subtitle = "System settings and providers",
                expanded = expandedSection == "Device & app",
                onClick = { expandedSection = if (expandedSection == "Device & app") null else "Device & app" },
            )
        }
        if (expandedSection == "Device & app") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ActionRow(
                            icon = Icons.Filled.Settings,
                            title = "Android app settings",
                            subtitle = "Notifications, permissions and battery",
                            onClick = onOpenAppSettings,
                        )
                        ActionRow(
                            icon = Icons.Filled.SmartToy,
                            title = "Model providers",
                            subtitle = "Configure keys, endpoints and capabilities",
                            onClick = onOpenModels,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection(
                title = "About Piko", subtitle = "Version and how Piko works",
                expanded = expandedSection == "About Piko",
                onClick = { expandedSection = if (expandedSection == "About Piko") null else "About Piko" },
            )
        }
        if (expandedSection == "About Piko") {
            item {
                PikoCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("Piko ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            StatusPill("Personal build", PikoViolet)
                        }
                        Text(
                            "An AI agent that operates your Android phone. Sideloaded and personal by design. Piko runs real accessibility actions; it does not simulate the phone inside its own UI.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Filled.Security, contentDescription = null, tint = PikoMint, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Keystore-encrypted keys · HTTPS by default · screenshot-protected Settings · no analytics",
                                style = MaterialTheme.typography.labelSmall,
                                color = PikoMint,
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    "Android limitations: secure windows cannot be captured, some apps block accessibility actions or expose incomplete trees, and Android may block background app launches. Piko reports these cases rather than bypassing them.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

    }

    if (deleteHistoryDialog) {
        AlertDialog(
            onDismissRequest = { deleteHistoryDialog = false },
            title = { Text("Delete all task history?") },
            text = { Text("This permanently removes all tasks and traces. Settings and API keys are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllHistory()
                    deleteHistoryDialog = false
                }) { Text("Delete", color = PikoRed) }
            },
            dismissButton = { TextButton(onClick = { deleteHistoryDialog = false }) { Text("Cancel") } },
        )
    }
    if (deleteKeysDialog) {
        AlertDialog(
            onDismissRequest = { deleteKeysDialog = false },
            title = { Text("Delete all API keys?") },
            text = { Text("Every provider key stored by Piko will be permanently removed. Providers remain configured but cannot make requests until you add keys again.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllCredentials()
                    deleteKeysDialog = false
                }) { Text("Delete keys", color = PikoRed) }
            },
            dismissButton = { TextButton(onClick = { deleteKeysDialog = false }) { Text("Cancel") } },
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
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                valueText,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else PikoMuted,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = { onChange(local) },
        )
    }
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.Transparent,
        onClick = onClick,
    ) {
        Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ApiKeySetting(
    providerName: String,
    hasKey: Boolean,
    onSave: suspend (String) -> Boolean,
    onRemove: suspend () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    PikoCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(providerName, style = MaterialTheme.typography.titleMedium)
            Text(if (hasKey) "Key saved securely" else "No key saved", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                label = { Text(if (hasKey) "Replace API key" else "API key") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = input.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try { if (onSave(input)) input = "" } finally { busy = false }
                    }
                }) { Text("Save key") }
                if (hasKey) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try { onRemove() } finally { busy = false }
                    }
                }) { Text("Remove key") }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
    Column {
        Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.small) {
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}
