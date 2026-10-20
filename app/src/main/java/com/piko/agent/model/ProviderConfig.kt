package com.piko.agent.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderKind {
    OPENCODE_GO,
    OPENAI,
    ANTHROPIC,
    OPENAI_COMPATIBLE,
    CUSTOM,
}

@Serializable
enum class EndpointProtocol {
    AUTO,
    OPENAI_CHAT,
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
}

@Serializable
data class ModelConfig(
    val id: String,
    val displayName: String = id,
    val protocol: EndpointProtocol = EndpointProtocol.AUTO,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = true,
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val maxOutputTokens: Int = 1600,
) {
    val label: String get() = if (displayName.isBlank()) id else displayName
}

@Serializable
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val protocol: EndpointProtocol = EndpointProtocol.AUTO,
    val models: List<ModelConfig> = emptyList(),
    val selectedModelId: String = "",
    val timeoutSeconds: Int = 90,
    val maxRetries: Int = 2,
    val createdAtMs: Long = System.currentTimeMillis(),
) {
    val selectedModel: ModelConfig?
        get() = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()

    fun normalized(): ProviderConfig {
        val cleanedBase = baseUrl.trim().trimEnd('/')
        val cleanedModels = models
            .filter { it.id.isNotBlank() }
            .map { it.copy(id = it.id.trim(), displayName = it.displayName.trim().ifBlank { it.id.trim() }) }
            .distinctBy { it.id }
        val requestedSelected = selectedModelId.trim()
        val selected = if (cleanedModels.any { it.id == requestedSelected }) {
            requestedSelected
        } else {
            cleanedModels.firstOrNull()?.id.orEmpty()
        }
        return copy(
            name = name.trim().ifBlank { "Provider" },
            baseUrl = cleanedBase,
            models = cleanedModels,
            selectedModelId = selected,
            timeoutSeconds = timeoutSeconds.coerceIn(15, 300),
            maxRetries = maxRetries.coerceIn(0, 5),
        )
    }
}

data class RemoteModel(
    val id: String,
    val displayName: String = id,
)

object ProviderCatalog {
    const val OPENCODE_GO_ID = "opencode-go"
    const val OPENCODE_GO_BASE = "https://opencode.ai/inference/go"

    fun openCodeGo(): ProviderConfig = ProviderConfig(
        id = OPENCODE_GO_ID,
        name = "OpenCode Go",
        kind = ProviderKind.OPENCODE_GO,
        baseUrl = OPENCODE_GO_BASE,
        protocol = EndpointProtocol.AUTO,
        selectedModelId = "deepseek-v4.1-flash",
        models = openCodeGoModels(),
        timeoutSeconds = 120,
    )

    /**
     * Seed list from OpenCode's published Go catalog. The live /models endpoint
     * refreshes this list. Pricing is per million tokens and is an estimate;
     * users can override it. Protocols are routed from the documented Go
     * endpoint table.
     */
    fun openCodeGoModels(): List<ModelConfig> = listOf(
        model("deepseek-v4.1-flash", "DeepSeek V4.1 Flash", 0.30, 1.20),
        model("deepseek-v4-pro", "DeepSeek V4 Pro", 1.32, 3.96),
        model("glm-5.3-flash", "GLM-5.3 Flash", 0.15, 0.50),
        model("glm-5.3", "GLM-5.3", 1.40, 4.40),
        model("glm-5.2", "GLM-5.2", 1.40, 4.40),
        model("kimi-k2.7-code", "Kimi K2.7 Code", 0.95, 4.00),
        model("kimi-k3", "Kimi K3", 3.00, 15.00),
        model("kimi-k2.6", "Kimi K2.6", 0.95, 4.00),
        model("mimo-v2.6-flash", "MiMo V2.6 Flash", 0.14, 0.28),
        model("mimo-v2.6-pro", "MiMo V2.6 Pro", 0.435, 0.87),
        model("mimo-v2.5", "MiMo V2.5", 0.14, 0.28),
        model("mimo-v2.5-pro", "MiMo V2.5 Pro", 0.435, 0.87),
        model("minimax-m3", "MiniMax M3", 0.30, 1.20),
        model("minimax-m3.1-flash-free", "MiniMax M3.1 Flash Free", 0.0, 0.0),
        model("longcat-2.0", "LongCat 2.0", 0.30, 1.20),
        model("longcat-2.5-preview-free", "LongCat 2.5 Preview Free", 0.0, 0.0),
        model("step-5-preview", "Step 5 Preview", 1.00, 2.70),
        model("hy3", "Hy3", 0.14, 0.58),
        model("qwen3.8-max", "Qwen3.8 Max", 2.00, 6.00),
        model("qwen3.8-flash", "Qwen3.8 Flash", 0.15, 0.47),
        model("qwen3.7-plus", "Qwen3.7 Plus", 0.40, 1.60),
        model("claude-haiku-5-5", "Claude Haiku 5.5", 0.10, 0.50, vision = true),
        model("gpt-5.6-luna", "GPT 5.6 Luna", 0.20, 1.20, vision = true, protocol = EndpointProtocol.OPENAI_RESPONSES),
        model("gpt-6-luna", "GPT 6 Luna", 0.10, 0.50, vision = true, protocol = EndpointProtocol.OPENAI_RESPONSES),
        model("grok-4.6", "Grok 4.6", 2.00, 6.00, vision = true, protocol = EndpointProtocol.OPENAI_RESPONSES),
        model("grok-4.7", "Grok 4.7", 2.00, 6.00, vision = true, protocol = EndpointProtocol.OPENAI_RESPONSES),
        model("muse-spark-1.3-contributor", "Muse Spark 1.3", 0.10, 0.20, protocol = EndpointProtocol.OPENAI_RESPONSES),
        model("muse-spark-1.2-contributor", "Muse Spark 1.2", 0.10, 0.20, protocol = EndpointProtocol.OPENAI_RESPONSES),
    )

    fun modelForModelId(providerKind: ProviderKind, modelId: String): ModelConfig {
        val known = if (providerKind == ProviderKind.OPENCODE_GO) {
            openCodeGoModels().firstOrNull { it.id == modelId }
        } else {
            null
        }
        return known ?: ModelConfig(
            id = modelId,
            displayName = modelId,
            protocol = if (providerKind == ProviderKind.OPENAI) inferOpenAiProtocol(modelId) else EndpointProtocol.AUTO,
            supportsVision = guessVision(providerKind, modelId),
            supportsTools = true,
        )
    }

    fun inferOpenAiProtocol(modelId: String): EndpointProtocol {
        val id = modelId.lowercase()
        return if (
            id.startsWith("gpt-5") || id.startsWith("gpt-6") ||
            id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") ||
            id.startsWith("computer-use")
        ) {
            EndpointProtocol.OPENAI_RESPONSES
        } else {
            EndpointProtocol.OPENAI_CHAT
        }
    }

    fun guessVision(kind: ProviderKind, modelId: String): Boolean {
        val id = modelId.lowercase()
        return when {
            kind == ProviderKind.OPENCODE_GO -> id.contains("vision") || id.contains("vl") ||
                id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("claude-") ||
                id.startsWith("gemini-")
            else -> id.contains("vision") || id.contains("vl") || id.startsWith("gpt-4o") ||
                id.startsWith("gpt-4.1") || id.startsWith("gpt-5") || id.startsWith("gpt-6") ||
                id.startsWith("claude-") || id.startsWith("gemini-")
        }
    }

    fun openAi(): ProviderConfig = ProviderConfig(
        id = "openai",
        name = "OpenAI",
        kind = ProviderKind.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        selectedModelId = "gpt-5.2",
        models = listOf(
            ModelConfig("gpt-5.2", "GPT-5.2", EndpointProtocol.OPENAI_RESPONSES, true, true, 1.75, 14.0),
            ModelConfig("gpt-4.1", "GPT-4.1", EndpointProtocol.OPENAI_CHAT, true, true, 2.0, 8.0),
        ),
    )

    fun anthropic(): ProviderConfig = ProviderConfig(
        id = "anthropic",
        name = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        selectedModelId = "claude-sonnet-4-6",
        models = listOf(
            ModelConfig("claude-sonnet-4-6", "Claude Sonnet 4.6", EndpointProtocol.ANTHROPIC_MESSAGES, true, true, 3.0, 15.0),
            ModelConfig("claude-haiku-4-5", "Claude Haiku 4.5", EndpointProtocol.ANTHROPIC_MESSAGES, true, true, 1.0, 5.0),
        ),
    )

    fun compatible(): ProviderConfig = ProviderConfig(
        id = "custom-${System.currentTimeMillis()}",
        name = "OpenAI-compatible",
        kind = ProviderKind.OPENAI_COMPATIBLE,
        baseUrl = "",
        selectedModelId = "",
        models = emptyList(),
    )

    private fun model(
        id: String,
        name: String,
        input: Double,
        output: Double,
        vision: Boolean = false,
        protocol: EndpointProtocol? = null,
    ): ModelConfig = ModelConfig(
        id = id,
        displayName = name,
        protocol = protocol ?: resolveGoProtocol(id),
        supportsVision = vision,
        supportsTools = true,
        inputPricePerMillion = input,
        outputPricePerMillion = output,
    )

    fun resolveGoProtocol(modelId: String): EndpointProtocol {
        val id = modelId.lowercase()
        return when {
            id.startsWith("gpt-") || id.startsWith("grok-") || id.startsWith("muse-spark-") ->
                EndpointProtocol.OPENAI_RESPONSES
            id.startsWith("claude-") || id.startsWith("minimax-") ||
                id.startsWith("qwen3.7-plus") || id.startsWith("qwen3.8-flash") ||
                id.startsWith("qwen3.8-max") || id.startsWith("qwen3.7-max") ||
                id.startsWith("qwen3.6-plus") -> EndpointProtocol.ANTHROPIC_MESSAGES
            else -> EndpointProtocol.OPENAI_CHAT
        }
    }
}
