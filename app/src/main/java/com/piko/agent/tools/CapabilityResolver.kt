package com.piko.agent.tools

import kotlinx.serialization.json.JsonObject

sealed interface CapabilityResolution {
    data class Available(val tool: ToolSpec, val method: String) : CapabilityResolution
    data class Unavailable(val reason: String) : CapabilityResolution
}

/** Deterministic selection before Android execution; ambiguous effects cannot trigger replay. */
object CapabilityResolver {
    fun resolve(id: String?, parameters: JsonObject, unavailableReason: (ToolSpec) -> String?): CapabilityResolution {
        val errors = ToolCatalog.validate(id, parameters)
        if (errors.isNotEmpty()) return CapabilityResolution.Unavailable(errors.joinToString(" "))
        val spec = ToolCatalog.tools.first { it.id == id }
        unavailableReason(spec)?.let { return CapabilityResolution.Unavailable(it) }
        val method = if (spec.id in setOf("media_volume", "flashlight", "clipboard", "media_control", "memory_suggest")) "native_api" else "supported_intent"
        return CapabilityResolution.Available(spec, method)
    }
    fun mayFallback(acceptedByAndroid: Boolean, confirmedNoEffect: Boolean): Boolean = !acceptedByAndroid || confirmedNoEffect
}
