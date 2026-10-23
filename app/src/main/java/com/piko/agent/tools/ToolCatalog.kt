package com.piko.agent.tools

import kotlinx.serialization.json.*

data class ToolSpec(
    val id: String,
    val description: String,
    val required: Set<String> = emptySet(),
    val optional: Set<String> = emptySet(),
    val confirmation: Boolean = false,
    val permissions: Set<String> = emptySet(),
    val timeoutMs: Long = 10_000,
    val risk: String = if (confirmation) "user_review" else "low",
    val resultSchema: String = "success:Boolean, message:String, acceptedByAndroid:Boolean, resultVerified:Boolean",
    val verification: String = "Intent dispatch only; review the destination app.",
) {
    val inputSchema: JsonObject get() = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("properties", buildJsonObject {
            (required + optional).forEach { key ->
                put(key, buildJsonObject {
                    put("type", when (key) {
                        "enabled" -> "boolean"
                        "percent", "hour", "minute", "beginMs", "endMs" -> "integer"
                        else -> "string"
                    })
                })
            }
        })
    }
}

/** Closed catalog: no arbitrary intents, components, shell commands or extras. */
object ToolCatalog {
    val tools = listOf(
        ToolSpec("wifi_settings", "Open Wi-Fi settings"),
        ToolSpec("bluetooth_settings", "Open Bluetooth settings"),
        ToolSpec("display_settings", "Open display settings"),
        ToolSpec("app_details", "Open application settings", setOf("package")),
        ToolSpec("website", "Open an HTTP(S) website", setOf("url")),
        ToolSpec("media_volume", "Set media volume", setOf("percent"), verification = "Read back STREAM_MUSIC volume."),
        ToolSpec("flashlight", "Set flashlight", setOf("enabled"), permissions = setOf("android.permission.CAMERA"), verification = "Wait for torch callback."),
        ToolSpec("dialer", "Prepare a phone call", setOf("number"), confirmation = true),
        ToolSpec("sms", "Prepare an SMS draft", setOf("number", "text"), confirmation = true),
        ToolSpec("email", "Prepare an email draft", setOf("address", "subject", "text"), confirmation = true),
        ToolSpec("share_text", "Open text share chooser", setOf("text"), confirmation = true),
        ToolSpec("clipboard", "Copy text to clipboard", setOf("text"), confirmation = true),
        ToolSpec("navigation", "Open navigation destination", setOf("destination")),
        ToolSpec("calendar", "Prepare calendar event", setOf("title"), setOf("beginMs", "endMs"), confirmation = true),
        ToolSpec("alarm", "Prepare alarm", setOf("hour", "minute"), setOf("label"), confirmation = true),
        ToolSpec("youtube_search", "Search YouTube", setOf("query")),
        ToolSpec("memory_suggest", "Propose a non-sensitive preference for user approval (only when enabled)", setOf("key", "content"), confirmation = true, verification = "Only save after explicit user approval; repository confirms persistence."),
        ToolSpec("media_control", "Play or pause a compatible active media session", setOf("operation"), setOf("package"), verification = "Read back playback state; notification access required."),
        ToolSpec("spotify_search", "Search Spotify", setOf("query")),
    )

    fun validate(id: String?, args: JsonObject): List<String> {
        val spec = tools.firstOrNull { it.id == id } ?: return listOf("Unknown toolId.")
        val errors = mutableListOf<String>()
        if (!args.keys.containsAll(spec.required)) errors += "Missing parameters: ${spec.required - args.keys}."
        if ((args.keys - spec.required - spec.optional).isNotEmpty()) errors += "Unexpected parameters."
        for ((key, value) in args) {
            val p = value as? JsonPrimitive
            if (p == null || value is JsonNull || p.content.length > 4000 || p.content.contains('\u0000')) {
                errors += "Invalid $key."; continue
            }
            when (key) {
                "enabled" -> if (p.isString || p.booleanOrNull == null) errors += "enabled must be boolean."
                "percent", "hour", "minute", "beginMs", "endMs" -> {
                    val n = if (p.isString) null else p.longOrNull
                    val range = when (key) { "percent" -> 0L..100; "hour" -> 0L..23; "minute" -> 0L..59; else -> 1L..Long.MAX_VALUE }
                    if (n == null || n !in range) errors += "Invalid numeric $key."
                }
                else -> if (!p.isString || p.content.isBlank()) errors += "$key must be a nonempty string."
            }
            if (key == "operation" && p.content !in setOf("play", "pause")) errors += "operation must be play or pause."
            if (key == "url" && !Regex("https?://[^\\s/]+(?:/[^\\s]*)?", RegexOption.IGNORE_CASE).matches(p.content)) errors += "Only HTTP(S) URLs are allowed."
            if (key == "package" && !Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(p.content)) errors += "Invalid package."
            if (key == "number" && !Regex("[+0-9 ()-]{1,40}").matches(p.content)) errors += "Invalid phone number."
            if (key == "address" && !Regex("[^@\\s?&#]+@[^@\\s?&#]+\\.[^@\\s?&#]+").matches(p.content)) errors += "Invalid email address."
        }
        val begin = (args["beginMs"] as? JsonPrimitive)?.longOrNull
        val end = (args["endMs"] as? JsonPrimitive)?.longOrNull
        if (end != null && (begin == null || end <= begin)) errors += "endMs must follow beginMs."
        return errors
    }

    fun prompt() = "Native tools (parameters in parentheses):\n" + tools.joinToString("\n") {
        "${it.id}(${it.required.joinToString()}) optional(${it.optional.joinToString()}): ${it.description}; ${it.verification}"
    }
}
