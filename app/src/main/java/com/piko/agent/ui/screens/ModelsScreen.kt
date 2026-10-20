package com.piko.agent.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ProviderKind
import com.piko.agent.ui.PikoViewModel
import com.piko.agent.ui.components.PikoCard
import com.piko.agent.ui.components.SectionHeader
import com.piko.agent.ui.components.StatusPill
import com.piko.agent.ui.theme.PikoAmber
import com.piko.agent.ui.theme.PikoMint
import com.piko.agent.ui.theme.PikoMuted
import com.piko.agent.ui.theme.PikoRed
import com.piko.agent.ui.theme.PikoViolet
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(
    viewModel: PikoViewModel,
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text("Models", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Connect the AI that plans and operates the phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            PikoCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill("Active model", MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        Text(
                            settings.selectedProvider?.name ?: "None",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        selected?.selectedModel?.label ?: "No model selected",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        selected?.selectedModel?.id ?: "Add a provider below",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            if (selected?.selectedModel?.supportsVision == true) "Vision" else "Text only",
                            if (selected?.selectedModel?.supportsVision == true) PikoViolet else PikoMuted,
                        )
                        StatusPill(
                            if (selected?.selectedModel?.supportsTools == true) "Tools" else "JSON actions",
                            PikoMint,
                        )
                        StatusPill(
                            if (selected != null && viewModel.hasApiKey(selected.id)) "Key saved" else "Needs key",
                            if (selected != null && viewModel.hasApiKey(selected.id)) PikoMint else PikoAmber,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { selected?.let { onEditProvider(it.id) } ?: run { addDialog = true } },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(if (selected == null) "Add provider" else "Configure model")
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        item {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = PikoViolet.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, PikoViolet.copy(alpha = 0.25f)),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cloud, contentDescription = null, tint = PikoViolet, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("OpenCode Go", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Piko works with OpenCode Go out of the box. Create a key in the OpenCode Console, paste it here, and refresh the model list.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://opencode.ai/console")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }) {
                            Text("Open console")
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                        TextButton(onClick = onOpenSettings) { Text("Network settings") }
                    }
                }
            }
        }

        item {
            SectionHeader(
                title = "Providers",
                subtitle = "${settings.providerConfigs.size} configured",
                trailing = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        onClick = { addDialog = true },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
            )
        }

        items(settings.providerConfigs, key = { it.id }) { provider ->
            ProviderCard(
                provider = provider,
                selected = provider.id == settings.selectedProviderId,
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
            Text(
                "Piko sends the user goal, a filtered accessibility observation, and recent action history to the selected provider. Screenshots are attached only when needed. API keys are encrypted with the Android Keystore.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        onClick = onClick,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(20.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(provider.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${kindLabel(provider.kind)} · ${provider.selectedModel?.id ?: "no model"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected) {
                    StatusPill("Active", MaterialTheme.colorScheme.primary, icon = Icons.Filled.Check)
                } else {
                    TextButton(onClick = onSelect) { Text("Use") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    if (hasKey) "Key saved" else "No key",
                    if (hasKey) PikoMint else PikoAmber,
                    icon = Icons.Filled.Key,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(
                    if (provider.selectedModel?.supportsVision == true) "Vision" else "Text",
                    if (provider.selectedModel?.supportsVision == true) PikoViolet else PikoMuted,
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = "Edit ${provider.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
