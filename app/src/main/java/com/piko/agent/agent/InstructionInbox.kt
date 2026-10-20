package com.piko.agent.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select

data class InstructionSnapshot(val revision: Long = 0, val messages: List<String> = emptyList())

/** Direct user instructions invalidate decisions already in flight. */
class InstructionInbox {
    private val mutable = MutableStateFlow(InstructionSnapshot())
    val state = mutable.asStateFlow()

    @Synchronized fun add(text: String): Boolean {
        val clean = text.trim().take(2000)
        if (clean.isEmpty()) return false
        val current = mutable.value
        mutable.value = InstructionSnapshot(current.revision + 1, (current.messages + clean).takeLast(20))
        return true
    }

    suspend fun <T : Any> whileCurrent(revision: Long, block: suspend () -> T): T? = coroutineScope {
        if (state.value.revision != revision) return@coroutineScope null
        val result = async { block() }
        val changed = async { state.first { it.revision != revision } }
        try {
            select {
                result.onAwait { if (state.value.revision == revision) it else null }
                changed.onAwait { null }
            }
        } finally {
            changed.cancel()
            result.cancel()
        }
    }
}
