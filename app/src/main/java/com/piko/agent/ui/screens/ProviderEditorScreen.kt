package com.piko.agent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.piko.agent.ai.ConnectionTestResult
import com.piko.agent.model.EndpointProtocol
import com.piko.agent.model.ModelConfig
import com.piko.agent.model.ProviderCatalog
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ProviderKind
import com.piko.agent.model.RemoteModel
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
fun ProviderEditorScreen(
    viewModel: PikoViewModel,
    providerId: String,
    onOpenKeys: () -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val provider = settings.providerConfigs.firstOrNull { it.id == providerId }
    if (provider == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Model provider", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                "This provider is not available. It may have been removed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
        return
    }

    ProviderEditorContent(
        viewModel = viewModel,
        initial = provider,
        onOpenKeys = onOpenKeys,
        canDelete = settings.providerConfigs.size > 1,
        onBack = onBack,
        onDeleted = onDeleted,
    )
}

@Composable
private fun ProviderEditorContent(
    viewModel: PikoViewModel,
    initial: ProviderConfig,
    onOpenKeys: () -> Unit,
    canDelete: Boolean,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(initial.id) { mutableStateOf(initial) }
    var saving by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<ConnectionTestResult?>(null) }
    var modelDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var newModelId by remember { mutableStateOf("") }
    var protocolMenu by remember { mutableStateOf(false) }
    var modelProtocolMenu by remember { mutableStateOf(false) }
    val selectedModel = draft.selectedModel

    fun updateSelected(transform: (ModelConfig) -> ModelConfig) {
        val id = draft.selectedModelId
        draft = draft.copy(
            models = draft.models.map { if (it.id == id) transform(it) else it },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(Modifier.weight(1f)) {
                    Text("Configure provider", style = MaterialTheme.typography.titleLarge)
                    Text(
                        kindLabel(draft.kind),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { if (canDelete) deleteDialog = true }, enabled = canDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete provider",
                        tint = if (canDelete) PikoRed else PikoMuted,
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            val ok = viewModel.saveProvider(draft, "")
                            saving = false
                            if (ok) onBack()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(50.dp),
                    shape = MaterialTheme.shapes.large,
                    enabled = !saving,
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Save provider", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (draft.kind == ProviderKind.OPENCODE_GO) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                ) {
                    Text(
                        "OpenCode Go uses the documented inference endpoint, Bearer auth, and a stable session header. Piko routes each model to its OpenAI, Responses, or Anthropic-compatible endpoint automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            PikoCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { draft = draft.copy(name = it) },
                        label = { Text("Display name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    SelectionField(
                        label = "Provider type",
                        value = kindLabel(draft.kind),
                        onClick = null,
                    )
                    OutlinedTextField(
                        value = draft.baseUrl,
                        onValueChange = { draft = draft.copy(baseUrl = it) },
                        label = { Text("Base URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("https://api.example.com/v1") },
                        supportingText = {
                            Text(
                                if (draft.baseUrl.startsWith("http://")) {
                                    "HTTP is rejected unless local development endpoints are enabled in Settings."
                                } else {
                                    "Use the API root that contains /chat/completions, /responses or /messages."
                                },
                            )
                        },
                        isError = draft.baseUrl.startsWith("http://"),
                    )
                    TextButton(onClick = onOpenKeys) { Text("Manage API keys in Settings") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(onClick = { protocolMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    "Endpoint: ${protocolLabel(draft.protocol)}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(expanded = protocolMenu, onDismissRequest = { protocolMenu = false }) {
                                EndpointProtocol.entries.forEach { protocol ->
                                    DropdownMenuItem(
                                        text = { Text(protocolLabel(protocol)) },
                                        onClick = {
                                            draft = draft.copy(
                                                protocol = protocol,
                                                models = draft.models.map { it.copy(protocol = protocol) },
                                            )
                                            protocolMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = draft.timeoutSeconds.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { draft = draft.copy(timeoutSeconds = it) }
                            },
                            label = { Text("Timeout (s)") },
                            modifier = Modifier.width(116.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = draft.maxRetries.toString(),
                            onValueChange = { value ->
                                value.toIntOrNull()?.let { draft = draft.copy(maxRetries = it) }
                            },
                            label = { Text("Retries") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }

            PikoCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(
                            title = "Models",
                            subtitle = "${draft.models.size} configured",
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    fetching = true
                                    val result = viewModel.fetchModels(draft, "")
                                    fetching = false
                                    result.onSuccess { remote ->
                                        val merged = viewModel.enrichModels(draft, remote)
                                        draft = draft.copy(
                                            models = merged,
                                            selectedModelId = draft.selectedModelId.ifBlank { merged.firstOrNull()?.id.orEmpty() },
                                        )
                                        viewModel.emit("Loaded ${remote.size} models.")
                                    }.onFailure { viewModel.emit(it.message ?: "Could not load models.") }
                                }
                            },
                            enabled = !fetching,
                        ) {
                            if (fetching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh models")
                            }
                        }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        onClick = { if (draft.models.isNotEmpty()) modelDialog = true },
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Selected model", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    selectedModel?.label ?: "No model selected",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    selectedModel?.id ?: "Refresh or add a model ID",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Choose model")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newModelId,
                            onValueChange = { newModelId = it },
                            label = { Text("Add model ID") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("e.g. deepseek-v4.1-flash") },
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val id = newModelId.trim()
                                if (id.isBlank()) return@Button
                                val existing = draft.models.any { it.id == id }
                                if (!existing) {
                                    val model = ProviderCatalog.modelForModelId(draft.kind, id)
                                    draft = draft.copy(
                                        models = draft.models + model,
                                        selectedModelId = model.id,
                                    )
                                } else {
                                    draft = draft.copy(selectedModelId = id)
                                }
                                newModelId = ""
                            },
                            enabled = newModelId.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                        }
                    }
                }
            }

            selectedModel?.let { model ->
                var inputPriceText by remember(model.id) { mutableStateOf(model.inputPricePerMillion.toString()) }
                var outputPriceText by remember(model.id) { mutableStateOf(model.outputPricePerMillion.toString()) }
                var maxTokensText by remember(model.id) { mutableStateOf(model.maxOutputTokens.toString()) }
                PikoCard(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Model capabilities", style = MaterialTheme.typography.titleMedium)
                        SwitchRow(
                            title = "Vision / screenshots",
                            subtitle = "Allow Piko to attach screenshots when the agent needs visual context.",
                            checked = model.supportsVision,
                            onCheckedChange = { value -> updateSelected { it.copy(supportsVision = value) } },
                        )
                        SwitchRow(
                            title = "Native tool calling",
                            subtitle = "Use the execute_action tool. If the provider rejects it, Piko falls back to structured JSON.",
                            checked = model.supportsTools,
                            onCheckedChange = { value -> updateSelected { it.copy(supportsTools = value) } },
                        )
                        Box {
                            OutlinedButton(onClick = { modelProtocolMenu = true }) {
                                Text("Protocol: ${protocolLabel(model.protocol)}")
                            }
                            DropdownMenu(expanded = modelProtocolMenu, onDismissRequest = { modelProtocolMenu = false }) {
                                EndpointProtocol.entries.forEach { protocol ->
                                    DropdownMenuItem(
                                        text = { Text(protocolLabel(protocol)) },
                                        onClick = {
                                            updateSelected { it.copy(protocol = protocol) }
                                            modelProtocolMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = inputPriceText,
                                onValueChange = { value ->
                                    if (value.matches(Regex("^\\d*\\.?\\d*$"))) {
                                        inputPriceText = value
                                        value.toDoubleOrNull()?.let { price -> updateSelected { it.copy(inputPricePerMillion = price) } }
                                    }
                                },
                                label = { Text("Input $/M") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            OutlinedTextField(
                                value = outputPriceText,
                                onValueChange = { value ->
                                    if (value.matches(Regex("^\\d*\\.?\\d*$"))) {
                                        outputPriceText = value
                                        value.toDoubleOrNull()?.let { price -> updateSelected { it.copy(outputPricePerMillion = price) } }
                                    }
                                },
                                label = { Text("Output $/M") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                        }
                        OutlinedTextField(
                            value = maxTokensText,
                            onValueChange = { value ->
                                if (value.all(Char::isDigit)) {
                                    maxTokensText = value
                                    value.toIntOrNull()?.let { tokens -> updateSelected { it.copy(maxOutputTokens = tokens) } }
                                }
                            },
                            label = { Text("Max output tokens") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Text(
                            "Pricing is used only for the estimated cost display and spending limit. Zero means unknown or free.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            PikoCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection test", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Verifies authentication and model discovery. If the model list endpoint is unavailable, Piko sends a tiny prompt to the selected model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                testing = true
                                testResult = viewModel.testConnection(draft.normalized(), "")
                                testing = false
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (testing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Testing…")
                        } else {
                            Text("Test connection")
                        }
                    }
                    testResult?.let { result ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (result.success) PikoMint.copy(alpha = 0.1f) else PikoRed.copy(alpha = 0.1f),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (result.success) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        tint = if (result.success) PikoMint else PikoRed,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(7.dp))
                                    Text(
                                        if (result.success) "Connection successful" else "Connection failed",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                Text(
                                    result.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                                if (result.models.isNotEmpty()) {
                                    TextButton(onClick = {
                                        val merged = viewModel.enrichModels(draft, result.models)
                                        draft = draft.copy(
                                            models = merged,
                                            selectedModelId = draft.selectedModelId.ifBlank { merged.firstOrNull()?.id.orEmpty() },
                                        )
                                        viewModel.emit("Imported ${result.models.size} models.")
                                    }) {
                                        Text("Import ${result.models.size} discovered models")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Text(
                "Screen observations sent to this provider may contain personal information visible on your phone. Piko filters redundant nodes and redacts password and credential fields, but you should only use providers you trust.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
        }
    }

    if (modelDialog) {
        ModelPickerDialog(
            models = draft.models,
            selectedId = draft.selectedModelId,
            onSelect = { id ->
                draft = draft.copy(selectedModelId = id)
                modelDialog = false
            },
            onDismiss = { modelDialog = false },
        )
    }
    if (deleteDialog) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false },
            title = { Text("Remove provider?") },
            text = { Text("This deletes ${draft.name}, its saved API key, and its model settings. Task history is kept.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.deleteProvider(draft.id)
                        deleteDialog = false
                        onDeleted()
                    }
                }) {
                    Text("Remove", color = PikoRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModelPickerDialog(
    models: List<ModelConfig>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a model") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                models.forEach { model ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (model.id == selectedId) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                        } else {
                            Color.Transparent
                        },
                        onClick = { onSelect(model.id) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = model.id == selectedId, onClick = { onSelect(model.id) })
                            Column(Modifier.weight(1f)) {
                                Text(model.label, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    model.id,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (model.supportsVision) StatusPill("Vision", PikoViolet)
                                    if (model.supportsTools) StatusPill("Tools", PikoMint)
                                    StatusPill(protocolLabel(model.protocol), PikoMuted)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun SelectionField(label: String, value: String, onClick: (() -> Unit)?) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            onClick = onClick ?: {},
            enabled = onClick != null,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (onClick != null) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
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

private fun protocolLabel(protocol: EndpointProtocol): String = when (protocol) {
    EndpointProtocol.AUTO -> "Auto-detect"
    EndpointProtocol.OPENAI_CHAT -> "OpenAI Chat Completions"
    EndpointProtocol.OPENAI_RESPONSES -> "OpenAI Responses"
    EndpointProtocol.ANTHROPIC_MESSAGES -> "Anthropic Messages"
}
