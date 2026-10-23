package com.piko.agent.core

/**
 * Detects agent loops using both the action signature and a UI fingerprint.
 * A warning asks the model to change approach; an abort is enforced by the
 * controller and terminates the task instead of burning tokens forever.
 */
class LoopDetector(
    private val maxDuplicateActions: Int = 2,
    private val maxSameElementTaps: Int = 4,
    private val maxStepsWithoutProgress: Int = 6,
) {
    private var lastSignature: String? = null
    private var lastFingerprint: String? = null
    private var duplicateCount = 0
    private var stepsWithoutProgress = 0
    private val tapCounts = mutableMapOf<String, Int>()
    private var lastTapTarget: String? = null

    fun record(fingerprint: String, action: AgentAction): LoopSignal {
        val signature = signature(action)
        val fingerprintChanged = lastFingerprint != null && fingerprint != lastFingerprint

        duplicateCount = if (signature == lastSignature && !fingerprintChanged) duplicateCount + 1 else 0
        if (lastFingerprint != null && !fingerprintChanged) stepsWithoutProgress++ else stepsWithoutProgress = 0

        val tapTarget = when (action.type) {
            ActionType.TAP, ActionType.LONG_PRESS -> action.elementId ?: "${action.x},${action.y}"
            ActionType.SCROLL -> "scroll:${action.direction}"
            else -> null
        }
        if (tapTarget != null) {
            if (tapTarget == lastTapTarget && !fingerprintChanged) {
                tapCounts[tapTarget] = (tapCounts[tapTarget] ?: 0) + 1
            } else if (tapTarget != lastTapTarget || fingerprintChanged) {
                tapCounts.clear()
                tapCounts[tapTarget] = 1
            }
        }
        lastTapTarget = tapTarget
        lastSignature = signature
        lastFingerprint = fingerprint

        val sameTapCount = tapTarget?.let { tapCounts[it] ?: 0 } ?: 0
        return when {
            sameTapCount >= maxSameElementTaps -> LoopSignal.Abort(
                "The same element was activated $sameTapCount times without the screen changing.",
            )
            duplicateCount >= maxDuplicateActions -> LoopSignal.Abort(
                "The same action repeated $duplicateCount times without progress.",
            )
            stepsWithoutProgress >= maxStepsWithoutProgress -> LoopSignal.Abort(
                "The interface has not changed for $stepsWithoutProgress steps.",
            )
            duplicateCount == 1 -> LoopSignal.Warn(
                "The previous action was repeated without a visible change. Try another approach and consider requesting a screenshot.",
            )
            else -> LoopSignal.None
        }
    }

    fun reset() {
        lastSignature = null
        lastFingerprint = null
        duplicateCount = 0
        stepsWithoutProgress = 0
        tapCounts.clear()
        lastTapTarget = null
    }

    companion object {
        fun signature(action: AgentAction): String = buildString {
            append(action.type.name)
            append('|')
            append(action.elementId.orEmpty())
            append('|')
            append(action.x ?: "")
            append(',')
            append(action.y ?: "")
            append('|')
            append(action.startX ?: "")
            append(',')
            append(action.startY ?: "")
            append(',')
            append(action.endX ?: "")
            append(',')
            append(action.endY ?: "")
            append('|')
            append(action.direction?.name.orEmpty())
            append('|')
            append(action.text.orEmpty().take(80))
            append('|')
            append(action.packageName.orEmpty())
            append(action.toolId.orEmpty())
            append(action.parameters.toString())
        }
    }

    sealed interface LoopSignal {
        data object None : LoopSignal
        data class Warn(val reason: String) : LoopSignal
        data class Abort(val reason: String) : LoopSignal
    }
}
