package com.piko.agent.agent

import android.content.Context
import com.piko.agent.accessibility.AccessibilityBridge
import com.piko.agent.accessibility.ActionExecutor
import com.piko.agent.accessibility.ExecutionResult
import com.piko.agent.accessibility.ObservationEngine
import com.piko.agent.ai.AgentPromptBuilder
import com.piko.agent.ai.LlmClient
import com.piko.agent.ai.LlmException
import com.piko.agent.ai.LlmRequest
import com.piko.agent.ai.ObservationFormatter
import com.piko.agent.ai.PromptInput
import com.piko.agent.ai.TurnContext
import com.piko.agent.core.ActionParser
import com.piko.agent.core.ActionValidator
import com.piko.agent.core.ActionType
import com.piko.agent.core.AgentAction
import com.piko.agent.core.AgentState
import com.piko.agent.core.AgentStateMachine
import com.piko.agent.core.LoopDetector
import com.piko.agent.core.PikoJson
import com.piko.agent.core.SensitiveActionClassifier
import com.piko.agent.core.SensitiveTextRedactor
import com.piko.agent.core.TokenUsage
import com.piko.agent.data.TaskRepository
import com.piko.agent.model.ElementRef
import com.piko.agent.model.ModelConfig
import com.piko.agent.model.ObservationResult
import com.piko.agent.model.PikoSettings
import com.piko.agent.model.ProviderConfig
import com.piko.agent.model.ScreenObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The iterative observe -> reason -> act -> verify loop. Exactly one instance
 * owns the device at a time; the foreground service enforces that.
 */
class AgentController(
    private val context: Context,
    private val taskId: String,
    private val goal: String,
    private val settings: PikoSettings,
    private val provider: ProviderConfig,
    private val model: ModelConfig,
    private val apiKey: String,
    private val taskRepository: TaskRepository,
    private val llmClient: LlmClient,
    private val observationEngine: ObservationEngine,
    private val actionExecutor: ActionExecutor,
) {
    private val stateMachine = AgentStateMachine()
    private val loopDetector = LoopDetector()
    private val pauseGate = MutableStateFlow(false)
    private var pausedAtMs: Long? = null
    private var totalPausedMs: Long = 0

    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    @Volatile private var finished = false

    private var confirmationDeferred: CompletableDeferred<Boolean>? = null
    private var totalUsage = TokenUsage()
    private var stepCount = 0
    private var safetyBlocks = 0
    private var parseFailures = 0
    private var observationFailures = 0
    private var screenshotRequested = false
    private var pendingWarnings = mutableListOf<String>()
    private var lastPromptFingerprint: String? = null
    private var lastActionChanged = false
    private var sameScreenCount = 0

    val isFinished: Boolean get() = finished

    fun pause(reason: String) {
        if (finished || stopRequested) return
        if (AgentRuntime.state().state.isTerminal) return
        pauseRequested = true
        pauseGate.value = true
        pausedAtMs = System.currentTimeMillis()
        val pending = AgentRuntime.state().pendingConfirmation != null
        setState(
            AgentState.PAUSED,
            if (pending) "$reason You can still approve or deny the pending action." else reason,
        )
        AgentRuntime.addTimeline(AgentState.PAUSED, "Paused", reason)
    }

    fun resume() {
        if (finished || stopRequested) return
        pauseRequested = false
        pauseGate.value = false
        pausedAtMs?.let { paused ->
            totalPausedMs += (System.currentTimeMillis() - paused).coerceAtLeast(0)
        }
        pausedAtMs = null
        val pending = AgentRuntime.state().pendingConfirmation
        setState(if (pending != null) AgentState.AWAITING_USER_CONFIRMATION else AgentState.OBSERVING)
        AgentRuntime.addTimeline(AgentState.OBSERVING, "Resumed", "The user resumed the task.")
    }

    fun requestStop() {
        if (finished) return
        stopRequested = true
        pauseRequested = false
        pauseGate.value = false
        confirmationDeferred?.complete(false)
        AgentRuntime.setPendingConfirmation(null)
        setState(AgentState.CANCELLED)
        AgentRuntime.addTimeline(AgentState.CANCELLED, "Stopped by user", "No further actions will be taken.")
    }

    fun approveConfirmation(): Boolean {
        val deferred = confirmationDeferred ?: return false
        if (deferred.isCompleted) return false
        deferred.complete(true)
        return true
    }

    fun denyConfirmation(): Boolean {
        val deferred = confirmationDeferred ?: return false
        if (deferred.isCompleted) return false
        deferred.complete(false)
        return true
    }

    suspend fun run() {
        var terminalHandled = false
        try {
            terminalHandled = false
            if (settings.historyEnabled) {
                runCatching {
                    taskRepository.startTask(taskId, goal, provider.name, model.label)
                }
            }
            AgentRuntime.begin(taskId, goal, provider.name, model.label, settings.maxSteps)
            transition(AgentState.PREPARING)
            AgentRuntime.addTimeline(
                AgentState.PREPARING,
                "Preparing task",
                "${provider.name} · ${model.label} · limit ${settings.maxSteps} steps",
            )
            if (apiKey.isBlank()) {
                finish(
                    status = AgentState.FAILED,
                    summary = null,
                    error = "No API key is configured for ${provider.name}.",
                    verified = false,
                    verification = null,
                    terminalHandled = true,
                )
                return
            }
            if (!AccessibilityBridge.connected.value) {
                finish(
                    status = AgentState.FAILED,
                    summary = null,
                    error = "Accessibility is not connected. Enable Piko in Android Settings > Accessibility.",
                    verified = false,
                    verification = null,
                    terminalHandled = true,
                )
                return
            }

            val startedAt = System.currentTimeMillis()
            val maxDurationMs = settings.maxDurationMinutes * 60_000L
            val history = mutableListOf<TurnContext>()
            var observation: ScreenObservation? = null
            var refs: Map<String, ElementRef> = emptyMap()
            var step = 0
            var initialObservation = observeWithRetry(includeScreenshot = false)
            observation = initialObservation.observation
            refs = initialObservation.refs
            if (observation == null) {
                finish(
                    AgentState.FAILED,
                    null,
                    initialObservation.error ?: "Piko could not inspect the screen.",
                    false,
                    null,
                    terminalHandled = true,
                )
                return
            }

            while (step < settings.maxSteps) {
                throwIfStopped()
                if (activeElapsed(startedAt) > maxDurationMs) {
                    finish(
                        AgentState.FAILED,
                        "Task stopped at the configured time limit.",
                        "Maximum task duration (${settings.maxDurationMinutes} minutes) reached.",
                        false,
                        null,
                        terminalHandled = true,
                    )
                    return
                }
                if (pauseGate.value) awaitResume()
                throwIfStopped()
                checkAccessibility()
                if (pauseGate.value) awaitResume()
                throwIfStopped()

                step++
                stepCount = step
                AgentRuntime.setStep(step)

                transition(AgentState.OBSERVING)
                AgentRuntime.addTimeline(
                    AgentState.OBSERVING,
                    "Observing screen",
                    "Step $step of ${settings.maxSteps}",
                    step = step,
                )

                val includeScreenshot = shouldIncludeScreenshot(observation!!)
                val result = observeWithRetry(includeScreenshot)
                if (result.observation == null) {
                    observationFailures++
                    val error = result.error ?: "The screen could not be inspected."
                    if (isLockError(error) && settings.autoPauseOnLock) {
                        pause("The device is locked.")
                        awaitResume()
                        continue
                    }
                    if (error.contains("Accessibility", ignoreCase = true)) {
                        pause("Accessibility disconnected.")
                        awaitResume()
                        checkAccessibility(forceFail = true)
                        continue
                    }
                    if (observationFailures >= 3) {
                        finish(
                            AgentState.FAILED,
                            null,
                            error,
                            false,
                            null,
                            terminalHandled = true,
                        )
                        return
                    }
                    pendingWarnings += "Observation failed: $error"
                    delay(350)
                    continue
                }
                observationFailures = 0
                observation = result.observation
                refs = result.refs
                val currentObservation = result.observation
                if (AgentRuntime.state().state != AgentState.PAUSED) {
                    transition(AgentState.OBSERVING)
                }
                AgentRuntime.setObservation(
                    currentObservation,
                    preview = result.screenshot?.bytes,
                )
                AgentRuntime.log(
                    "step=$step observe app=${currentObservation.foregroundPackage} " +
                        "elements=${currentObservation.elements.size} fingerprint=${currentObservation.fingerprint.take(12)} " +
                        "screenshot=${result.screenshot?.source ?: "none"}",
                    settings.debugLogging,
                )

                val fingerprint = currentObservation.fingerprint
                sameScreenCount = if (lastPromptFingerprint != null &&
                    fingerprint == lastPromptFingerprint &&
                    !lastActionChanged
                ) {
                    sameScreenCount + 1
                } else {
                    0
                }
                lastPromptFingerprint = fingerprint
                lastActionChanged = false

                if (pauseGate.value) awaitResume()
                throwIfStopped()

                if (model.supportsVision && settings.sendScreenshots && currentObservation.secureScreen) {
                    pendingWarnings += "The current screen is protected from screenshots. Use accessibility information only."
                }
                if (currentObservation.hasDialog) {
                    pendingWarnings += "A dialog is visible. Read it carefully; do not approve authentication or permissions automatically."
                }
                if (sameScreenCount >= 2) {
                    pendingWarnings += "The screen has not changed for ${sameScreenCount + 1} observations. Stop repeating and try a different approach."
                }

                transition(AgentState.REASONING)
                val request = LlmRequest(
                    systemPrompt = AgentPromptBuilder.systemPrompt(),
                    userPrompt = AgentPromptBuilder.userPrompt(
                        PromptInput(
                            goal = goal,
                            step = step,
                            maxSteps = settings.maxSteps,
                            observation = currentObservation,
                            history = history,
                            warnings = pendingWarnings.toList(),
                            screenshotIncluded = result.screenshot != null,
                            sameScreenCount = sameScreenCount,
                            usingNativeTools = model.supportsTools,
                        ),
                    ),
                    imageJpeg = result.screenshot?.bytes,
                    sessionId = AgentPromptBuilder.freshSessionId(taskId),
                    maxOutputTokens = model.maxOutputTokens,
                    allowTools = model.supportsTools,
                    stream = settings.streamResponses,
                )
                AgentRuntime.setAction("Asking ${model.label} for the next step")
                AgentRuntime.log(
                    "step=$step request provider=${provider.name} model=${model.id} " +
                        "tools=${model.supportsTools} screenshot=${result.screenshot != null}",
                    settings.debugLogging,
                )
                val response = try {
                    llmClient.complete(
                        config = provider,
                        model = model,
                        apiKey = apiKey,
                        request = request,
                        allowInsecureEndpoints = settings.allowInsecureEndpoints,
                        onRetry = { message ->
                            AgentRuntime.addTimeline(
                                AgentState.REASONING,
                                "Provider retry",
                                message,
                                step = step,
                            )
                        },
                    )
                } catch (error: LlmException) {
                    finish(
                        AgentState.FAILED,
                        null,
                        error.userMessage(),
                        false,
                        null,
                        terminalHandled = true,
                    )
                    return
                }

                val cost = TokenUsage.estimate(
                    inputTokens = response.usage.inputTokens,
                    outputTokens = response.usage.outputTokens,
                    cachedInputTokens = response.usage.cachedInputTokens,
                    inputPricePerMillion = model.inputPricePerMillion,
                    outputPricePerMillion = model.outputPricePerMillion,
                )
                totalUsage = totalUsage.plus(cost)
                AgentRuntime.addUsage(cost)
                AgentRuntime.log(
                    "step=$step response tool=${response.toolName ?: "json"} " +
                        "tokensIn=${response.usage.inputTokens} tokensOut=${response.usage.outputTokens} " +
                        "estimated=${response.usageEstimated}",
                    settings.debugLogging,
                )
                if (pauseGate.value) awaitResume()
                throwIfStopped()
                if (settings.maxCostUsd > 0 && totalUsage.costUsd > settings.maxCostUsd) {
                    finish(
                        AgentState.FAILED,
                        "Task stopped at the configured spending limit.",
                        "Estimated cost $${"%.4f".format(totalUsage.costUsd)} exceeded the $${"%.2f".format(settings.maxCostUsd)} limit.",
                        false,
                        null,
                        terminalHandled = true,
                    )
                    return
                }

                val parsed = ActionParser.parse(response.actionCandidate)
                val action = when (parsed) {
                    is ActionParser.ActionParseResult.Success -> parsed.action
                    is ActionParser.ActionParseResult.Failure -> {
                        parseFailures++
                        pendingWarnings += "Your previous response was invalid: ${parsed.message}. Return exactly one valid action."
                        AgentRuntime.addTimeline(
                            AgentState.REASONING,
                            "Invalid model action",
                            parsed.message,
                            isError = true,
                            step = step,
                        )
                        if (parseFailures >= 3) {
                            finish(
                                AgentState.FAILED,
                                null,
                                "The model repeatedly returned invalid actions: ${parsed.message}",
                                false,
                                null,
                                terminalHandled = true,
                            )
                            return
                        }
                        continue
                    }
                }
                parseFailures = 0

                when (val validation = ActionValidator.validate(action, currentObservation.bounds)) {
                    is ActionValidator.ValidationResult.Invalid -> {
                        pendingWarnings += "Your action was rejected: ${validation.errors.joinToString(" ")}"
                        AgentRuntime.addTimeline(
                            AgentState.REASONING,
                            "Action rejected by validator",
                            validation.errors.joinToString(" "),
                            isError = true,
                            step = step,
                        )
                        continue
                    }
                    is ActionValidator.ValidationResult.Valid -> Unit
                }
                invalidElementReference(action, refs)?.let { message ->
                    pendingWarnings += "Your action was rejected: $message"
                    AgentRuntime.addTimeline(
                        AgentState.REASONING,
                        "Unknown element reference",
                        message,
                        isError = true,
                        step = step,
                    )
                    continue
                }

                if (action.type == ActionType.WAIT) {
                    // Wait is still a real action but it does not need a new
                    // observation to be considered successful.
                }

                if (action.type == ActionType.FINISH) {
                    val summary = action.summary ?: action.message ?: "Task completed."
                    val evidence = action.verification
                    val verified = action.verified == true && !evidence.isNullOrBlank()
                    val finalSummary = if (action.verified == true && evidence.isNullOrBlank()) {
                        "$summary (unverified: no evidence provided)"
                    } else {
                        summary
                    }
                    finish(
                        AgentState.COMPLETED,
                        finalSummary,
                        null,
                        verified,
                        evidence,
                        terminalHandled = true,
                    )
                    return
                }
                if (action.type == ActionType.FAIL) {
                    finish(
                        AgentState.FAILED,
                        action.summary,
                        action.message ?: "The agent reported that the task could not be completed.",
                        false,
                        null,
                        terminalHandled = true,
                    )
                    return
                }

                var actionToExecute = action
                var preApproved = false
                var approvedExecution = false
                if (action.type == ActionType.REQUEST_CONFIRMATION) {
                    val pendingAction = action.pendingAction
                    val pendingSensitivity = pendingAction?.let { pending ->
                        SensitiveActionClassifier.classify(
                            sensitivityContext(pending, currentObservation, refs),
                        )
                    }
                    if (pendingSensitivity?.isBlocked == true) {
                        safetyBlocks++
                        pendingWarnings += "Safety policy blocked the proposed action: ${pendingSensitivity.reason}"
                        AgentRuntime.addTimeline(
                            AgentState.REASONING,
                            "Blocked by safety policy",
                            pendingSensitivity.reason,
                            isError = true,
                            step = step,
                        )
                        if (safetyBlocks >= 3) {
                            finish(
                                AgentState.FAILED,
                                null,
                                "Piko stopped because the model repeatedly attempted an action that violates its safety policy.",
                                false,
                                null,
                                terminalHandled = true,
                            )
                            return
                        }
                        continue
                    }
                    val summary = buildString {
                        append(action.summary ?: "May Piko perform this action?")
                        if (pendingAction != null) {
                            append("\n\nAction: ")
                            append(confirmationSummary(pendingAction, currentObservation, refs))
                        }
                    }
                    val reason = listOfNotNull(
                        action.reason,
                        pendingSensitivity?.reason?.takeIf { it.isNotBlank() },
                    ).joinToString(" ").ifBlank { "The agent requested explicit approval." }
                    val approved = askConfirmation(
                        action = pendingAction ?: action,
                        summary = summary,
                        reason = reason,
                        category = pendingSensitivity?.category?.name?.takeIf { it != "NONE" } ?: "Agent request",
                        step = step,
                    )
                    if (!approved) {
                        pendingWarnings += "The user denied the proposed action: $summary. Do not repeat it without a different approach."
                        history += TurnContext(
                            step = step,
                            action = action,
                            accepted = false,
                            verifiedChange = false,
                            observationBrief = ObservationFormatter.summarize(currentObservation),
                            foregroundPackage = currentObservation.foregroundPackage,
                            note = "User denied confirmation",
                        )
                        if (pendingAction != null) continue
                        continue
                    }
                    if (pendingAction == null) {
                        pendingWarnings += "The user approved the request but no action was attached. Continue with the next appropriate action."
                        continue
                    }
                    actionToExecute = pendingAction
                    preApproved = true
                    approvedExecution = true
                }

                if (actionToExecute.type == ActionType.OBSERVE) {
                    screenshotRequested = actionToExecute.includeScreenshot == true
                    pendingWarnings += "A fresh observation was requested. Use the next observation to decide."
                    if (!screenshotRequested) {
                        sameScreenCount = 0
                    }
                    continue
                }

                val sensitivity = SensitiveActionClassifier.classify(
                    sensitivityContext(actionToExecute, currentObservation, refs),
                )
                if (sensitivity.isBlocked) {
                    safetyBlocks++
                    pendingWarnings += "Safety policy blocked the action: ${sensitivity.reason}"
                    AgentRuntime.addTimeline(
                        AgentState.REASONING,
                        "Blocked by safety policy",
                        sensitivity.reason,
                        isError = true,
                        step = step,
                    )
                    history += TurnContext(
                        step = step,
                        action = actionToExecute,
                        accepted = false,
                        verifiedChange = false,
                        observationBrief = ObservationFormatter.summarize(currentObservation),
                        foregroundPackage = currentObservation.foregroundPackage,
                        note = "Blocked by Piko safety policy",
                    )
                    if (safetyBlocks >= 3) {
                        finish(
                            AgentState.FAILED,
                            null,
                            "Piko stopped because the model repeatedly attempted an action that violates its safety policy.",
                            false,
                            null,
                            terminalHandled = true,
                        )
                        return
                    }
                    continue
                }
                if (sensitivity.requiresConfirmation && settings.confirmSensitiveActions && !preApproved) {
                    val approved = askConfirmation(
                        action = actionToExecute,
                        summary = confirmationSummary(actionToExecute, currentObservation, refs),
                        reason = sensitivity.reason,
                        category = sensitivity.category.name,
                        step = step,
                    )
                    if (!approved) {
                        pendingWarnings += "The user denied this sensitive action. Do not repeat it. Ask for guidance or fail."
                        history += TurnContext(
                            step = step,
                            action = actionToExecute,
                            accepted = false,
                            verifiedChange = false,
                            observationBrief = ObservationFormatter.summarize(currentObservation),
                            foregroundPackage = currentObservation.foregroundPackage,
                            note = "User denied confirmation",
                        )
                        continue
                    }
                    approvedExecution = true
                }

                if (pauseGate.value) awaitResume()
                throwIfStopped()

                if (!verifyLiveContext(currentObservation, actionToExecute, approvedExecution)) {
                    pendingWarnings += "The screen changed or Piko's own interface was in front before the action could run. Observe again and choose a safe next action."
                    AgentRuntime.addTimeline(
                        AgentState.REASONING,
                        "Action withheld",
                        "The foreground screen changed before execution. Piko re-observes instead of acting on stale coordinates.",
                        isError = true,
                        step = step,
                    )
                    continue
                }

                if (pauseGate.value) awaitResume()
                throwIfStopped()
                if (stateMachine.state != AgentState.REASONING) {
                    transition(AgentState.REASONING)
                }
                transition(AgentState.EXECUTING)
                if (pauseGate.value) awaitResume()
                throwIfStopped()
                val actionBrief = ObservationFormatter.actionBrief(actionToExecute)
                AgentRuntime.setAction(actionLabel(actionToExecute, refs))
                AgentRuntime.addTimeline(
                    AgentState.EXECUTING,
                    actionLabel(actionToExecute, refs),
                    "Step $step",
                    actionType = actionToExecute.type.name,
                    step = step,
                )

                val execution = try {
                    actionExecutor.execute(actionToExecute, currentObservation, refs, settings)
                } catch (error: CancellationException) {
                    throw error
                }
                AgentRuntime.addTimeline(
                    if (execution.success) AgentState.VERIFYING else AgentState.EXECUTING,
                    if (execution.success) "Action dispatched" else "Action failed",
                    execution.message,
                    actionType = actionToExecute.type.name,
                    isError = !execution.success,
                    step = step,
                )
                AgentRuntime.log(
                    "step=$step action=${actionToExecute.type.name} target=${actionToExecute.elementId ?: actionToExecute.packageName ?: "coords"} " +
                        "success=${execution.success} gesture=${execution.gestureUsed}",
                    settings.debugLogging,
                )

                transition(AgentState.VERIFYING)
                settle(
                    if (actionToExecute.type == ActionType.LAUNCH_APP) {
                        maxOf(settings.settleTimeoutMs, 3_500)
                    } else {
                        settings.settleTimeoutMs
                    },
                )
                if (pauseGate.value) awaitResume()
                val afterResult = observeWithRetry(includeScreenshot = false)
                val afterObservation = afterResult.observation
                val changed = afterObservation != null &&
                    afterObservation.fingerprint != currentObservation.fingerprint
                lastActionChanged = changed
                if (afterObservation != null) {
                    AgentRuntime.setObservation(afterObservation, preview = null)
                }
                val verificationNote = buildVerificationNote(
                    actionToExecute,
                    currentObservation,
                    afterObservation,
                    execution,
                    refs,
                )
                val outcomeNote = listOfNotNull(
                    execution.message,
                    verificationNote,
                    if (afterObservation == null) "Post-action observation failed: ${afterResult.error}" else null,
                ).joinToString(" ")
                AgentRuntime.addTimeline(
                    AgentState.VERIFYING,
                    if (changed) "Verified a visible change" else "No visible change detected",
                    outcomeNote,
                    actionType = actionToExecute.type.name,
                    isError = !execution.success,
                    step = step,
                )

                history += TurnContext(
                    step = step,
                    action = actionToExecute,
                    accepted = execution.success,
                    verifiedChange = changed,
                    observationBrief = ObservationFormatter.summarize(afterObservation ?: currentObservation),
                    foregroundPackage = afterObservation?.foregroundPackage ?: currentObservation.foregroundPackage,
                    note = verificationNote,
                )
                if (history.size > 30) history.removeAt(0)

                if (actionToExecute.type == ActionType.LAUNCH_APP &&
                    execution.launchPackage != null &&
                    afterObservation != null &&
                    afterObservation.foregroundPackage != execution.launchPackage
                ) {
                    pendingWarnings += "The requested app did not become the foreground app. Android may have blocked the background launch. The current foreground package is ${afterObservation.foregroundPackage}."
                }

                persistStep(
                    step = step,
                    action = actionToExecute,
                    execution = execution,
                    observation = currentObservation,
                    verification = outcomeNote,
                    screenshotIncluded = result.screenshot != null,
                )
                persistProgress()

                val loopSignal = loopDetector.record(
                    fingerprint = afterObservation?.fingerprint ?: currentObservation.fingerprint,
                    action = actionToExecute,
                )
                when (loopSignal) {
                    is LoopDetector.LoopSignal.Abort -> {
                        finish(
                            AgentState.FAILED,
                            null,
                            "Loop detected: ${loopSignal.reason}",
                            false,
                            null,
                            terminalHandled = true,
                        )
                        return
                    }
                    is LoopDetector.LoopSignal.Warn -> pendingWarnings += loopSignal.reason
                    LoopDetector.LoopSignal.None -> Unit
                }

                screenshotRequested = false
                if (step >= settings.maxSteps) {
                    finish(
                        AgentState.FAILED,
                        "Task stopped at the configured step limit.",
                        "Maximum steps (${settings.maxSteps}) reached without a verified completion.",
                        false,
                        null,
                        terminalHandled = true,
                    )
                    return
                }
            }
            if (!finished) {
                finish(
                    AgentState.FAILED,
                    "Task stopped at the configured step limit.",
                    "Maximum steps (${settings.maxSteps}) reached.",
                    false,
                    null,
                    terminalHandled = true,
                )
            }
        } catch (cancelled: CancellationException) {
            val wasUserStop = stopRequested
            withContext(NonCancellable) {
                finishInternal(
                    status = if (wasUserStop) AgentState.CANCELLED else AgentState.FAILED,
                    summary = if (wasUserStop) "Task cancelled by the user." else "Task interrupted.",
                    error = if (wasUserStop) null else "The Piko task service was interrupted.",
                    verified = false,
                    verification = null,
                )
            }
            terminalHandled = true
            throw cancelled
        } catch (error: Exception) {
            if (!terminalHandled) {
                withContext(NonCancellable) {
                    finishInternal(
                        AgentState.FAILED,
                        null,
                        "Piko stopped: ${error.message ?: error.javaClass.simpleName}",
                        false,
                        null,
                    )
                }
                terminalHandled = true
            }
        } finally {
            if (!terminalHandled && !finished) {
                withContext(NonCancellable) {
                    finishInternal(
                        AgentState.FAILED,
                        null,
                        "The task ended unexpectedly.",
                        false,
                        null,
                    )
                }
            }
        }
    }

    private suspend fun observeWithRetry(includeScreenshot: Boolean): ObservationResult {
        var last = ObservationResult(null, error = "The screen could not be inspected.")
        repeat(2) { attempt ->
            throwIfStopped()
            val settingsWithScreenshot = if (includeScreenshot) settings else settings.copy(sendScreenshots = false)
            last = try {
                observationEngine.observe(settingsWithScreenshot, includeScreenshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ObservationResult(null, error = "Observation failed: ${error.message ?: error.javaClass.simpleName}")
            }
            if (last.observation != null) return last
            if (attempt == 0) delay(250)
        }
        if (last.observation == null && isSelfForegroundError(last.error)) {
            return waitForScreenHandoff(includeScreenshot) ?: last
        }
        return last
    }

    /**
     * Android only exposes one foreground app to accessibility. Piko's own UI
     * cannot be observed while the agent acts, so it briefly pauses and waits
     * for the user (or the automatic handoff) to leave Piko. It never resumes
     * after a process death; this is an in-process handoff only.
     */
    private suspend fun waitForScreenHandoff(includeScreenshot: Boolean): ObservationResult? {
        autoPause("Piko's interface is in the foreground. Move to another app — Piko will continue automatically.")
        AgentRuntime.addTimeline(
            AgentState.PAUSED,
            "Waiting for the screen",
            "Piko cannot operate itself. Return to the home screen or another app; the task resumes automatically.",
        )
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            throwIfStopped()
            if (pauseGate.value) awaitResume()
            delay(450)
            val probe = try {
                observationEngine.observe(settings.copy(sendScreenshots = false), includeScreenshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ObservationResult(null, error = "Observation failed")
            }
            if (probe.observation != null) {
                if (pauseGate.value) {
                    awaitResume()
                } else {
                    setState(AgentState.OBSERVING)
                }
                AgentRuntime.addTimeline(
                    AgentState.OBSERVING,
                    "Screen handed over to Piko",
                    probe.observation.appLabel ?: probe.observation.foregroundPackage,
                )
                return probe
            }
        }
        return null
    }

    private fun autoPause(reason: String) {
        if (finished || stopRequested) return
        if (AgentRuntime.state().state.isTerminal) return
        stateMachine.transitionTo(AgentState.PAUSED)
        AgentRuntime.setState(AgentState.PAUSED, reason)
    }

    private fun isSelfForegroundError(error: String?): Boolean =
        error?.contains("Piko's own interface", ignoreCase = true) == true

    private suspend fun verifyLiveContext(
        before: ScreenObservation,
        action: AgentAction,
        afterUserApproval: Boolean,
    ): Boolean {
        val livePackage = try {
            AccessibilityBridge.service?.rootInActiveWindow?.packageName?.toString()
        } catch (_: Exception) {
            null
        } ?: return false
        if (livePackage == context.packageName) {
            val handoff = waitForScreenHandoff(false) ?: return false
            val fresh = handoff.observation ?: return false
            return verifyApprovedTarget(before, action, fresh)
        }
        if (before.foregroundPackage != null && livePackage != before.foregroundPackage) {
            return false
        }
        if (afterUserApproval) {
            val fresh = observeWithRetry(includeScreenshot = false).observation ?: return false
            return verifyApprovedTarget(before, action, fresh)
        }
        return true
    }

    /**
     * An approval is tied to a target. If the screen changed while the user was
     * deciding, an element action is allowed only when the same labelled element
     * is still present near the same place. Coordinate gestures require an
     * unchanged fingerprint.
     */
    private fun verifyApprovedTarget(
        before: ScreenObservation,
        action: AgentAction,
        fresh: ScreenObservation,
    ): Boolean {
        if (before.foregroundPackage != null && fresh.foregroundPackage != before.foregroundPackage) {
            return false
        }
        val elementId = action.elementId
        if (elementId == null) {
            return when (action.type) {
                ActionType.LAUNCH_APP, ActionType.WAIT, ActionType.BACK, ActionType.HOME,
                ActionType.OPEN_NOTIFICATIONS -> true
                else -> fresh.fingerprint == before.fingerprint
            }
        }
        val reference = before.elements.firstOrNull { it.id == elementId } ?: return false
        val match = fresh.elements
            .filter { candidate ->
                (reference.resourceId != null && candidate.resourceId == reference.resourceId) ||
                    (reference.text != null && reference.text != "[redacted]" && candidate.text == reference.text) ||
                    (reference.description != null && candidate.description == reference.description)
            }
            .minByOrNull { candidate ->
                kotlin.math.abs(candidate.bounds.centerX - reference.bounds.centerX) +
                    kotlin.math.abs(candidate.bounds.centerY - reference.bounds.centerY)
            }
            ?: return false
        val distance = kotlin.math.abs(match.bounds.centerX - reference.bounds.centerX) +
            kotlin.math.abs(match.bounds.centerY - reference.bounds.centerY)
        return distance <= 120
    }

    private suspend fun askConfirmation(
        action: AgentAction,
        summary: String,
        reason: String,
        category: String,
        step: Int,
    ): Boolean {
        if (stopRequested) return false
        val id = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        confirmationDeferred = deferred
        AgentRuntime.setPendingConfirmation(
            PendingConfirmation(
                id = id,
                action = action,
                summary = summary,
                reason = reason,
                category = category,
                requestedAtMs = System.currentTimeMillis(),
            ),
        )
        transition(AgentState.AWAITING_USER_CONFIRMATION)
        AgentRuntime.addTimeline(
            AgentState.AWAITING_USER_CONFIRMATION,
            "Waiting for your approval",
            summary,
            step = step,
        )
        val approved = try {
            withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { deferred.await() } ?: false
        } finally {
            AgentRuntime.setPendingConfirmation(null)
            confirmationDeferred = null
        }
        AgentRuntime.addTimeline(
            if (approved) AgentState.REASONING else AgentState.REASONING,
            if (approved) "Approved by user" else "Denied or timed out",
            summary,
            isError = !approved,
            step = step,
        )
        if (pauseGate.value && !approved) {
            // stay paused until the user explicitly resumes
        } else if (!pauseGate.value && approved) {
            transition(AgentState.REASONING)
        }
        persistProgress()
        return approved
    }

    private fun sensitivityContext(
        action: AgentAction,
        observation: ScreenObservation,
        refs: Map<String, ElementRef>,
    ): SensitiveActionClassifier.Context {
        val ref = targetRef(action, observation, refs)
        return SensitiveActionClassifier.Context(
            action = action,
            targetText = ref?.text,
            targetDescription = ref?.description,
            targetResourceId = ref?.resourceId,
            targetPackage = ref?.packageName,
            isPasswordField = ref?.password == true,
            foregroundPackage = observation.foregroundPackage,
        )
    }

    private fun targetRef(
        action: AgentAction,
        observation: ScreenObservation,
        refs: Map<String, ElementRef>,
    ): ElementRef? {
        action.elementId?.let { id -> refs[id]?.let { return it } }
        val x = action.x ?: action.startX
        val y = action.y ?: action.startY
        if (x == null || y == null) return null
        return observation.elements
            .filter { it.bounds.contains(x, y) }
            .minByOrNull { it.bounds.width.toLong() * it.bounds.height }
            ?.let { refs[it.id] }
    }

    private fun invalidElementReference(
        action: AgentAction,
        refs: Map<String, ElementRef>,
    ): String? {
        action.elementId?.let { id ->
            if (!refs.containsKey(id)) return "Element $id is not part of the current observation."
        }
        action.pendingAction?.let { pending -> invalidElementReference(pending, refs)?.let { return it } }
        return null
    }

    private fun confirmationSummary(
        action: AgentAction,
        observation: ScreenObservation,
        refs: Map<String, ElementRef>,
    ): String {
        val target = targetRef(action, observation, refs)?.let { ref ->
            ref.text ?: ref.description ?: ref.id
        }
        return when (action.type) {
            ActionType.TAP -> "Tap \"${target ?: "this element"}\" in ${observation.appLabel ?: observation.foregroundPackage}?"
            ActionType.LONG_PRESS -> "Long-press \"${target ?: "this element"}\"?"
            ActionType.TYPE_TEXT -> "Enter text into \"${target ?: "the focused field"}\"?"
            ActionType.LAUNCH_APP -> "Launch ${action.appName ?: action.packageName}?"
            ActionType.SWIPE, ActionType.SCROLL -> "Perform this navigation gesture?"
            ActionType.CLEAR_TEXT -> "Clear the text field?"
            else -> action.summary ?: "Perform ${action.type.name.lowercase()}?"
        }
    }

    private fun actionLabel(action: AgentAction, refs: Map<String, ElementRef>): String = when (action.type) {
        ActionType.TAP -> "Tapping ${action.elementId?.let { refs[it] }?.let { ref -> "\"${ref.text ?: ref.description ?: ref.id}\"" } ?: "screen"}"
        ActionType.LONG_PRESS -> "Long-pressing"
        ActionType.SWIPE -> "Swiping"
        ActionType.SCROLL -> "Scrolling ${action.direction?.name?.lowercase().orEmpty()}"
        ActionType.TYPE_TEXT -> "Entering text"
        ActionType.CLEAR_TEXT -> "Clearing text"
        ActionType.BACK -> "Going back"
        ActionType.HOME -> "Going home"
        ActionType.OPEN_NOTIFICATIONS -> "Opening notifications"
        ActionType.LAUNCH_APP -> "Launching ${action.appName ?: action.packageName}"
        ActionType.WAIT -> "Waiting"
        ActionType.OBSERVE -> "Observing again"
        ActionType.REQUEST_CONFIRMATION -> "Requesting confirmation"
        ActionType.FINISH -> "Finishing"
        ActionType.FAIL -> "Reporting failure"
    }

    private fun buildVerificationNote(
        action: AgentAction,
        before: ScreenObservation,
        after: ScreenObservation?,
        execution: ExecutionResult,
        refs: Map<String, ElementRef>,
    ): String? {
        if (after == null) return "Could not verify the result because the screen could not be observed."
        if (action.type == ActionType.LAUNCH_APP && execution.launchPackage != null) {
            return if (after.foregroundPackage == execution.launchPackage) {
                "Foreground app verified as ${execution.launchPackage}."
            } else {
                "Foreground app is ${after.foregroundPackage}, not ${execution.launchPackage}."
            }
        }
        if (action.type == ActionType.TYPE_TEXT) {
            val entered = action.text.orEmpty()
            val present = after.elements.any { it.text?.contains(entered) == true }
            return if (present) "Entered text is visible in the new observation." else "Entered text was not found in the new observation."
        }
        if (action.elementId != null) {
            val ref = refs[action.elementId]
            if (ref != null) {
                val beforeElement = before.elements.firstOrNull { it.id == ref.id }
                val afterElement = after.elements.firstOrNull { element ->
                    (ref.resourceId != null && element.resourceId == ref.resourceId) ||
                        (ref.text != null && ref.text != "[redacted]" && element.text == ref.text)
                }
                if (beforeElement != null && afterElement != null) {
                    if (beforeElement.checked != afterElement.checked) {
                        return "Target state changed from checked=${beforeElement.checked} to checked=${afterElement.checked}."
                    }
                    if (beforeElement.selected != afterElement.selected) {
                        return "Target selection changed from ${beforeElement.selected} to ${afterElement.selected}."
                    }
                }
            }
        }
        return if (execution.success) {
            if (after.fingerprint != before.fingerprint) "The screen changed after the action." else "No visible change followed the action."
        } else {
            null
        }
    }

    private fun shouldIncludeScreenshot(observation: ScreenObservation): Boolean {
        if (!settings.sendScreenshots || !model.supportsVision) return false
        if (observation.elements.any { it.password }) return false
        if (screenshotRequested) return true
        if (sameScreenCount >= 2) return true
        if (observation.elements.size <= 3) return true
        if (observation.hasDialog && sameScreenCount >= 1) return true
        return false
    }

    private suspend fun settle(maxWaitMs: Long) {
        val start = System.currentTimeMillis()
        var lastEvent = AccessibilityBridge.lastEventAtMs
        var quietSince = start
        while (System.currentTimeMillis() - start < maxWaitMs) {
            delay(80)
            val eventAt = AccessibilityBridge.lastEventAtMs
            if (eventAt > lastEvent) {
                lastEvent = eventAt
                quietSince = System.currentTimeMillis()
            }
            if (System.currentTimeMillis() - start >= 350 &&
                System.currentTimeMillis() - quietSince >= 380
            ) {
                break
            }
        }
        delay(120)
    }

    private suspend fun awaitResume() {
        if (stopRequested) throwIfStopped()
        if (AgentRuntime.state().pendingConfirmation != null) {
            setState(AgentState.AWAITING_USER_CONFIRMATION)
        } else if (AgentRuntime.state().state == AgentState.PAUSED) {
            setState(AgentState.OBSERVING)
        }
        pauseGate.first { !it }
        throwIfStopped()
    }

    private fun checkAccessibility(forceFail: Boolean = false) {
        if (!AccessibilityBridge.connected.value) {
            if (forceFail) {
                throw IllegalStateException(
                    "Accessibility is still disconnected. Re-enable Piko in Android Settings > Accessibility, then resume.",
                )
            }
            pause("Accessibility disconnected.")
        }
    }

    private fun isLockError(error: String): Boolean =
        error.contains("locked", ignoreCase = true) || error.contains("Keyguard", ignoreCase = true)

    private fun activeElapsed(startedAt: Long): Long {
        val now = System.currentTimeMillis()
        val currentPause = pausedAtMs?.let { (now - it).coerceAtLeast(0) } ?: 0
        return now - startedAt - totalPausedMs - currentPause
    }

    private fun throwIfStopped() {
        if (stopRequested) throw CancellationException("Stopped by user")
    }

    private fun transition(state: AgentState) {
        if (!stateMachine.transitionTo(state)) {
            AgentRuntime.log("Ignored invalid transition ${stateMachine.state} -> $state", settings.debugLogging)
            if (state.isTerminal) stateMachine.transitionTo(AgentState.FAILED)
            return
        }
        AgentRuntime.setState(state)
    }

    private fun setState(state: AgentState, pauseReason: String? = null) {
        if (state == AgentState.PAUSED) {
            stateMachine.transitionTo(AgentState.PAUSED)
            AgentRuntime.setState(state, pauseReason)
        } else {
            if (!stateMachine.transitionTo(state)) {
                AgentRuntime.log("Ignored invalid transition ${stateMachine.state} -> $state", settings.debugLogging)
                if (state.isTerminal) {
                    AgentRuntime.complete(
                        summary = AgentRuntime.state().resultSummary ?: "",
                        verified = AgentRuntime.state().verified,
                        verification = AgentRuntime.state().verification,
                        state = state,
                        error = AgentRuntime.state().error,
                    )
                }
                return
            }
            AgentRuntime.setState(state)
        }
    }

    private suspend fun finish(
        status: AgentState,
        summary: String?,
        error: String?,
        verified: Boolean?,
        verification: String?,
        terminalHandled: Boolean,
    ) {
        withContext(NonCancellable) {
            finishInternal(status, summary, error, verified, verification)
        }
    }

    private suspend fun finishInternal(
        status: AgentState,
        summary: String?,
        error: String?,
        verified: Boolean?,
        verification: String?,
    ) {
        if (finished) return
        finished = true
        if (settings.historyEnabled) {
            runCatching {
                taskRepository.finishTask(
                    id = taskId,
                    status = status,
                    summary = summary,
                    error = error,
                    verified = verified,
                    verification = verification,
                    stepCount = stepCount,
                    inputTokens = totalUsage.inputTokens,
                    outputTokens = totalUsage.outputTokens,
                    costUsd = totalUsage.costUsd,
                )
            }
        }
        AgentRuntime.complete(
            summary = summary ?: error ?: status.name.lowercase(),
            verified = verified,
            verification = verification,
            state = status,
            error = error,
        )
        AgentRuntime.addTimeline(
            status,
            when (status) {
                AgentState.COMPLETED -> "Task completed"
                AgentState.CANCELLED -> "Task cancelled"
                else -> "Task failed"
            },
            error ?: summary,
            isError = status != AgentState.COMPLETED,
        )
    }

    private suspend fun persistStep(
        step: Int,
        action: AgentAction,
        execution: ExecutionResult,
        observation: ScreenObservation,
        verification: String,
        screenshotIncluded: Boolean,
    ) {
        if (!settings.historyEnabled) return
        val safeAction = sanitizeForTrace(action)
        val actionJson = runCatching {
            PikoJson.encodeToString(AgentAction.serializer(), safeAction)
        }.getOrNull()
        runCatching {
            taskRepository.addStep(
                taskId = taskId,
                stepIndex = step,
                state = AgentState.VERIFYING,
                actionType = action.type.name,
                actionJson = actionJson,
                result = verification,
                observationSummary = ObservationFormatter.summarize(observation),
                appPackage = observation.foregroundPackage,
                screenshotIncluded = screenshotIncluded,
            )
        }
    }

    private suspend fun persistProgress() {
        if (!settings.historyEnabled) return
        runCatching {
            taskRepository.updateProgress(
                id = taskId,
                status = AgentRuntime.state().state,
                stepCount = stepCount,
                inputTokens = totalUsage.inputTokens,
                outputTokens = totalUsage.outputTokens,
                costUsd = totalUsage.costUsd,
                error = AgentRuntime.state().error,
            )
        }
    }

    private fun sanitizeForTrace(action: AgentAction): AgentAction {
        val copy = action.copy(
            text = SensitiveTextRedactor.forTrace(action.text),
            pendingAction = action.pendingAction?.let { sanitizeForTrace(it) },
        )
        return copy
    }

    companion object {
        private const val CONFIRMATION_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
