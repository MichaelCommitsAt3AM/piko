package com.piko.agent.ai

import com.piko.agent.core.ActionSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The single action tool. Parameters mirror [com.piko.agent.core.AgentAction].
 * The JSON fallback prompt uses the same shape when a model has no tool support.
 */
object ActionToolSchema {
    const val NAME = "execute_action"
    const val DESCRIPTION =
        "Perform exactly one Android UI action from the validated Piko action schema. " +
            "Element IDs are valid only for the observation in this request."

    private val ACTION_PROPERTIES_FOR_NESTED: JsonObject = buildJsonObject {
        putJsonObject("type") { put("type", "string"); put("enum", actionTypes()) }
        putJsonObject("elementId") { put("type", "string") }
        putJsonObject("x") { put("type", "integer") }
        putJsonObject("y") { put("type", "integer") }
        putJsonObject("startX") { put("type", "integer") }
        putJsonObject("startY") { put("type", "integer") }
        putJsonObject("endX") { put("type", "integer") }
        putJsonObject("endY") { put("type", "integer") }
        putJsonObject("durationMs") { put("type", "integer") }
        putJsonObject("direction") { put("type", "string") }
        putJsonObject("text") { put("type", "string") }
        putJsonObject("packageName") { put("type", "string") }
        putJsonObject("toolId") { put("type", "string"); put("enum", JsonArray(com.piko.agent.tools.ToolCatalog.tools.map { kotlinx.serialization.json.JsonPrimitive(it.id) })) }
        putJsonObject("parameters") { put("type", "object") }
    }

    val ACTION_PROPERTIES: JsonObject = buildJsonObject {
        putJsonObject("version") { put("type", "integer"); put("enum", buildJsonArray { add(1) }) }
        putJsonObject("type") { put("type", "string"); put("enum", actionTypes()) }
        putJsonObject("elementId") { put("type", "string"); put("description", "Element id from the current observation, e.g. element_12.") }
        putJsonObject("x") { put("type", "integer") }
        putJsonObject("y") { put("type", "integer") }
        putJsonObject("startX") { put("type", "integer") }
        putJsonObject("startY") { put("type", "integer") }
        putJsonObject("endX") { put("type", "integer") }
        putJsonObject("endY") { put("type", "integer") }
        putJsonObject("durationMs") { put("type", "integer"); put("minimum", 80); put("maximum", 3000) }
        putJsonObject("direction") {
            put("type", "string")
            put("enum", buildJsonArray { add("up"); add("down"); add("left"); add("right") })
        }
        putJsonObject("amount") { put("type", "integer"); put("minimum", 1); put("maximum", 10) }
        putJsonObject("text") { put("type", "string"); put("maxLength", 4000) }
        putJsonObject("packageName") { put("type", "string") }
        putJsonObject("toolId") { put("type", "string"); put("enum", JsonArray(com.piko.agent.tools.ToolCatalog.tools.map { kotlinx.serialization.json.JsonPrimitive(it.id) })) }
        putJsonObject("parameters") { put("type", "object") }
        putJsonObject("appName") { put("type", "string") }
        putJsonObject("milliseconds") { put("type", "integer"); put("minimum", 100); put("maximum", 10000) }
        putJsonObject("includeScreenshot") { put("type", "boolean") }
        putJsonObject("summary") { put("type", "string") }
        putJsonObject("reason") { put("type", "string") }
        putJsonObject("verified") { put("type", "boolean") }
        putJsonObject("verification") { put("type", "string") }
        putJsonObject("message") { put("type", "string") }
        putJsonObject("pendingAction") {
            put("type", "object")
            put("description", "Optional device action to run only after the user approves.")
            put("properties", ACTION_PROPERTIES_FOR_NESTED)
        }
    }

    fun parameters(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("reasoning") {
                put("type", "string")
                put("description", "One or two sentences connecting the observation to this action.")
            }
            putJsonObject("action") {
                put("type", "object")
                put("properties", ACTION_PROPERTIES)
                put("required", buildJsonArray { add("type") })
            }
        }
        put("required", buildJsonArray { add("reasoning"); add("action") })
        put("additionalProperties", false)
    }

    fun promptInstructions(): String = ActionSchema.schemaDescription()

    private fun actionTypes(): JsonArray = buildJsonArray {
        add("tap"); add("long_press"); add("swipe"); add("scroll"); add("type_text")
        add("clear_text"); add("back"); add("home"); add("open_notifications")
        add("launch_app"); add("wait"); add("observe"); add("request_confirmation")
        add("native_tool"); add("finish"); add("fail")
    }
}
