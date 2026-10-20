package com.piko.agent.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a model response into a single action. Both native tool-call
 * arguments and structured JSON in message text are accepted.
 */
object ActionParser {
    private val reasoningKeys = listOf("reasoning", "thought", "explanation", "analysis", "why")

    fun parse(raw: String): ActionParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ActionParseResult.Failure("The model returned an empty response.", "")
        val candidates = linkedSetOf<JsonObject>()

        runCatching { PikoJson.parseToJsonElement(trimmed).jsonObject }.getOrNull()?.let(candidates::add)
        if (candidates.isEmpty()) {
            extractBalancedObjects(trimmed).forEach { candidate ->
                runCatching { PikoJson.parseToJsonElement(candidate).jsonObject }.getOrNull()?.let(candidates::add)
            }
        }
        if (candidates.isEmpty()) {
            return ActionParseResult.Failure("No JSON object found in the model response.", trimmed.take(300))
        }

        var lastError: String? = null
        for (candidate in candidates) {
            val actionObject = findActionObject(candidate)
            if (actionObject != null) {
                val reasoning = findText(candidate, reasoningKeys)
                val decoded = runCatching {
                    PikoJson.decodeFromJsonElement(AgentAction.serializer(), actionObject)
                }
                decoded.onSuccess { return ActionParseResult.Success(it, reasoning, trimmed) }
                    .onFailure { lastError = it.message ?: "Unknown decode error" }
            }
            lastError = lastError ?: "JSON did not contain an action."
        }
        return ActionParseResult.Failure(
            "Could not decode a valid action: ${lastError ?: "unknown error"}",
            trimmed.take(600),
        )
    }

    private fun findActionObject(root: JsonObject, depth: Int = 0): JsonObject? {
        if (depth > 3) return null
        for (key in listOf("action", "tool_call", "toolCall", "function", "arguments", "input")) {
            val value = root[key]
            if (value is JsonObject) {
                if (value.containsKey("type") || value.containsKey("action")) {
                    return findActionObject(value, depth + 1) ?: value
                }
                findActionObject(value, depth + 1)?.let { return it }
            }
            if (value is JsonPrimitive && value.isString) {
                val text = value.content
                val embedded = extractBalancedObjects(text).firstNotNullOfOrNull { part ->
                    runCatching { PikoJson.parseToJsonElement(part).jsonObject }.getOrNull()
                }
                if (embedded != null) findActionObject(embedded, depth + 1)?.let { return it }
            }
        }
        if (root.containsKey("type")) return root
        return null
    }

    private fun findText(root: JsonObject, keys: List<String>): String? {
        val direct = keys.firstNotNullOfOrNull { key -> root[key]?.asTextOrNull() }
        if (direct != null) return direct
        for (value in root.values) {
            if (value is JsonObject) {
                val nested = keys.firstNotNullOfOrNull { key -> value[key]?.asTextOrNull() }
                if (nested != null) return nested
            }
        }
        return null
    }

    private fun JsonElement.asTextOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /**
     * Finds balanced JSON objects while ignoring braces inside strings. Models
     * often wrap JSON in prose or Markdown fences, so this is deliberately
     * tolerant.
     */
    fun extractBalancedObjects(text: String): List<String> {
        val results = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        for (index in text.indices) {
            val c = text[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }
                continue
            }
            when (c) {
                '"' -> if (depth > 0) inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) {
                            results += text.substring(start, index + 1)
                            start = -1
                        }
                    }
                }
            }
        }
        return results
    }

    sealed interface ActionParseResult {
        data class Success(
            val action: AgentAction,
            val reasoning: String?,
            val raw: String,
        ) : ActionParseResult

        data class Failure(
            val message: String,
            val rawPreview: String,
        ) : ActionParseResult
    }
}

/** Builds the JSON shape used by fallback prompts and connection tests. */
object ActionSchema {
    fun example(): JsonObject = buildJsonObject {
        put("reasoning", JsonPrimitive("Brief explanation of why this action follows from the observation."))
        put("action", buildJsonObject {
            put("version", JsonPrimitive(1))
            put("type", JsonPrimitive("tap"))
            put("elementId", JsonPrimitive("element_12"))
        })
    }

    fun schemaDescription(): String = """
        Every response must contain exactly one action:
        {"version":1,"type":"tap","elementId":"element_12"}
        {"version":1,"type":"tap","x":540,"y":1200}
        {"version":1,"type":"long_press","elementId":"element_5"}
        {"version":1,"type":"swipe","startX":540,"startY":1700,"endX":540,"endY":500,"durationMs":400}
        {"version":1,"type":"scroll","direction":"down","elementId":"element_3","amount":1}
        {"version":1,"type":"type_text","elementId":"element_4","text":"hello"}
        {"version":1,"type":"clear_text","elementId":"element_4"}
        {"version":1,"type":"back"}
        {"version":1,"type":"home"}
        {"version":1,"type":"open_notifications"}
        {"version":1,"type":"launch_app","packageName":"com.android.settings"}
        {"version":1,"type":"wait","milliseconds":500}
        {"version":1,"type":"observe","includeScreenshot":true}
        {"version":1,"type":"request_confirmation","summary":"Send this message?","pendingAction":{"version":1,"type":"tap","elementId":"element_8"}}
        {"version":1,"type":"finish","summary":"Dark mode is enabled.","verified":true,"verification":"Theme setting shows Dark."}
        {"version":1,"type":"fail","message":"The app could not be opened."}
    """.trimIndent()
}
