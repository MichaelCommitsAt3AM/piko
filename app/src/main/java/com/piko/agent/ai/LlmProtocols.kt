package com.piko.agent.ai

import com.piko.agent.core.PikoJson
import com.piko.agent.core.TokenEstimator
import com.piko.agent.core.TokenUsage
import com.piko.agent.model.EndpointProtocol
import com.piko.agent.model.ModelConfig
import com.piko.agent.model.ProviderConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.bool(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal object ProtocolJson {
    fun extractTextContent(element: JsonElement?): String = when (element) {
        null -> ""
        is JsonPrimitive -> if (element.isString) element.content else ""
        is JsonArray -> element.mapNotNull { part ->
            when (part) {
                is JsonPrimitive -> part.takeIf { it.isString }?.content
                is JsonObject -> part.string("text") ?: part.string("content") ?: part.string("output_text")
                else -> null
            }
        }.joinToString("\n").trim()
        is JsonObject -> element.string("text") ?: element.string("content") ?: ""
    }

    fun imageDataUri(jpeg: ByteArray, mime: String = "image/jpeg"): String =
        "data:$mime;base64," + Base64.getEncoder().encodeToString(jpeg)
}

internal fun usageFrom(
    input: Long?,
    output: Long?,
    cached: Long?,
    promptText: String,
    responseText: String,
): Pair<TokenUsage, Boolean> {
    val hasUsage = input != null || output != null
    val inputTokens = input ?: TokenEstimator.estimateTokens(promptText)
    val outputTokens = output ?: TokenEstimator.estimateTokens(responseText)
    val usage = TokenUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedInputTokens = cached ?: 0,
    )
    return usage to !hasUsage
}

internal fun protocolError(code: Int, body: String, apiKey: String): LlmException {
    val message = runCatching {
        val root = PikoJson.parseToJsonElement(body).jsonObject
        root.obj("error")?.string("message")
            ?: root.string("message")
            ?: root.string("detail")
            ?: body
    }.getOrDefault(body)
    val safe = sanitizeErrorMessage(message.ifBlank { "HTTP $code" }, apiKey)
    val kind = when (code) {
        401 -> LlmErrorKind.AUTHENTICATION
        403 -> LlmErrorKind.PERMISSION
        404 -> LlmErrorKind.INVALID_REQUEST
        408 -> LlmErrorKind.TIMEOUT
        413 -> LlmErrorKind.INVALID_REQUEST
        429 -> LlmErrorKind.RATE_LIMIT
        in 500..599 -> LlmErrorKind.SERVER
        else -> LlmErrorKind.INVALID_REQUEST
    }
    val retryAfter = runCatching {
        val root = PikoJson.parseToJsonElement(body).jsonObject
        root.obj("error")?.string("retry_after")
    }.getOrNull()?.toDoubleOrNull()?.times(1000)?.toLong()
    return LlmException("$safe (HTTP $code)", kind, code, retryAfter)
}

/**
 * OpenAI Chat Completions (also used by most OpenAI-compatible endpoints).
 */
object OpenAiChatProtocol {
    fun buildBody(
        config: ProviderConfig,
        model: ModelConfig,
        request: LlmRequest,
    ): JsonObject = buildJsonObject {
        put("model", model.id)
        if (request.stream) {
            put("stream", true)
            // Supported by OpenAI and most compatible gateways. If a gateway
            // rejects it, the retry path resends a minimal request.
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "system")
                put("content", request.systemPrompt)
            })
            add(buildJsonObject {
                put("role", "user")
                val image = request.imageJpeg
                if (image != null) {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", request.userPrompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", ProtocolJson.imageDataUri(image))
                                put("detail", "high")
                            }
                        })
                    }
                } else {
                    put("content", request.userPrompt)
                }
            })
        }
        if (request.allowTools && model.supportsTools) {
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", ActionToolSchema.NAME)
                        put("description", ActionToolSchema.DESCRIPTION)
                        put("parameters", ActionToolSchema.parameters())
                    }
                })
            }
            put("tool_choice", "auto")
        }
        if (config.kind == com.piko.agent.model.ProviderKind.OPENAI) {
            put("max_completion_tokens", request.maxOutputTokens)
        } else {
            put("max_tokens", request.maxOutputTokens)
            put("temperature", 0.1)
        }
    }

    fun parseResponse(body: String, request: LlmRequest): LlmResponse {
        val root = PikoJson.parseToJsonElement(body).jsonObject
        val choice = root.arr("choices")?.firstOrNull()?.jsonObject
            ?: throw LlmException("The provider returned no choices.", LlmErrorKind.UNKNOWN)
        val message = choice.obj("message") ?: buildJsonObject { }
        var text = ProtocolJson.extractTextContent(message["content"])
            .ifBlank { ProtocolJson.extractTextContent(choice["text"]) }
        var toolName: String? = null
        var arguments: String? = null

        val toolCall = message.arr("tool_calls")?.firstOrNull()?.jsonObject
        if (toolCall != null) {
            val function = toolCall.obj("function")
            toolName = function?.string("name") ?: toolCall.string("name")
            arguments = when (val raw = function?.get("arguments") ?: toolCall["arguments"]) {
                is JsonPrimitive -> raw.content
                is JsonObject -> PikoJson.encodeToString(JsonObject.serializer(), raw)
                else -> null
            }
        }
        val legacyFunction = message.obj("function_call")
        if (arguments.isNullOrBlank() && legacyFunction != null) {
            toolName = legacyFunction.string("name")
            arguments = legacyFunction.string("arguments")
        }
        // Some gateways return reasoning_content separately.
        if (text.isBlank()) {
            text = ProtocolJson.extractTextContent(message["reasoning_content"])
        }
        val usageObj = root.obj("usage")
        val promptText = request.systemPrompt + request.userPrompt
        val (usage, estimated) = usageFrom(
            input = usageObj?.long("prompt_tokens") ?: usageObj?.long("input_tokens"),
            output = usageObj?.long("completion_tokens") ?: usageObj?.long("output_tokens"),
            cached = usageObj?.obj("prompt_tokens_details")?.long("cached_tokens")
                ?: usageObj?.obj("input_tokens_details")?.long("cached_tokens"),
            promptText = promptText,
            responseText = text + (arguments ?: ""),
        )
        val filter = root.arr("choices")?.firstOrNull()?.jsonObject?.string("finish_reason") == "content_filter"
        if (filter) throw LlmException("The provider blocked the response.", LlmErrorKind.CONTENT_FILTER)
        return LlmResponse(
            text = text,
            toolName = toolName,
            toolArguments = arguments,
            usage = usage,
            usageEstimated = estimated,
            finishReason = choice.string("finish_reason"),
            responseId = root.string("id"),
        )
    }
}

/**
 * OpenAI Responses API (used by OpenAI and several OpenCode Go models).
 */
object OpenAiResponsesProtocol {
    fun buildBody(model: ModelConfig, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model.id)
        put("store", false)
        put("instructions", request.systemPrompt)
        putJsonArray("input") {
            add(buildJsonObject {
                put("role", "user")
                if (request.imageJpeg != null) {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", request.userPrompt)
                        })
                        add(buildJsonObject {
                            put("type", "input_image")
                            put("image_url", ProtocolJson.imageDataUri(request.imageJpeg))
                            put("detail", "high")
                        })
                    }
                } else {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", request.userPrompt)
                        })
                    })
                }
            })
        }
        if (request.allowTools && model.supportsTools) {
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "function")
                    put("name", ActionToolSchema.NAME)
                    put("description", ActionToolSchema.DESCRIPTION)
                    put("parameters", ActionToolSchema.parameters())
                })
            }
            put("tool_choice", "auto")
        }
        put("max_output_tokens", request.maxOutputTokens)
    }

    fun parseResponse(body: String, request: LlmRequest): LlmResponse {
        val root = PikoJson.parseToJsonElement(body).jsonObject
        if (root.string("status") == "failed") {
            val message = root.obj("error")?.string("message") ?: "The response failed."
            throw LlmException(message, LlmErrorKind.UNKNOWN)
        }
        val output = root.arr("output") ?: buildJsonArray { }
        val textParts = mutableListOf<String>()
        var toolName: String? = null
        var arguments: String? = null
        for (item in output) {
            val obj = item as? JsonObject ?: continue
            when (obj.string("type")) {
                "function_call" -> {
                    toolName = obj.string("name")
                    arguments = obj.string("arguments")
                }
                "message" -> {
                    val content = obj["content"] as? JsonArray
                    content?.forEach { part ->
                        val partObj = part as? JsonObject ?: return@forEach
                        val partText = partObj.string("text")
                        if (!partText.isNullOrBlank()) textParts += partText
                    }
                }
                "output_text" -> obj.string("text")?.let(textParts::add)
            }
        }
        if (textParts.isEmpty()) {
            root.string("output_text")?.let(textParts::add)
        }
        val usageObj = root.obj("usage")
        val text = textParts.joinToString("\n").trim()
        val (usage, estimated) = usageFrom(
            input = usageObj?.long("input_tokens"),
            output = usageObj?.long("output_tokens"),
            cached = usageObj?.obj("input_tokens_details")?.long("cached_tokens"),
            promptText = request.systemPrompt + request.userPrompt,
            responseText = text + (arguments ?: ""),
        )
        val contentFiltered = root.obj("incomplete_details")?.string("reason") == "content_filter"
        if (contentFiltered) throw LlmException("The provider blocked the response.", LlmErrorKind.CONTENT_FILTER)
        return LlmResponse(
            text = text,
            toolName = toolName,
            toolArguments = arguments,
            usage = usage,
            usageEstimated = estimated,
            finishReason = root.string("status"),
            responseId = root.string("id"),
        )
    }
}

/**
 * Accumulates OpenAI Chat Completions SSE chunks. Tool-call arguments arrive as
 * string fragments and are concatenated before validation.
 */
class OpenAiChatStreamAccumulator {
    private val text = StringBuilder()
    private var toolName: String? = null
    private val arguments = StringBuilder()
    private var finishReason: String? = null
    private var responseId: String? = null
    private var inputTokens: Long? = null
    private var outputTokens: Long? = null
    private var cachedTokens: Long? = null
    private var sawData = false
    var error: LlmException? = null
        private set

    fun consumeData(data: String) {
        if (data == "[DONE]") return
        if (data.isBlank()) return
        sawData = true
        val root = runCatching { PikoJson.parseToJsonElement(data).jsonObject }.getOrNull()
            ?: throw LlmException("Malformed streaming chunk.", LlmErrorKind.UNKNOWN)
        root.obj("error")?.let { errorObject ->
            val message = errorObject.string("message") ?: "The provider returned a streaming error."
            error = LlmException(message, LlmErrorKind.SERVER)
            return
        }
        responseId = root.string("id") ?: responseId
        val choice = root.arr("choices")?.firstOrNull()?.jsonObject
        if (choice != null) {
            val delta = choice.obj("delta")
            delta?.let { d ->
                val content = ProtocolJson.extractTextContent(d["content"])
                if (content.isNotEmpty()) text.append(content)
                val toolCalls = d.arr("tool_calls")
                if (toolCalls != null) {
                    for (callElement in toolCalls) {
                        val call = callElement as? JsonObject ?: continue
                        val function = call.obj("function")
                        function?.string("name")?.takeIf { it.isNotBlank() }?.let { toolName = it }
                        function?.string("arguments")?.let { arguments.append(it) }
                    }
                }
                val legacy = d.obj("function_call")
                if (legacy != null) {
                    legacy.string("name")?.takeIf { it.isNotBlank() }?.let { toolName = it }
                    legacy.string("arguments")?.let { arguments.append(it) }
                }
            }
            choice.string("finish_reason")?.let { finishReason = it }
        }
        root.obj("usage")?.let { usage ->
            inputTokens = usage.long("prompt_tokens") ?: usage.long("input_tokens")
            outputTokens = usage.long("completion_tokens") ?: usage.long("output_tokens")
            cachedTokens = usage.obj("prompt_tokens_details")?.long("cached_tokens")
                ?: usage.obj("input_tokens_details")?.long("cached_tokens")
        }
    }

    fun finish(request: LlmRequest): LlmResponse {
        error?.let { throw it }
        val finalText = text.toString()
        val finalArguments = arguments.toString().ifBlank { null }
        if (!sawData) throw LlmException("The provider returned no streaming data.", LlmErrorKind.UNKNOWN)
        if (finalText.isBlank() && finalArguments.isNullOrBlank()) {
            throw LlmException("The model returned an empty streamed response.", LlmErrorKind.UNKNOWN)
        }
        val (usage, estimated) = usageFrom(
            input = inputTokens,
            output = outputTokens,
            cached = cachedTokens,
            promptText = request.systemPrompt + request.userPrompt,
            responseText = finalText + (finalArguments ?: ""),
        )
        return LlmResponse(
            text = finalText,
            toolName = toolName,
            toolArguments = finalArguments,
            usage = usage,
            usageEstimated = estimated,
            finishReason = finishReason,
            responseId = responseId,
        )
    }
}

/**
 * Anthropic Messages API, including OpenCode Go's Anthropic-compatible route.
 */
object AnthropicProtocol {
    fun buildBody(model: ModelConfig, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model.id)
        put("system", request.systemPrompt)
        put("max_tokens", request.maxOutputTokens)
        put("temperature", 0.1)
        putJsonArray("messages") {
            add(buildJsonObject {
                put("role", "user")
                if (request.imageJpeg != null) {
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", request.userPrompt)
                        })
                        add(buildJsonObject {
                            put("type", "image")
                            putJsonObject("source") {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", Base64.getEncoder().encodeToString(request.imageJpeg))
                            }
                        })
                    }
                } else {
                    put("content", request.userPrompt)
                }
            })
        }
        if (request.allowTools && model.supportsTools) {
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("name", ActionToolSchema.NAME)
                    put("description", ActionToolSchema.DESCRIPTION)
                    put("input_schema", ActionToolSchema.parameters())
                })
            }
        }
    }

    fun parseResponse(body: String, request: LlmRequest): LlmResponse {
        val root = PikoJson.parseToJsonElement(body).jsonObject
        val content = root.arr("content") ?: buildJsonArray { }
        val textParts = mutableListOf<String>()
        var toolName: String? = null
        var arguments: String? = null
        for (block in content) {
            val obj = block as? JsonObject ?: continue
            when (obj.string("type")) {
                "text" -> obj.string("text")?.let(textParts::add)
                "tool_use" -> {
                    toolName = obj.string("name")
                    val input = obj["input"] as? JsonObject
                    arguments = input?.let { PikoJson.encodeToString(JsonObject.serializer(), it) }
                }
                "thinking" -> Unit
            }
        }
        val usageObj = root.obj("usage")
        val text = textParts.joinToString("\n").trim()
        val (usage, estimated) = usageFrom(
            input = usageObj?.long("input_tokens"),
            output = usageObj?.long("output_tokens"),
            cached = usageObj?.long("cache_read_input_tokens"),
            promptText = request.systemPrompt + request.userPrompt,
            responseText = text + (arguments ?: ""),
        )
        return LlmResponse(
            text = text,
            toolName = toolName,
            toolArguments = arguments,
            usage = usage,
            usageEstimated = estimated,
            finishReason = root.string("stop_reason"),
            responseId = root.string("id"),
        )
    }
}

internal fun parseModelList(body: String): List<RemoteModelInfo> {
    val root = runCatching { PikoJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return emptyList()
    val array = root["data"] as? JsonArray ?: root["models"] as? JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
        val display = obj.string("display_name") ?: obj.string("displayName") ?: obj.string("name") ?: id
        RemoteModelInfo(id, display)
    }.distinctBy { it.id }
}
