package com.piko.agent.core

/**
 * Validates every model-produced action before it can reach Android.
 *
 * Validation is intentionally strict. Coordinates are in the current display
 * coordinate space (the same space as accessibility bounds). The agent prompt
 * states that space and screenshot metadata so vision models can map correctly.
 */
object ActionValidator {
    private val elementIdPattern = Regex("^element_[0-9]{1,4}$")
    private val packagePattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private const val MAX_TEXT_LENGTH = 4000
    private const val MAX_GESTURE_DURATION_MS = 3000
    private const val MIN_GESTURE_DURATION_MS = 80
    private const val MAX_WAIT_MS = 10_000L
    private const val MAX_SCROLL_AMOUNT = 10

    fun validate(action: AgentAction, bounds: ScreenBounds, depth: Int = 0): ValidationResult {
        if (depth > 1) return ValidationResult.Invalid(listOf("Nested confirmation action is too deep."))
        if (action.version != AgentAction.ACTION_SCHEMA_VERSION) {
            return ValidationResult.Invalid(listOf("Unsupported action schema version ${action.version}."))
        }

        val errors = mutableListOf<String>()
        when (action.type) {
            ActionType.TAP, ActionType.LONG_PRESS -> {
                val hasElement = !action.elementId.isNullOrBlank()
                val hasPoint = action.x != null || action.y != null
                if (hasElement == hasPoint) {
                    errors += "Provide exactly one of elementId or x/y."
                }
                if (hasElement && !elementIdPattern.matches(action.elementId!!)) {
                    errors += "elementId must look like element_12."
                }
                if (hasPoint) {
                    if (action.x == null || action.y == null) {
                        errors += "Both x and y are required for coordinate taps."
                    } else {
                        if (!inBounds(action.x, action.y, bounds)) {
                            errors += "Tap coordinates (${action.x}, ${action.y}) are outside ${bounds.width}x${bounds.height}."
                        }
                    }
                }
            }

            ActionType.SWIPE -> {
                val x1 = action.startX
                val y1 = action.startY
                val x2 = action.endX
                val y2 = action.endY
                if (x1 == null || y1 == null || x2 == null || y2 == null) {
                    errors += "Swipe requires startX, startY, endX and endY."
                } else {
                    if (!inBounds(x1, y1, bounds) || !inBounds(x2, y2, bounds)) {
                        errors += "Swipe coordinates must be inside ${bounds.width}x${bounds.height}."
                    }
                    val distance = kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
                    if (distance < 10.0) errors += "Swipe distance is too small."
                }
                val duration = action.durationMs ?: 350
                if (duration !in MIN_GESTURE_DURATION_MS..MAX_GESTURE_DURATION_MS) {
                    errors += "durationMs must be between $MIN_GESTURE_DURATION_MS and $MAX_GESTURE_DURATION_MS."
                }
            }

            ActionType.SCROLL -> {
                if (action.direction == null) errors += "Scroll requires a direction."
                val amount = action.amount ?: 1
                if (amount !in 1..MAX_SCROLL_AMOUNT) errors += "amount must be between 1 and $MAX_SCROLL_AMOUNT."
                if (!action.elementId.isNullOrBlank() && !elementIdPattern.matches(action.elementId)) {
                    errors += "elementId must look like element_12."
                }
            }

            ActionType.TYPE_TEXT -> {
                val text = action.text
                if (text.isNullOrEmpty()) {
                    errors += "type_text requires non-empty text."
                } else if (text.length > MAX_TEXT_LENGTH) {
                    errors += "Text exceeds $MAX_TEXT_LENGTH characters."
                } else if (text.contains('\u0000')) {
                    errors += "Text contains a null character."
                }
                if (!action.elementId.isNullOrBlank() && !elementIdPattern.matches(action.elementId)) {
                    errors += "elementId must look like element_12."
                }
            }

            ActionType.CLEAR_TEXT -> {
                if (!action.elementId.isNullOrBlank() && !elementIdPattern.matches(action.elementId)) {
                    errors += "elementId must look like element_12."
                }
            }

            ActionType.BACK, ActionType.HOME, ActionType.OPEN_NOTIFICATIONS -> Unit

            ActionType.LAUNCH_APP -> {
                val pkg = action.packageName
                val name = action.appName
                if (pkg.isNullOrBlank() && name.isNullOrBlank()) {
                    errors += "launch_app requires packageName or appName."
                }
                if (!pkg.isNullOrBlank() && !packagePattern.matches(pkg)) {
                    errors += "packageName is not a valid Android package name."
                }
                if (!name.isNullOrBlank() && name.length > 120) {
                    errors += "appName is too long."
                }
            }

            ActionType.WAIT -> {
                val ms = action.milliseconds
                if (ms == null || ms !in 100..MAX_WAIT_MS) {
                    errors += "wait requires milliseconds between 100 and $MAX_WAIT_MS."
                }
            }

            ActionType.OBSERVE -> Unit

            ActionType.REQUEST_CONFIRMATION -> {
                if (action.summary.isNullOrBlank()) errors += "request_confirmation requires a summary."
                action.pendingAction?.let { pending ->
                    if (pending.type in setOf(
                            ActionType.REQUEST_CONFIRMATION,
                            ActionType.FINISH,
                            ActionType.FAIL,
                            ActionType.OBSERVE,
                        )
                    ) {
                        errors += "pendingAction must be a device action."
                    } else {
                        val nested = validate(pending, bounds, depth + 1)
                        if (nested is ValidationResult.Invalid) {
                            errors += nested.errors.map { "pendingAction: $it" }
                        }
                    }
                }
            }

            ActionType.FINISH -> {
                if (action.summary.isNullOrBlank() && action.message.isNullOrBlank()) {
                    errors += "finish requires a summary of the outcome."
                }
            }

            ActionType.FAIL -> {
                if (action.message.isNullOrBlank() && action.summary.isNullOrBlank()) {
                    errors += "fail requires a reason."
                }
            }
        }
        return if (errors.isEmpty()) ValidationResult.Valid(action) else ValidationResult.Invalid(errors)
    }

    private fun inBounds(x: Int, y: Int, bounds: ScreenBounds): Boolean =
        bounds.isValid && x in 0 until bounds.width && y in 0 until bounds.height

    sealed interface ValidationResult {
        data class Valid(val action: AgentAction) : ValidationResult
        data class Invalid(val errors: List<String>) : ValidationResult
    }
}
