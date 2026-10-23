package com.piko.agent.core

object AssistantExecutionPolicy {
    fun permits(screenQuestion: Boolean, action: AgentAction): Boolean = !screenQuestion ||
        action.type in setOf(ActionType.FINISH, ActionType.FAIL, ActionType.OBSERVE)
}
