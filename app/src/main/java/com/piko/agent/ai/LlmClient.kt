package com.piko.agent.ai

import com.piko.agent.core.PikoJson
import com.piko.agent.core.RetryPolicy
import com.piko.agent.model.EndpointProtocol
import com.piko.agent.model.ModelConfig
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ProviderKind
import com.piko.agent.model.ProviderCatalog
import com.piko.agent.model.RemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One HTTP client that understands the three response dialects Piko supports.
 * Authentication headers are added here and are never logged.
 */
class LlmClient(
    private val baseClient: OkHttpClient = defaultClient(),
) {
    suspend fun complete(
        config: ProviderConfig,
        model: ModelConfig,
        apiKey: String,
        request: LlmRequest,
        allowInsecureEndpoints: Boolean,
        onRetry: ((String) -> Unit)? = null,
    ): LlmResponse {
        if (apiKey.isBlank()) {
            throw LlmException("Missing API key for ${config.name}.", LlmErrorKind.AUTHENTICATION)
        }
        val protocol = resolveProtocol(config, model)
        val url = resolveChatUrl(config, model, protocol, allowInsecureEndpoints)
        val retryPolicy = RetryPolicy(maxAttempts = config.maxRetries + 1)

        var allowTools = request.allowTools
        var simplified = false
        var streamDisabled = false
        var attempt = 0
        var lastError: LlmException? = null
        while (true) {
            try {
                val toolAwareRequest = request.copy(
                    allowTools = allowTools && model.supportsTools,
                    stream = request.stream && !streamDisabled,
                )
                var body = when (protocol) {
                    EndpointProtocol.OPENAI_CHAT -> OpenAiChatProtocol.buildBody(config, model, toolAwareRequest)
                    EndpointProtocol.OPENAI_RESPONSES -> OpenAiResponsesProtocol.buildBody(model, toolAwareRequest)
                    EndpointProtocol.ANTHROPIC_MESSAGES -> AnthropicProtocol.buildBody(model, toolAwareRequest)
                    EndpointProtocol.AUTO -> error("Protocol must be resolved")
                }
                if (simplified) {
                    val removed = when (protocol) {
                        EndpointProtocol.OPENAI_CHAT -> setOf("temperature", "max_tokens", "max_completion_tokens", "stream_options")
                        EndpointProtocol.OPENAI_RESPONSES -> setOf("temperature", "max_output_tokens", "stream_options", "store")
                        else -> emptySet()
                    }
                    if (removed.isNotEmpty()) {
                        body = JsonObject(body.filterKeys { it !in removed })
                    }
                }
                val http = if (toolAwareRequest.stream && protocol == EndpointProtocol.OPENAI_CHAT) {
                    return executeStreamingChat(
                        url = url,
                        apiKey = apiKey,
                        config = config,
                        request = toolAwareRequest,
                        protocol = protocol,
                        body = body,
                        timeoutSeconds = config.timeoutSeconds,
                    )
                } else {
                    execute(
                        url = url,
                        apiKey = apiKey,
                        config = config,
                        sessionId = request.sessionId,
                        protocol = protocol,
                        body = body,
                        timeoutSeconds = config.timeoutSeconds,
                    )
                }
                if (http.code !in 200..299) throw protocolError(http.code, http.body, apiKey)
                val parsed = when (protocol) {
                    EndpointProtocol.OPENAI_CHAT -> OpenAiChatProtocol.parseResponse(http.body, request)
                    EndpointProtocol.OPENAI_RESPONSES -> OpenAiResponsesProtocol.parseResponse(http.body, request)
                    EndpointProtocol.ANTHROPIC_MESSAGES -> AnthropicProtocol.parseResponse(http.body, request)
                    EndpointProtocol.AUTO -> error("Protocol must be resolved")
                }
                if (parsed.actionCandidate.isBlank()) {
                    throw LlmException("The model returned an empty action.", LlmErrorKind.UNKNOWN)
                }
                return parsed
            } catch (error: LlmException) {
                lastError = error
                // Some OpenAI-compatible servers reject tool definitions. Retry
                // once using structured JSON mode instead of failing the task.
                if (allowTools && isToolRejection(error)) {
                    allowTools = false
                    onRetry?.invoke("Provider rejected native tools; falling back to structured JSON.")
                    continue
                }
                if (!simplified && wouldHelpToSimplify(error)) {
                    simplified = true
                    onRetry?.invoke("Provider rejected an optional request parameter; retrying with a minimal request.")
                    continue
                }
                if (request.stream && !streamDisabled && error.kind == LlmErrorKind.INVALID_REQUEST &&
                    (error.message?.contains("stream", ignoreCase = true) == true)
                ) {
                    streamDisabled = true
                    onRetry?.invoke("Provider rejected streaming; retrying without streaming.")
                    continue
                }
                if (!error.retryable) throw error
                val delayMs = retryPolicy.nextDelayMs(attempt, error.retryAfterMs)
                    ?: throw error
                onRetry?.invoke("${error.userMessage()} Retrying in ${delayMs / 1000.0}s.")
                delay(delayMs)
                attempt++
            } catch (error: IllegalArgumentException) {
                throw LlmException(error.message ?: "Invalid provider configuration.", LlmErrorKind.INVALID_REQUEST, cause = error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw LlmException(
                    "The provider returned a response Piko could not parse: ${error.message ?: error.javaClass.simpleName}",
                    LlmErrorKind.UNKNOWN,
                    cause = error,
                )
            }
        }
    }

    suspend fun listModels(
        config: ProviderConfig,
        apiKey: String,
        allowInsecureEndpoints: Boolean,
    ): List<RemoteModel> {
        if (apiKey.isBlank()) {
            throw LlmException("Missing API key for ${config.name}.", LlmErrorKind.AUTHENTICATION)
        }
        val url = resolveModelsUrl(config, allowInsecureEndpoints)
        val http = executeGet(
            url = url,
            apiKey = apiKey,
            config = config,
            sessionId = "piko-model-list",
            protocol = resolveProtocol(config, config.selectedModel ?: ModelConfig(id = "")),
            timeoutSeconds = minOf(config.timeoutSeconds, 30),
        )
        if (http.code !in 200..299) throw protocolError(http.code, http.body, apiKey)
        val parsed = parseModelList(http.body)
        if (parsed.isEmpty()) {
            throw LlmException("The provider returned no models.", LlmErrorKind.UNKNOWN)
        }
        return parsed.map { RemoteModel(it.id, it.displayName) }
    }

    suspend fun testConnection(
        config: ProviderConfig,
        apiKey: String,
        allowInsecureEndpoints: Boolean,
    ): ConnectionTestResult {
        if (apiKey.isBlank()) {
            return ConnectionTestResult(false, "Enter an API key first.", kind = LlmErrorKind.AUTHENTICATION)
        }
        val model = config.selectedModel
        if (model == null) {
            return ConnectionTestResult(false, "Add a model ID first.", kind = LlmErrorKind.INVALID_REQUEST)
        }
        return try {
            // A tiny completion always validates the key and the selected
            // model. Some providers expose /models publicly, so a successful
            // model list alone is not proof that inference is authorized.
            val response = complete(
                config = config,
                model = model,
                apiKey = apiKey,
                request = LlmRequest(
                    systemPrompt = "You are a connection test. Reply with the single word OK.",
                    userPrompt = "Reply with OK.",
                    sessionId = "piko-connection-test",
                    maxOutputTokens = 24,
                    allowTools = false,
                ),
                allowInsecureEndpoints = allowInsecureEndpoints,
            )
            if (response.actionCandidate.isBlank()) {
                ConnectionTestResult(false, "Connected, but the model returned an empty response.", kind = LlmErrorKind.UNKNOWN)
            } else {
                val models = runCatching { listModels(config, apiKey, allowInsecureEndpoints) }.getOrDefault(emptyList())
                ConnectionTestResult(
                    true,
                    "Connected to ${config.name} and received a model response." +
                        if (models.isNotEmpty()) " Found ${models.size} models." else "",
                    models,
                )
            }
        } catch (error: LlmException) {
            ConnectionTestResult(false, error.userMessage(), kind = error.kind)
        } catch (error: Exception) {
            ConnectionTestResult(false, error.message ?: "Connection failed.", kind = LlmErrorKind.UNKNOWN)
        }
    }

    private fun isToolRejection(error: LlmException): Boolean {
        val message = error.message?.lowercase() ?: return false
        return error.kind == LlmErrorKind.INVALID_REQUEST &&
            (message.contains("tool") || message.contains("function")) &&
            (message.contains("not support") || message.contains("unsupported") || message.contains("unknown") || message.contains("invalid"))
    }

    private fun wouldHelpToSimplify(error: LlmException): Boolean {
        if (error.kind != LlmErrorKind.INVALID_REQUEST) return false
        val message = error.message?.lowercase() ?: return false
        return message.contains("temperature") || message.contains("max_tokens") ||
            message.contains("max_completion_tokens") || message.contains("max_output_tokens") ||
            message.contains("stream_options") || message.contains("store") ||
            message.contains("unsupported parameter") || message.contains("unknown parameter")
    }

    private suspend fun execute(
        url: String,
        apiKey: String,
        config: ProviderConfig,
        sessionId: String,
        protocol: EndpointProtocol,
        body: JsonObject,
        timeoutSeconds: Int,
    ): HttpResult {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = PikoJson.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .applyAuth(config, apiKey, protocol, sessionId)
            .build()
        return perform(request, timeoutSeconds)
    }

    private suspend fun executeGet(
        url: String,
        apiKey: String,
        config: ProviderConfig,
        sessionId: String,
        protocol: EndpointProtocol,
        timeoutSeconds: Int,
    ): HttpResult {
        val request = Request.Builder()
            .url(url)
            .get()
            .applyAuth(config, apiKey, protocol, sessionId)
            .build()
        return perform(request, timeoutSeconds)
    }

    private fun Request.Builder.applyAuth(
        config: ProviderConfig,
        apiKey: String,
        protocol: EndpointProtocol,
        sessionId: String,
    ): Request.Builder {
        header("User-Agent", "Piko/1.0 (Android; personal accessibility agent)")
        header("Accept", "application/json")
        header("X-Piko-Client", "Piko-Android/1.0")
        if (config.kind == ProviderKind.ANTHROPIC) {
            header("x-api-key", apiKey)
        } else {
            header("Authorization", "Bearer $apiKey")
        }
        if (protocol == EndpointProtocol.ANTHROPIC_MESSAGES) {
            header("anthropic-version", "2023-06-01")
            // OpenCode Go's Anthropic-compatible route accepts Bearer auth, but
            // some gateways expect the native Anthropic key header. Sending it
            // alongside Bearer is harmless and improves compatibility.
            if (config.kind == ProviderKind.OPENCODE_GO) {
                header("x-api-key", apiKey)
            }
        }
        if (config.kind == ProviderKind.OPENCODE_GO) {
            header("x-opencode-session", sessionId.take(128))
        }
        return this
    }

    private suspend fun perform(request: Request, timeoutSeconds: Int): HttpResult =
        suspendCancellableCoroutine { continuation ->
            val client = baseClient.newBuilder()
                .callTimeout(timeoutSeconds.toLong().coerceIn(10, 300), TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds.toLong().coerceIn(10, 300), TimeUnit.SECONDS)
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(mapIoException(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        runCatching {
                            val body = it.body?.string().orEmpty()
                            val retryAfter = it.header("Retry-After")?.toLongOrNull()?.times(1000)
                            val limit = 4_000_000
                            HttpResult(
                                code = it.code,
                                body = if (body.length > limit) body.take(limit) else body,
                                retryAfterMs = retryAfter,
                            )
                        }.onSuccess { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }.onFailure { error ->
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    LlmException(error.message ?: "Could not read response.", LlmErrorKind.NETWORK, cause = error),
                                )
                            }
                        }
                    }
                }
            })
        }

    private suspend fun executeStreamingChat(
        url: String,
        apiKey: String,
        config: ProviderConfig,
        request: LlmRequest,
        protocol: EndpointProtocol,
        body: JsonObject,
        timeoutSeconds: Int,
    ): LlmResponse = suspendCancellableCoroutine { continuation ->
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = PikoJson.encodeToString(JsonObject.serializer(), body).toRequestBody(mediaType)
        val httpRequest = Request.Builder()
            .url(url)
            .post(requestBody)
            .applyAuth(config, apiKey, protocol, request.sessionId)
            .header("Accept", "text/event-stream")
            .build()
        val client = baseClient.newBuilder()
            .callTimeout(timeoutSeconds.toLong().coerceIn(10, 300), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong().coerceIn(10, 300), TimeUnit.SECONDS)
            .build()
        val call = client.newCall(httpRequest)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(mapIoException(e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        if (it.code !in 200..299) {
                            val errorBody = it.body?.string().orEmpty()
                            if (continuation.isActive) {
                                continuation.resumeWithException(protocolError(it.code, errorBody, apiKey))
                            }
                            return
                        }
                        val source = it.body?.source()
                            ?: throw LlmException("The provider returned no body.", LlmErrorKind.UNKNOWN)
                        val accumulator = OpenAiChatStreamAccumulator()
                        var line = source.readUtf8Line()
                        while (line != null && line.isBlank()) line = source.readUtf8Line()
                        if (line == null) {
                            throw LlmException("The provider returned an empty stream.", LlmErrorKind.UNKNOWN)
                        }
                        if (!line.startsWith("data:") && !line.startsWith("event:") && !line.startsWith(":")) {
                            // Some compatible gateways ignore stream=true and
                            // return a normal JSON response.
                            val normalBody = buildString {
                                appendLine(line)
                                append(source.readUtf8())
                            }
                            val parsed = OpenAiChatProtocol.parseResponse(normalBody, request)
                            if (continuation.isActive) continuation.resume(parsed)
                            return
                        }
                        while (line != null) {
                            if (line.startsWith("data:")) {
                                accumulator.consumeData(line.removePrefix("data:").trim())
                            }
                            line = source.readUtf8Line()
                        }
                        val parsed = accumulator.finish(request)
                        if (continuation.isActive) continuation.resume(parsed)
                    } catch (cancelled: CancellationException) {
                        if (continuation.isActive) continuation.resumeWithException(cancelled)
                    } catch (error: LlmException) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    } catch (error: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(mapIoException(error))
                    } catch (error: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                LlmException("Could not read the streamed response: ${error.message}", LlmErrorKind.NETWORK, cause = error),
                            )
                        }
                    }
                }
            }
        })
    }

    private fun mapIoException(error: IOException): LlmException = when (error) {
        is SocketTimeoutException -> LlmException(
            "The provider request timed out.",
            LlmErrorKind.TIMEOUT,
            cause = error,
        )
        is UnknownHostException -> LlmException(
            "The provider host could not be resolved.",
            LlmErrorKind.NETWORK,
            cause = error,
        )
        else -> LlmException(
            "Network error: ${error.message ?: error.javaClass.simpleName}",
            LlmErrorKind.NETWORK,
            cause = error,
        )
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        fun resolveProtocol(config: ProviderConfig, model: ModelConfig): EndpointProtocol {
            if (model.protocol != EndpointProtocol.AUTO) return model.protocol
            if (config.protocol != EndpointProtocol.AUTO) return config.protocol
            return when (config.kind) {
                ProviderKind.OPENCODE_GO -> ProviderCatalog.resolveGoProtocol(model.id)
                ProviderKind.ANTHROPIC -> EndpointProtocol.ANTHROPIC_MESSAGES
                ProviderKind.OPENAI -> ProviderCatalog.inferOpenAiProtocol(model.id)
                ProviderKind.OPENAI_COMPATIBLE, ProviderKind.CUSTOM -> EndpointProtocol.OPENAI_CHAT
            }
        }

        fun resolveChatUrl(
            config: ProviderConfig,
            model: ModelConfig,
            protocol: EndpointProtocol,
            allowInsecure: Boolean,
        ): String {
            val base = validateBaseUrl(config.baseUrl, allowInsecure)
            if (config.kind == ProviderKind.OPENCODE_GO) {
                val root = goRoot(base)
                return when (protocol) {
                    EndpointProtocol.OPENAI_CHAT -> "$root/openai/v1/chat/completions"
                    EndpointProtocol.OPENAI_RESPONSES -> "$root/openai/v1/responses"
                    EndpointProtocol.ANTHROPIC_MESSAGES -> "$root/anthropic/v1/messages"
                    EndpointProtocol.AUTO -> error("Protocol must be resolved")
                }.also { validateBaseUrl(it, allowInsecure) }
            }
            val url = when (protocol) {
                EndpointProtocol.OPENAI_CHAT -> when {
                    base.endsWith("/chat/completions") -> base
                    else -> "$base/chat/completions"
                }
                EndpointProtocol.OPENAI_RESPONSES -> when {
                    base.endsWith("/responses") -> base
                    else -> "$base/responses"
                }
                EndpointProtocol.ANTHROPIC_MESSAGES -> when {
                    base.endsWith("/messages") -> base
                    else -> "$base/messages"
                }
                EndpointProtocol.AUTO -> error("Protocol must be resolved")
            }
            return validateBaseUrl(url, allowInsecure)
        }

        fun resolveModelsUrl(config: ProviderConfig, allowInsecure: Boolean): String {
            val base = validateBaseUrl(config.baseUrl, allowInsecure)
            if (config.kind == ProviderKind.OPENCODE_GO) {
                return validateBaseUrl("${goRoot(base)}/v1/models", allowInsecure)
            }
            val root = when {
                base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions")
                base.endsWith("/responses") -> base.removeSuffix("/responses")
                base.endsWith("/messages") -> base.removeSuffix("/messages")
                else -> base
            }
            return validateBaseUrl("$root/models", allowInsecure)
        }

        private fun goRoot(base: String): String {
            val trimmed = base.trimEnd('/')
            return when {
                trimmed.contains("/inference/go") -> trimmed.substringBefore("/inference/go") + "/inference/go"
                trimmed.contains("/zen/go") -> trimmed.substringBefore("/zen/go") + "/zen/go"
                else -> trimmed
            }
        }

        fun validateBaseUrl(raw: String, allowInsecure: Boolean): String {
            val value = raw.trim()
            require(value.isNotEmpty()) { "Provider base URL is empty." }
            val url = runCatching { value.toHttpUrl() }
                .getOrNull() ?: throw LlmException("Invalid provider URL: $value", LlmErrorKind.INVALID_REQUEST)
            if (url.scheme == "http" && !allowInsecure) {
                throw LlmException(
                    "Insecure HTTP endpoints are disabled. Use HTTPS or enable local development endpoints in Settings.",
                    LlmErrorKind.INVALID_REQUEST,
                )
            }
            if (url.scheme != "https" && url.scheme != "http") {
                throw LlmException("Only HTTP(S) provider endpoints are supported.", LlmErrorKind.INVALID_REQUEST)
            }
            require(url.username.isEmpty() && url.password.isEmpty()) { "Provider URLs must not contain credentials." }
            return value.trimEnd('/')
        }
    }
}

private data class HttpResult(
    val code: Int,
    val body: String,
    val retryAfterMs: Long? = null,
)
