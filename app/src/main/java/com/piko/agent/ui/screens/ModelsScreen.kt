package com.piko.agent.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ProviderKind
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.PikoCard
import com.piko.agent.ui.components.SectionHeader
import com.piko.agent.ui.theme.PikoAmber
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(
    viewModel: PikoViewModel,
    onOpenMenu: () -> Unit,
    onEditProvider: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val credentialRevision by viewModel.credentialRevision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addDialog by remember { mutableStateOf(false) }
    val selected = settings.selectedProvider

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenMenu, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open navigation")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Models", style = MaterialTheme.typography.headlineLarge)
                    Text("The mind behind your tasks.", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            PikoCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("CURRENT MODEL", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(selected?.selectedModel?.label ?: "Choose your model",
                        style = MaterialTheme.typography.headlineMedium)
                    Text(selected?.name ?: "Connect a provider to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(
                        onClick = { selected?.let { onEditProvider(it.id) } ?: run { addDialog = true } },
                    ) {
                        Text(if (selected == null) "Connect a provider" else "Change model")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Filled.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
            }
        }
        item {
            SectionHeader("Your providers", trailing = {
                TextButton(onClick = { addDialog = true }) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add")
                }
            })
        }
        items(settings.providerConfigs, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider, selected = provider.id == settings.selectedProviderId,
                hasKey = viewModel.hasApiKey(provider.id),
                onClick = { onEditProvider(provider.id) },
                onSelect = {
                    scope.launch {
                        viewModel.selectProvider(provider.id)
                        viewModel.emit("${provider.name} selected.")
                    }
                },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your keys stay encrypted on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://opencode.ai/console")))
                    }) { Text("OpenCode console") }
                    TextButton(onClick = onOpenSettings) { Text("API keys & settings") }
                }
            }
        }
    }

    if (addDialog) {
        AddProviderDialog(
            onDismiss = { addDialog = false },
            onChoose = { kind ->
                scope.launch {
                    val id = viewModel.createProvider(kind)
                    addDialog = false
                    onEditProvider(id)
                }
            },
        )
    }
}

@Composable
private fun ProviderCard(
    provider: ProviderConfig,
    selected: Boolean,
    hasKey: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
) {
    Column {
        Surface(onClick = onClick, color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    Text(provider.selectedModel?.label ?: "No model selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!hasKey) Text("Add an API key", style = MaterialTheme.typography.labelSmall, color = PikoAmber)
                }
                if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected provider",
                    modifier = Modifier.padding(12.dp).size(20.dp))
                else TextButton(onClick = onSelect) { Text("Use") }
                Icon(Icons.Filled.ChevronRight, contentDescription = "Edit ${provider.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun AddProviderDialog(
    onDismiss: () -> Unit,
    onChoose: (ProviderKind) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderChoice("OpenCode Go", "One key, many models, model discovery", ProviderKind.OPENCODE_GO, onChoose)
                ProviderChoice("OpenAI", "Official OpenAI API", ProviderKind.OPENAI, onChoose)
                ProviderChoice("Anthropic", "Claude Messages API", ProviderKind.ANTHROPIC, onChoose)
                ProviderChoice("OpenAI-compatible", "Local servers, gateways, other vendors", ProviderKind.OPENAI_COMPATIBLE, onChoose)
                ProviderChoice("Custom endpoint", "Bring a fully custom compatible endpoint", ProviderKind.CUSTOM, onChoose)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ProviderChoice(
    title: String,
    subtitle: String,
    kind: ProviderKind,
    onChoose: (ProviderKind) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        onClick = { onChoose(kind) },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null)
        }
    }
}

fun kindLabel(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENCODE_GO -> "OpenCode Go"
    ProviderKind.OPENAI -> "OpenAI"
    ProviderKind.ANTHROPIC -> "Anthropic"
    ProviderKind.OPENAI_COMPATIBLE -> "OpenAI-compatible"
    ProviderKind.CUSTOM -> "Custom"
}
