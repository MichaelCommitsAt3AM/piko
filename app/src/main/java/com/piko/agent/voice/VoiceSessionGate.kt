package com.piko.agent.voice

/** Invalidates callbacks from cancelled/replaced recognizers before they can change the transcript. */
class VoiceSessionGate {
    enum class State { IDLE, STARTING, LISTENING, TRANSCRIBING, COMPLETE, ERROR, CANCELLED }
    var state = State.IDLE
        private set
    private var revision = 0L
    fun begin(): Long { revision++; state = State.STARTING; return revision }
    fun isCurrent(token: Long): Boolean = token == revision && state in setOf(State.STARTING, State.LISTENING, State.TRANSCRIBING)
    fun move(token: Long, next: State): Boolean {
        if (!isCurrent(token)) return false
        state = next; return true
    }
    fun cancel() { revision++; state = State.CANCELLED }
}
