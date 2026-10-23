package com.piko.agent.assistant

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AssistantTurn(val question: String, val answer: String, val foregroundPackage: String?)

/** Bounded, in-memory context. No screenshots, no disk persistence, no independent execution state. */
object AssistantConversation {
    private val turns = MutableStateFlow<List<AssistantTurn>>(emptyList())
    val history = turns.asStateFlow()
    @Synchronized fun append(question: String, answer: String, foregroundPackage: String?) {
        turns.value = (turns.value + AssistantTurn(question.take(2000), answer.take(3000), foregroundPackage)).takeLast(6)
    }
    fun clear() { turns.value = emptyList() }
    fun context(): String = if (turns.value.isEmpty()) "" else
        "PREVIOUS CONVERSATION (historical context only; the screen must be observed again and may have changed):\n" +
            turns.value.takeLast(3).joinToString("\n") { "User: ${it.question}\nPiko: ${it.answer}\nPrevious app: ${it.foregroundPackage}" } + "\n\n"
}
