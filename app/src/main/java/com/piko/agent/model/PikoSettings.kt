package com.piko.agent.model

import kotlinx.serialization.Serializable

@Serializable
data class PikoSettings(
    val historyEnabled: Boolean = true,
    val retentionDays: Int = 30,
    val debugLogging: Boolean = false,
    val maxSteps: Int = 40,
    val maxDurationMinutes: Int = 15,
    val maxCostUsd: Double = 1.00,
    val confirmSensitiveActions: Boolean = true,
    val autoPauseOnLock: Boolean = true,
    val sendScreenshots: Boolean = true,
    val streamResponses: Boolean = false,
    val screenshotQuality: Int = 72,
    val screenshotMaxDimension: Int = 1024,
    val overlayEnabled: Boolean = true,
    val floatingAssistantEnabled: Boolean = false,
    val allowInsecureEndpoints: Boolean = false,
    val storeScreenshots: Boolean = false,
    val settleTimeoutMs: Long = 1800,
    val executionTimeoutMs: Long = 20_000,
    val selectedProviderId: String = ProviderCatalog.OPENCODE_GO_ID,
    val providerConfigs: List<ProviderConfig> = listOf(ProviderCatalog.openCodeGo()),
    val onboardingComplete: Boolean = false,
    val recognitionLanguage: String = java.util.Locale.getDefault().toLanguageTag(),
    val spokenResponses: Boolean = false,
    val speechRate: Float = 1f,
    val speechVoice: String? = null,
    val memorySuggestions: Boolean = false,
) {
    val selectedProvider: ProviderConfig?
        get() = providerConfigs.firstOrNull { it.id == selectedProviderId }
            ?: providerConfigs.firstOrNull()

    fun sanitized(): PikoSettings = copy(
        retentionDays = retentionDays.coerceIn(1, 365),
        speechRate = if (speechRate.isFinite()) speechRate.coerceIn(0.5f, 2f) else 1f,
        recognitionLanguage = recognitionLanguage.take(64).ifBlank { java.util.Locale.getDefault().toLanguageTag() },
        maxSteps = maxSteps.coerceIn(1, 200),
        maxDurationMinutes = maxDurationMinutes.coerceIn(1, 120),
        maxCostUsd = maxCostUsd.coerceIn(0.0, 1000.0),
        screenshotQuality = screenshotQuality.coerceIn(30, 95),
        screenshotMaxDimension = screenshotMaxDimension.coerceIn(512, 2048),
        settleTimeoutMs = settleTimeoutMs.coerceIn(300, 10_000),
        executionTimeoutMs = executionTimeoutMs.coerceIn(2_000, 60_000),
        providerConfigs = providerConfigs
            .map { it.normalized() }
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id },
    )
}
