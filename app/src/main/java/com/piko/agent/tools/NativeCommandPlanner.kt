package com.piko.agent.tools

import com.piko.agent.core.*
import kotlinx.serialization.json.*

/** Conservative grammar. Any unsupported clause returns the whole request to the existing AI loop. */
object NativeCommandPlanner {
    fun plan(instruction: String): List<AgentAction>? {
        val clean = instruction.trim().replace(Regex("(?i)^(?:hey\\s+)?piko[, ]+"), "").trimEnd('.')
        val clauses = clean.split(Regex("(?i),\\s*(?:and\\s+)?|\\s+and\\s+")).filter { it.isNotBlank() }
        if (clauses.isEmpty() || clauses.size > 8) return null
        val actions = clauses.map { clause ->
            val text = clause.trim()
            fun tool(id: String, args: JsonObject = buildJsonObject {}) = AgentAction(type = ActionType.NATIVE_TOOL, toolId = id, parameters = args)
            val volume = Regex("(?i)(?:set |adjust )?(?:media )?volume (?:to )?(\\d{1,3})(?:%| percent)?").matchEntire(text)
            val torch = Regex("(?i)turn (on|off) (?:(?:my|the) )?flashlight").matchEntire(text)
            when {
                volume != null -> tool("media_volume", buildJsonObject { put("percent", volume.groupValues[1].toInt()) })
                torch != null -> tool("flashlight", buildJsonObject { put("enabled", torch.groupValues[1].equals("on", true)) })
                text.equals("open wifi settings", true) || text.equals("open wi-fi settings", true) -> tool("wifi_settings")
                text.equals("open bluetooth settings", true) -> tool("bluetooth_settings")
                text.equals("open display settings", true) -> tool("display_settings")
                Regex("(?i)open https?://\\S+").matches(text) -> tool("website", buildJsonObject { put("url", text.substringAfter(' ')) })
                !Regex("(?i)\\b(my|preferred|current|this|that)\\b").containsMatchIn(text) && Regex("(?i)open [\\p{L}0-9 ._-]{1,120}").matches(text) -> AgentAction(type = ActionType.LAUNCH_APP, appName = text.substringAfter(' '))
                else -> return null
            }
        }
        return actions.takeIf { list -> list.all { ActionValidator.validate(it, ScreenBounds(0, 0)) is ActionValidator.ValidationResult.Valid } }
    }
}
