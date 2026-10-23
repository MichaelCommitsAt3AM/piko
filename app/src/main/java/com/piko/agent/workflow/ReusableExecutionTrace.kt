package com.piko.agent.workflow

import com.piko.agent.core.*
import com.piko.agent.model.ScreenObservation
import com.piko.agent.tools.ToolCatalog

/** Temporary review material only. Never persists screen text, screenshots or coordinate scripts. */
object ReusableExecutionTrace {
    private var goal = ""
    private val steps = mutableListOf<WorkflowStep>()
    private var faithful = true
    @Synchronized fun begin(instruction: String) { goal = instruction; steps.clear(); faithful = true }
    @Synchronized fun recordNative(action: AgentAction, verified: Boolean, launchPackage: String?) {
        if (!verified) { faithful = false; return }
        when (action.type) {
            ActionType.NATIVE_TOOL -> steps += WorkflowStep(kind = "tool", toolId = action.toolId, parameters = action.parameters)
            ActionType.LAUNCH_APP -> if (launchPackage != null) steps += WorkflowStep(kind = "launch", foregroundPackage = launchPackage) else faithful = false
            else -> faithful = false
        }
    }
    @Synchronized fun record(action: AgentAction, before: ScreenObservation, verified: Boolean, launchPackage: String?) {
        if (!verified) { faithful = false; return }
        val step = when (action.type) {
            ActionType.NATIVE_TOOL -> {
                val tool = ToolCatalog.tools.firstOrNull { it.id == action.toolId }
                if (tool == null || tool.confirmation || tool.id == "memory_suggest") null
                else WorkflowStep(kind = "tool", toolId = action.toolId, parameters = action.parameters, requiredPermissions = tool.permissions.toList())
            }
            ActionType.LAUNCH_APP -> launchPackage?.let { WorkflowStep(kind = "launch", foregroundPackage = it) }
            ActionType.WAIT -> WorkflowStep(kind = "wait", milliseconds = action.milliseconds ?: 500)
            ActionType.BACK -> WorkflowStep(kind = "back")
            ActionType.HOME -> WorkflowStep(kind = "home")
            ActionType.TAP -> {
                val target = before.elements.firstOrNull { it.id == action.elementId }
                    ?: before.elements.filter { action.x != null && action.y != null && it.bounds.contains(action.x, action.y) }
                        .minByOrNull { it.bounds.width.toLong() * it.bounds.height }
                if (target == null || target.password || (target.resourceId == null && target.text.isNullOrBlank())) null
                else WorkflowStep(kind = "tap", targetText = target.text?.takeIf { it != "[redacted]" }, resourceId = target.resourceId)
            }
            else -> null
        }
        if (step == null) faithful = false else steps += step
    }
    @Synchronized fun draft(): List<WorkflowStep> = if (faithful && steps.isNotEmpty()) steps.toList()
        else listOf(WorkflowStep(instruction = goal.ifBlank { "Review and describe the task to repeat" }))
}
