package com.piko.agent.ai

import com.piko.agent.core.TokenUsage
import com.piko.agent.model.EndpointProtocol
import com.piko.agent.model.RemoteModel

data class LlmRequest(
    val systemPrompt: String,
    val userPrompt: String,
    val imageJpeg: ByteArray? = null,
    val sessionId: String,
    val maxOutputTokens: Int = 1600,
    val allowTools: Boolean = true,
    val stream: Boolean = false,
)

data class LlmResponse(
    val text: String,
    val toolName: String? = null,
    val toolArguments: String? = null,
    val usage: TokenUsage = TokenUsage(),
    val usageEstimated: Boolean = false,
    val finishReason: String? = null,
    val responseId: String? = null,
) {
    /** The best candidate for ActionParser: native tool arguments first. */
    val actionCandidate: String
        get() = toolArguments?.takeIf { it.isNotBlank() } ?: text
}

data class RemoteModelInfo(
    val id: String,
    val displayName: String = id,
)

enum class LlmErrorKind {
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMIT,
    TIMEOUT,
    NETWORK,
    SERVER,
    INVALID_REQUEST,
    CONTENT_FILTER,
    UNSUPPORTED,
    CANCELLED,
    UNKNOWN,
}

class LlmException(
    message: String,
    val kind: LlmErrorKind,
    val httpCode: Int? = null,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    val retryable: Boolean
        get() = kind == LlmErrorKind.RATE_LIMIT || kind == LlmErrorKind.TIMEOUT ||
            kind == LlmErrorKind.NETWORK || kind == LlmErrorKind.SERVER

    fun userMessage(): String = when (kind) {
        LlmErrorKind.AUTHENTICATION -> "Authentication failed. Check the API key for this provider."
        LlmErrorKind.PERMISSION -> "The provider denied this request. Check the key's permissions."
        LlmErrorKind.RATE_LIMIT -> "The provider is rate limiting Piko. Retrying may help shortly."
        LlmErrorKind.TIMEOUT -> "The model did not respond before the request timeout."
        LlmErrorKind.NETWORK -> "Piko could not reach the model provider. Check your connection."
        LlmErrorKind.SERVER -> "The model provider returned a server error."
        LlmErrorKind.INVALID_REQUEST -> "The provider rejected the request. Check the model ID and endpoint."
        LlmErrorKind.CONTENT_FILTER -> "The provider blocked the response under its content policy."
        LlmErrorKind.UNSUPPORTED -> "This model or endpoint does not support the request. Try another protocol or model."
        LlmErrorKind.CANCELLED -> "The request was cancelled."
        LlmErrorKind.UNKNOWN -> message ?: "The model request failed."
    }
}

data class ConnectionTestResult(
    val success: Boolean,
    val message: String,
    val models: List<RemoteModel> = emptyList(),
    val kind: LlmErrorKind? = null,
)

fun EndpointProtocol.isAnthropic(): Boolean = this == EndpointProtocol.ANTHROPIC_MESSAGES

internal fun sanitizeErrorMessage(raw: String, apiKey: String): String {
    val withoutKey = if (apiKey.isNotBlank() && raw.contains(apiKey)) {
        raw.replace(apiKey, "[redacted]")
    } else {
        raw
    }
    return withoutKey.take(600)
}
