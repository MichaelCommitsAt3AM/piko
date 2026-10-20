package com.piko.agent.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The lifecycle of a Piko task. Persisted names are intentionally stable.
 */
@Serializable
enum class AgentState {
    IDLE,
    PREPARING,
    OBSERVING,
    REASONING,
    EXECUTING,
    VERIFYING,
    AWAITING_USER_CONFIRMATION,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isRunning: Boolean
        get() = this == PREPARING || this == OBSERVING || this == REASONING ||
            this == EXECUTING || this == VERIFYING || this == AWAITING_USER_CONFIRMATION
}

/**
 * Guards transitions so a late callback can never move a completed task back
 * into an executing state. PAUSED is reachable from every non-terminal state.
 */
class AgentStateMachine(initial: AgentState = AgentState.IDLE) {
    var state: AgentState = initial
        private set

    fun canTransitionTo(next: AgentState): Boolean {
        if (next == state) return true
        if (state.isTerminal) return false
        if (next == AgentState.PAUSED) return true
        if (next == AgentState.CANCELLED) return true
        return when (state) {
            AgentState.IDLE -> next == AgentState.PREPARING
            AgentState.PREPARING -> next == AgentState.OBSERVING || next == AgentState.FAILED || next == AgentState.AWAITING_USER_CONFIRMATION
            AgentState.OBSERVING -> next == AgentState.REASONING || next == AgentState.FAILED || next == AgentState.AWAITING_USER_CONFIRMATION
            AgentState.REASONING -> next == AgentState.EXECUTING || next == AgentState.OBSERVING || next == AgentState.COMPLETED ||
                next == AgentState.FAILED || next == AgentState.AWAITING_USER_CONFIRMATION
            AgentState.EXECUTING -> next == AgentState.VERIFYING || next == AgentState.FAILED
            AgentState.VERIFYING -> next == AgentState.OBSERVING || next == AgentState.COMPLETED || next == AgentState.FAILED
            AgentState.AWAITING_USER_CONFIRMATION -> next == AgentState.EXECUTING || next == AgentState.OBSERVING ||
                next == AgentState.REASONING || next == AgentState.FAILED || next == AgentState.PREPARING
            AgentState.PAUSED -> next == AgentState.OBSERVING || next == AgentState.PREPARING ||
                next == AgentState.AWAITING_USER_CONFIRMATION || next == AgentState.FAILED || next == AgentState.CANCELLED
            AgentState.COMPLETED, AgentState.FAILED, AgentState.CANCELLED -> false
        }
    }

    fun transitionTo(next: AgentState): Boolean {
        if (!canTransitionTo(next)) return false
        state = next
        return true
    }
}

@Serializable
enum class ActionType {
    @SerialName("tap") TAP,
    @SerialName("long_press") LONG_PRESS,
    @SerialName("swipe") SWIPE,
    @SerialName("scroll") SCROLL,
    @SerialName("type_text") TYPE_TEXT,
    @SerialName("clear_text") CLEAR_TEXT,
    @SerialName("back") BACK,
    @SerialName("home") HOME,
    @SerialName("open_notifications") OPEN_NOTIFICATIONS,
    @SerialName("launch_app") LAUNCH_APP,
    @SerialName("wait") WAIT,
    @SerialName("observe") OBSERVE,
    @SerialName("request_confirmation") REQUEST_CONFIRMATION,
    @SerialName("finish") FINISH,
    @SerialName("fail") FAIL,
}

@Serializable
enum class ScrollDirection {
    @SerialName("up") UP,
    @SerialName("down") DOWN,
    @SerialName("left") LEFT,
    @SerialName("right") RIGHT,
}

@Serializable
data class AgentAction(
    val version: Int = ACTION_SCHEMA_VERSION,
    val type: ActionType,
    val elementId: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val startX: Int? = null,
    val startY: Int? = null,
    val endX: Int? = null,
    val endY: Int? = null,
    val durationMs: Int? = null,
    val direction: ScrollDirection? = null,
    val amount: Int? = null,
    val text: String? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val milliseconds: Long? = null,
    val includeScreenshot: Boolean? = null,
    val summary: String? = null,
    val reason: String? = null,
    val pendingAction: AgentAction? = null,
    val verified: Boolean? = null,
    val verification: String? = null,
    val message: String? = null,
) {
    companion object {
        const val ACTION_SCHEMA_VERSION = 1
    }
}

@Serializable
data class ScreenBounds(
    val width: Int,
    val height: Int,
) {
    init {
        require(width >= 0 && height >= 0)
    }

    val isValid: Boolean get() = width > 0 && height > 0
}

fun interface Clock {
    fun nowMillis(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

val PikoJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = false
    coerceInputValues = true
}
