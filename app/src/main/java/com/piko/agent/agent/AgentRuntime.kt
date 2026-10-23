package com.piko.agent.agent

import com.piko.agent.core.AgentAction
import com.piko.agent.core.AgentState
import com.piko.agent.core.TokenUsage
import com.piko.agent.model.ScreenObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TimelineEntry(
    val id: Long,
    val step: Int,
    val timestampMs: Long,
    val state: AgentState,
    val title: String,
    val detail: String? = null,
    val actionType: String? = null,
    val isError: Boolean = false,
)

data class PendingConfirmation(
    val id: String,
    val action: AgentAction?,
    val summary: String,
    val reason: String,
    val category: String,
    val requestedAtMs: Long,
)

data class AgentRuntimeState(
    val taskId: String? = null,
    val goal: String = "",
    val lastInstruction: String? = null,
    val state: AgentState = AgentState.IDLE,
    val providerName: String = "",
    val modelName: String = "",
    val currentApp: String? = null,
    val currentAction: String? = null,
    val observationSummary: String? = null,
    val observation: ScreenObservation? = null,
    val screenshotPreviewJpeg: ByteArray? = null,
    val step: Int = 0,
    val maxSteps: Int = 0,
    val startedAtMs: Long? = null,
    val elapsedMs: Long = 0,
    val timeline: List<TimelineEntry> = emptyList(),
    val pendingConfirmation: PendingConfirmation? = null,
    val error: String? = null,
    val resultSummary: String? = null,
    val verified: Boolean? = null,
    val verification: String? = null,
    val usage: TokenUsage = TokenUsage(),
    val pauseReason: String? = null,
    val interrupted: Boolean = false,
    val screenHandoffRequired: Boolean = true,
) {
    val isActive: Boolean get() = state.isRunning || state == AgentState.PAUSED
}

/**
 * Process-wide observable runtime. The foreground service writes; Compose and
 * the floating overlay read. The service is the single owner of task control.
 */
object AgentRuntime {
    private val _state = MutableStateFlow(AgentRuntimeState())
    val state: StateFlow<AgentRuntimeState> = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private var nextTimelineId = 1L

    fun state(): AgentRuntimeState = _state.value

    fun update(transform: (AgentRuntimeState) -> AgentRuntimeState) {
        _state.update(transform)
    }

    fun begin(
        taskId: String,
        goal: String,
        providerName: String,
        modelName: String,
        maxSteps: Int,
        screenHandoffRequired: Boolean = true,
    ) {
        nextTimelineId = 1L
        _state.value = AgentRuntimeState(
            taskId = taskId,
            goal = goal,
            state = AgentState.PREPARING,
            providerName = providerName,
            modelName = modelName,
            maxSteps = maxSteps,
            screenHandoffRequired = screenHandoffRequired,
            startedAtMs = System.currentTimeMillis(),
        )
    }

    fun setState(state: AgentState, pauseReason: String? = null) {
        _state.update {
            it.copy(
                state = state,
                pauseReason = if (state == AgentState.PAUSED) pauseReason else null,
                elapsedMs = it.startedAtMs?.let { start -> System.currentTimeMillis() - start } ?: 0,
            )
        }
    }

    fun setStep(step: Int) {
        _state.update { it.copy(step = step) }
    }

    fun setObservation(observation: ScreenObservation?, preview: ByteArray? = null) {
        _state.update {
            it.copy(
                observation = observation,
                currentApp = observation?.appLabel ?: observation?.foregroundPackage,
                observationSummary = observation?.let { obs ->
                    val kinds = obs.interactiveCount
                    "${obs.appLabel ?: obs.foregroundPackage ?: "Unknown app"} · " +
                        "${obs.elements.size} elements · $kinds interactive" +
                        if (obs.secureScreen) " · secure screen" else ""
                },
                screenshotPreviewJpeg = preview,
                usage = it.usage,
            )
        }
    }

    fun setAction(action: String?) {
        _state.update { it.copy(currentAction = action) }
    }

    fun setPendingConfirmation(confirmation: PendingConfirmation?) {
        _state.update { it.copy(pendingConfirmation = confirmation) }
    }

    fun addUsage(usage: TokenUsage) {
        _state.update { it.copy(usage = it.usage.plus(usage)) }
    }

    fun addTimeline(
        state: AgentState,
        title: String,
        detail: String? = null,
        actionType: String? = null,
        isError: Boolean = false,
        step: Int? = null,
        timestampMs: Long = System.currentTimeMillis(),
    ): TimelineEntry {
        val entry = TimelineEntry(
            id = nextTimelineId++,
            step = step ?: _state.value.step,
            timestampMs = timestampMs,
            state = state,
            title = title,
            detail = detail,
            actionType = actionType,
            isError = isError,
        )
        _state.update { current ->
            current.copy(timeline = (current.timeline + entry).takeLast(300))
        }
        return entry
    }

    fun complete(
        summary: String,
        verified: Boolean?,
        verification: String?,
        state: AgentState,
        error: String? = null,
    ) {
        _state.update {
            it.copy(
                state = state,
                resultSummary = summary,
                verified = verified,
                verification = verification,
                error = error,
                currentAction = null,
                pendingConfirmation = null,
                elapsedMs = it.startedAtMs?.let { start -> System.currentTimeMillis() - start } ?: 0,
            )
        }
    }

    fun markInterrupted(reason: String) {
        _state.update {
            it.copy(
                state = AgentState.FAILED,
                interrupted = true,
                error = reason,
                pendingConfirmation = null,
                currentAction = null,
            )
        }
    }

    fun reset() {
        _state.value = AgentRuntimeState()
        _logs.value = emptyList()
        nextTimelineId = 1L
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun log(message: String, enabled: Boolean, redact: Boolean = false) {
        if (!enabled) return
        val safe = if (redact) "[redacted]" else message.take(2000)
        _logs.update { (it + "${System.currentTimeMillis()}  $safe").takeLast(400) }
    }
}
