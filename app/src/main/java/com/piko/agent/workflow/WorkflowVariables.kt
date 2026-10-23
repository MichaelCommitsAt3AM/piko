package com.piko.agent.workflow

import kotlinx.serialization.json.*

/** Substitutes data values, never code, component names or arbitrary actions. */
object WorkflowVariables {
    private val pattern = Regex("\\{\\{([a-zA-Z][a-zA-Z0-9_]{0,39})}}")
    fun names(steps: List<WorkflowStep>): Set<String> = steps.flatMap { step ->
        listOf(step.instruction, step.targetText.orEmpty(), step.resourceId.orEmpty()) + step.parameters.values.map { (it as? JsonPrimitive)?.content.orEmpty() }
    }.flatMap { pattern.findAll(it).map { match -> match.groupValues[1] }.toList() }.toSortedSet()

    fun instantiate(steps: List<WorkflowStep>, values: Map<String, String>): List<WorkflowStep> {
        require(values.keys.containsAll(names(steps))) { "Enter all workflow variables." }
        require(values.values.all { it.isNotBlank() && it.length <= 4000 && !it.contains('\u0000') }) { "Invalid workflow variable." }
        fun replace(text: String) = pattern.replace(text) { values.getValue(it.groupValues[1]) }
        return steps.map { step -> step.copy(
            instruction = replace(step.instruction), targetText = step.targetText?.let(::replace), resourceId = step.resourceId?.let(::replace),
            parameters = JsonObject(step.parameters.mapValues { (key, element) ->
                if (element !is JsonPrimitive || !element.isString) element else {
                    val replaced = replace(element.content)
                    when (key) {
                        "percent", "hour", "minute", "beginMs", "endMs" -> JsonPrimitive(replaced.toLongOrNull() ?: error("$key must be numeric."))
                        "enabled" -> JsonPrimitive(replaced.toBooleanStrictOrNull() ?: error("enabled must be true or false."))
                        else -> JsonPrimitive(replaced)
                    }
                }
            }),
        ) }
    }

    fun templateErrors(steps: List<WorkflowStep>): List<String> {
        val samples = names(steps).associateWith { "Sample" }.toMutableMap()
        val prepared = steps.map { step ->
            val sampled = step.parameters.mapValues { (key, element) ->
                if (element is JsonPrimitive && element.isString && pattern.containsMatchIn(element.content)) {
                    when (key) {
                        "percent", "hour", "minute" -> JsonPrimitive(1)
                        "beginMs" -> JsonPrimitive(1000L)
                        "endMs" -> JsonPrimitive(2000L)
                        "enabled" -> JsonPrimitive(true)
                        "url" -> JsonPrimitive("https://example.com/")
                        "package" -> JsonPrimitive("com.example.app")
                        "number" -> JsonPrimitive("123")
                        "address" -> JsonPrimitive("person@example.com")
                        "operation" -> JsonPrimitive("play")
                        else -> JsonPrimitive("Sample")
                    }
                } else element
            }
            step.copy(parameters = JsonObject(sampled))
        }
        return runCatching { WorkflowPolicy.validate(instantiate(prepared, samples)) }.getOrElse { listOf(it.message ?: "Invalid template.") }
    }
}
