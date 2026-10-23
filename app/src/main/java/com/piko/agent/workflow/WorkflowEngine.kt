package com.piko.agent.workflow

import com.piko.agent.core.*
import com.piko.agent.model.ScreenObservation
import com.piko.agent.tools.ToolCatalog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class WorkflowStep(
    val kind: String = "ai",
    val instruction: String = "",
    val toolId: String? = null,
    val parameters: JsonObject = JsonObject(emptyMap()),
    val targetText: String? = null,
    val resourceId: String? = null,
    val milliseconds: Long = 500,
    val foregroundPackage: String? = null,
    val skipIfFalse: Int = 0,
    val confirmation: Boolean = false,
    val failurePolicy: String = "stop",
    val maxAttempts: Int = 1,
    val requiredPermissions: List<String> = emptyList(),
)

object WorkflowPolicy {
    fun validate(steps: List<WorkflowStep>): List<String> = buildList {
        if (steps.isEmpty() || steps.size > 40) add("Use 1–40 steps.")
        steps.forEachIndexed { index, step ->
            if (step.maxAttempts !in 1..3 || (step.maxAttempts > 1 && step.kind != "wait" && !(step.kind == "tool" && step.toolId in setOf("media_volume", "flashlight", "media_control")))) add("Retries are allowed only for bounded waits and idempotent native state setters (1–3 attempts).")
            if (step.failurePolicy !in setOf("stop", "continue")) add("Invalid failure policy at step $index.")
            if (step.instruction.length > 2000) add("Instruction too long.")
            when (step.kind) {
                "ai" -> if (step.instruction.isBlank()) add("AI steps require an instruction.")
                "tool" -> addAll(ToolCatalog.validate(step.toolId, step.parameters))
                "tap" -> if (step.targetText.isNullOrBlank() && step.resourceId.isNullOrBlank()) add("Taps require a semantic target.")
                "launch" -> if (step.foregroundPackage.isNullOrBlank() || !Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+").matches(step.foregroundPackage)) add("Launch steps require an installed package name in foregroundPackage.")
                "back", "home" -> Unit
                "wait" -> if (step.milliseconds !in 100..10_000) add("Wait must be 100–10000 ms.")
                "condition" -> if (step.foregroundPackage.isNullOrBlank() || step.skipIfFalse !in 1..steps.size || index + step.skipIfFalse >= steps.size) add("Condition must skip forward to an existing step.")
                else -> add("Unknown step kind.")
            }
        }
    }

    fun action(step: WorkflowStep, observation: ScreenObservation): AgentAction {
        val action = when (step.kind) {
            "tool" -> AgentAction(type = ActionType.NATIVE_TOOL, toolId = step.toolId, parameters = step.parameters)
            "launch" -> AgentAction(type = ActionType.LAUNCH_APP, packageName = step.foregroundPackage)
            "back" -> AgentAction(type = ActionType.BACK)
            "home" -> AgentAction(type = ActionType.HOME)
            "wait" -> AgentAction(type = ActionType.WAIT, milliseconds = step.milliseconds)
            "tap" -> {
                val matches = observation.elements.filter {
                    (step.resourceId == null || it.resourceId == step.resourceId) &&
                        (step.targetText == null || it.text == step.targetText || it.description == step.targetText)
                }
                require(matches.size == 1) { "Semantic target is missing or ambiguous. Review the workflow." }
                AgentAction(type = ActionType.TAP, elementId = matches.single().id)
            }
            else -> error("This step needs AI reasoning.")
        }
        return if (step.confirmation) AgentAction(type = ActionType.REQUEST_CONFIRMATION, summary = "Run workflow step ${step.toolId ?: step.targetText ?: step.kind}?", pendingAction = action) else action
    }
}

class WorkflowEngine {
    /** Called only by the existing AgentService task job; never starts a competing agent. */
    suspend fun run(
        steps: List<WorkflowStep>,
        foregroundPackage: () -> String?,
        execute: suspend (Int, WorkflowStep) -> Boolean,
    ): Boolean {
        require(WorkflowPolicy.validate(steps).isEmpty()) { WorkflowPolicy.validate(steps).joinToString(" ") }
        var index = 0
        var allSucceeded = true
        while (index < steps.size) {
            currentCoroutineContext().ensureActive()
            val step = steps[index]
            if (step.kind == "condition") {
                index += if (foregroundPackage() == step.foregroundPackage) 1 else step.skipIfFalse + 1
                continue
            }
            var ok = false
            repeat(step.maxAttempts) {
                currentCoroutineContext().ensureActive()
                if (!ok) ok = execute(index, step)
            }
            allSucceeded = allSucceeded && ok
            if (!ok && step.failurePolicy == "stop") return false
            index++
        }
        return allSucceeded
    }
}
