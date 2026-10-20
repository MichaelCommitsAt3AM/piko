package com.piko.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.piko.agent.core.AgentAction
import com.piko.agent.core.ActionType
import com.piko.agent.core.ScrollDirection
import com.piko.agent.core.SensitiveTextRedactor
import com.piko.agent.model.ElementRef
import com.piko.agent.model.PikoSettings
import com.piko.agent.model.ScreenObservation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.math.abs

data class ExecutionResult(
    val success: Boolean,
    val message: String,
    val gestureUsed: Boolean = false,
    val startedAtMs: Long = System.currentTimeMillis(),
    val finishedAtMs: Long = System.currentTimeMillis(),
    val acceptedByAndroid: Boolean = false,
    val launchPackage: String? = null,
)

/**
 * Executes validated actions with semantic accessibility actions first and
 * gesture fallbacks second. It never invents actions and never runs shell
 * commands.
 */
class ActionExecutor(private val context: Context) {

    suspend fun execute(
        action: AgentAction,
        observation: ScreenObservation,
        refs: Map<String, ElementRef>,
        settings: PikoSettings,
    ): ExecutionResult {
        val started = System.currentTimeMillis()
        if (action.elementId != null && action.elementId !in refs) {
            return fail("Element ${action.elementId} is stale. Observe the screen again.")
        }
        val service = AccessibilityBridge.service
            ?: return ExecutionResult(
                success = false,
                message = "Accessibility is disconnected.",
                startedAtMs = started,
                finishedAtMs = System.currentTimeMillis(),
            )
        return try {
            withTimeout(settings.executionTimeoutMs) {
                val result = when (action.type) {
                    ActionType.TAP -> tap(service, action, refs, observation)
                    ActionType.LONG_PRESS -> longPress(service, action, refs, observation)
                    ActionType.SWIPE -> swipe(service, action, observation)
                    ActionType.SCROLL -> scroll(service, action, refs, observation)
                    ActionType.TYPE_TEXT -> typeText(service, action, refs)
                    ActionType.CLEAR_TEXT -> clearText(service, action, refs)
                    ActionType.BACK -> global(service, AccessibilityService.GLOBAL_ACTION_BACK, "Pressed Back.")
                    ActionType.HOME -> global(service, AccessibilityService.GLOBAL_ACTION_HOME, "Pressed Home.")
                    ActionType.OPEN_NOTIFICATIONS -> global(
                        service,
                        AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                        "Opened the notification shade.",
                    )
                    ActionType.LAUNCH_APP -> launchApp(action)
                    ActionType.WAIT -> {
                        delay((action.milliseconds ?: 500).coerceIn(100, 10_000))
                        ExecutionResult(true, "Waited ${action.milliseconds ?: 500} ms.", acceptedByAndroid = true)
                    }
                    ActionType.OBSERVE -> ExecutionResult(true, "Requested a fresh observation.", acceptedByAndroid = true)
                    ActionType.REQUEST_CONFIRMATION, ActionType.FINISH, ActionType.FAIL ->
                        ExecutionResult(false, "This action is handled by the agent controller.")
                }
                result.copy(
                    startedAtMs = started,
                    finishedAtMs = System.currentTimeMillis(),
                )
            }
        } catch (error: TimeoutCancellationException) {
            ExecutionResult(
                success = false,
                message = "Action timed out after ${settings.executionTimeoutMs} ms.",
                startedAtMs = started,
                finishedAtMs = System.currentTimeMillis(),
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            ExecutionResult(
                success = false,
                message = "Action failed: ${error.message ?: error.javaClass.simpleName}",
                startedAtMs = started,
                finishedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun tap(
        service: PikoAccessibilityService,
        action: AgentAction,
        refs: Map<String, ElementRef>,
        observation: ScreenObservation,
    ): ExecutionResult {
        if (action.elementId != null) {
            val ref = refs[action.elementId]
                ?: return fail("Element ${action.elementId} is stale. Observe the screen again.")
            val node = NodeFinder.find(service, ref)
                ?: return fail("Element ${action.elementId} is no longer present. Observe the screen again.")
            val clickable = NodeFinder.clickableNode(node)
            if (clickable != null) {
                val accepted = runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
                if (accepted) return ExecutionResult(true, "Tapped ${describe(ref)}.", acceptedByAndroid = true)
            }
            val centerX = ref.bounds.centerX
            val centerY = ref.bounds.centerY
            if (centerX !in 0 until observation.screenWidth || centerY !in 0 until observation.screenHeight) {
                return fail("Element ${action.elementId} is outside the visible screen.")
            }
            val dispatched = service.tap(centerX, centerY)
            return ExecutionResult(
                success = dispatched,
                message = if (dispatched) "Tapped ${describe(ref)} with a gesture." else "The system rejected the tap gesture.",
                gestureUsed = true,
                acceptedByAndroid = dispatched,
            )
        }
        val x = action.x
        val y = action.y
        if (x == null || y == null) return fail("Tap has no target.")
        if (x !in 0 until observation.screenWidth || y !in 0 until observation.screenHeight) {
            return fail("Tap coordinates ($x, $y) are outside the screen.")
        }
        val dispatched = service.tap(x, y)
        return ExecutionResult(
            success = dispatched,
            message = if (dispatched) "Tapped ($x, $y)." else "The system rejected the tap gesture.",
            gestureUsed = true,
            acceptedByAndroid = dispatched,
        )
    }

    private suspend fun longPress(
        service: PikoAccessibilityService,
        action: AgentAction,
        refs: Map<String, ElementRef>,
        observation: ScreenObservation,
    ): ExecutionResult {
        if (action.elementId != null) {
            val ref = refs[action.elementId]
                ?: return fail("Element ${action.elementId} is stale. Observe again.")
            val node = NodeFinder.find(service, ref)
                ?: return fail("Element ${action.elementId} is no longer present. Observe again.")
            val clickable = NodeFinder.clickableNode(node)
            if (clickable != null) {
                val accepted = runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) }
                    .getOrDefault(false)
                if (accepted) return ExecutionResult(true, "Long-pressed ${describe(ref)}.", acceptedByAndroid = true)
            }
            val dispatched = service.tap(ref.bounds.centerX, ref.bounds.centerY, durationMs = 700, longPress = true)
            return ExecutionResult(
                success = dispatched,
                message = if (dispatched) "Long-pressed ${describe(ref)} with a gesture." else "Gesture rejected.",
                gestureUsed = true,
                acceptedByAndroid = dispatched,
            )
        }
        val x = action.x
        val y = action.y
        if (x == null || y == null) return fail("Long press has no target.")
        val dispatched = service.tap(x, y, durationMs = 700, longPress = true)
        return ExecutionResult(
            success = dispatched,
            message = if (dispatched) "Long-pressed ($x, $y)." else "Gesture rejected.",
            gestureUsed = true,
            acceptedByAndroid = dispatched,
        )
    }

    private suspend fun swipe(
        service: PikoAccessibilityService,
        action: AgentAction,
        observation: ScreenObservation,
    ): ExecutionResult {
        val startX = action.startX
        val startY = action.startY
        val endX = action.endX
        val endY = action.endY
        if (startX == null || startY == null || endX == null || endY == null) {
            return fail("Swipe coordinates are incomplete.")
        }
        if (startX !in 0 until observation.screenWidth || endX !in 0 until observation.screenWidth ||
            startY !in 0 until observation.screenHeight || endY !in 0 until observation.screenHeight
        ) {
            return fail("Swipe is outside the screen.")
        }
        val duration = (action.durationMs ?: 400).toLong()
        val dispatched = service.swipe(startX, startY, endX, endY, duration)
        return ExecutionResult(
            success = dispatched,
            message = if (dispatched) "Swiped ($startX,$startY) to ($endX,$endY)." else "The system rejected the swipe.",
            gestureUsed = true,
            acceptedByAndroid = dispatched,
        )
    }

    private suspend fun scroll(
        service: PikoAccessibilityService,
        action: AgentAction,
        refs: Map<String, ElementRef>,
        observation: ScreenObservation,
    ): ExecutionResult {
        val direction = action.direction ?: return fail("Scroll has no direction.")
        val amount = (action.amount ?: 1).coerceIn(1, 10)
        val ref = action.elementId?.let { refs[it] }
        if (ref != null) {
            val node = NodeFinder.find(service, ref)
            if (node != null && node.isScrollable) {
                val semantic = when (direction) {
                    ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ScrollDirection.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    ScrollDirection.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                var accepted = true
                repeat(amount) {
                    accepted = accepted && runCatching { node.performAction(semantic) }.getOrDefault(false)
                    delay(70)
                }
                if (accepted) {
                    return ExecutionResult(
                        true,
                        "Scrolled ${direction.name.lowercase()} on ${describe(ref)}.",
                        acceptedByAndroid = true,
                    )
                }
            }
            return swipeInBounds(
                service,
                ref.bounds,
                direction,
                amount,
                observation.screenWidth,
                observation.screenHeight,
            )
        }
        val screenBounds = com.piko.agent.model.ElementBounds(
            0,
            0,
            observation.screenWidth,
            observation.screenHeight,
        )
        return swipeInBounds(
            service,
            screenBounds,
            direction,
            amount,
            observation.screenWidth,
            observation.screenHeight,
        )
    }

    private suspend fun swipeInBounds(
        service: PikoAccessibilityService,
        bounds: com.piko.agent.model.ElementBounds,
        direction: ScrollDirection,
        amount: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): ExecutionResult {
        val centerX = bounds.centerX
        val centerY = bounds.centerY
        val horizontalDistance = (bounds.width * 0.55f).toInt().coerceAtLeast(80)
        val verticalDistance = (bounds.height * 0.55f).toInt().coerceAtLeast(80)
        fun clampX(value: Int) = value.coerceIn(0, (screenWidth - 1).coerceAtLeast(0))
        fun clampY(value: Int) = value.coerceIn(0, (screenHeight - 1).coerceAtLeast(0))
        var dispatched = true
        repeat(amount) {
            dispatched = when (direction) {
                ScrollDirection.DOWN -> service.swipe(
                    clampX(centerX), clampY(centerY + verticalDistance / 2),
                    clampX(centerX), clampY(centerY - verticalDistance / 2), 350,
                )
                ScrollDirection.UP -> service.swipe(
                    clampX(centerX), clampY(centerY - verticalDistance / 2),
                    clampX(centerX), clampY(centerY + verticalDistance / 2), 350,
                )
                ScrollDirection.RIGHT -> service.swipe(
                    clampX(centerX + horizontalDistance / 2), clampY(centerY),
                    clampX(centerX - horizontalDistance / 2), clampY(centerY), 350,
                )
                ScrollDirection.LEFT -> service.swipe(
                    clampX(centerX - horizontalDistance / 2), clampY(centerY),
                    clampX(centerX + horizontalDistance / 2), clampY(centerY), 350,
                )
            } && dispatched
            delay(100)
        }
        return ExecutionResult(
            success = dispatched,
            message = if (dispatched) {
                "Scrolled ${direction.name.lowercase()} with a gesture."
            } else {
                "The system rejected the scroll gesture."
            },
            gestureUsed = true,
            acceptedByAndroid = dispatched,
        )
    }

    private suspend fun typeText(
        service: PikoAccessibilityService,
        action: AgentAction,
        refs: Map<String, ElementRef>,
    ): ExecutionResult {
        val text = action.text ?: return fail("No text supplied.")
        val ref = action.elementId?.let { refs[it] }
        val node = if (ref != null) {
            val found = NodeFinder.find(service, ref)
                ?: return fail("The text field is stale. Observe the screen again.")
            NodeFinder.editableNode(found)
        } else {
            service.focusedEditable()
        }
        if (node == null) return fail("No editable text field was found. Tap the field first or provide elementId.")
        if (com.piko.agent.core.SensitiveApps.isBlocked(node.packageName?.toString())) {
            return fail("Piko does not enter text into credential managers or terminal apps.")
        }
        if (node.isPassword || SensitiveTextRedactor.shouldRedact(
                node.viewIdResourceName,
                node.hintText?.toString(),
                node.className?.toString(),
                node.isPassword,
            )
        ) {
            return fail("Piko does not enter passwords, PINs, one-time codes, or payment credentials.")
        }
        val accepted = service.setNodeText(node, text)
        return if (accepted) {
            ExecutionResult(true, "Entered text into the field.", acceptedByAndroid = true)
        } else {
            fail("Android rejected text entry for this field.")
        }
    }

    private suspend fun clearText(
        service: PikoAccessibilityService,
        action: AgentAction,
        refs: Map<String, ElementRef>,
    ): ExecutionResult {
        val ref = action.elementId?.let { refs[it] }
        val node = if (ref != null) {
            NodeFinder.find(service, ref)?.let { NodeFinder.editableNode(it) }
        } else {
            service.focusedEditable()
        } ?: return fail("No editable field was found.")
        if (com.piko.agent.core.SensitiveApps.isBlocked(node.packageName?.toString()) ||
            SensitiveTextRedactor.shouldRedact(
                node.viewIdResourceName, node.hintText?.toString(),
                node.className?.toString(), node.isPassword,
            )
        ) return fail("Piko does not modify credential fields or blocked apps.")
        val accepted = service.setNodeText(node, "")
        return if (accepted) {
            ExecutionResult(true, "Cleared the text field.", acceptedByAndroid = true)
        } else {
            fail("Android rejected clearing this field.")
        }
    }

    private fun global(
        service: PikoAccessibilityService,
        globalAction: Int,
        message: String,
    ): ExecutionResult {
        val accepted = service.performGlobal(globalAction)
        return ExecutionResult(
            success = accepted,
            message = if (accepted) message else "Android rejected the global action.",
            acceptedByAndroid = accepted,
        )
    }

    private fun launchApp(action: AgentAction): ExecutionResult {
        val packageManager = context.packageManager
        val packageName = action.packageName?.trim()?.takeIf { it.isNotBlank() }
            ?: findPackageByLabel(action.appName)
            ?: return fail("No installed app matched \"${action.appName}\".")
        if (packageName == context.packageName) return fail("Piko cannot drive its own interface.")
        com.piko.agent.core.SensitiveApps.blockReason(packageName)?.let { reason ->
            return fail(reason)
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(launchIntent)
            ExecutionResult(
                true,
                "Requested launch of $packageName. Waiting to verify the foreground app.",
                acceptedByAndroid = true,
                launchPackage = packageName,
            )
        } catch (error: Exception) {
            fail("Could not launch $packageName: ${error.message}")
        }
    }

    private fun findPackageByLabel(label: String?): String? {
        if (label.isNullOrBlank()) return null
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = context.packageManager.queryIntentActivities(query, 0)
        val normalized = label.trim().lowercase()
        return activities.firstOrNull { resolve ->
            resolve.loadLabel(context.packageManager).toString().trim().lowercase() == normalized
        }?.activityInfo?.packageName ?: activities.firstOrNull { resolve ->
            resolve.loadLabel(context.packageManager).toString().lowercase().contains(normalized)
        }?.activityInfo?.packageName
    }

    private fun describe(ref: ElementRef): String =
        ref.text?.take(40)?.takeIf { it.isNotBlank() }?.let { "\"$it\"" }
            ?: ref.description?.take(40)?.takeIf { it.isNotBlank() }?.let { "\"$it\"" }
            ?: ref.id

    private fun fail(message: String) = ExecutionResult(false, message)
}

internal object NodeFinder {
    fun find(service: PikoAccessibilityService, ref: ElementRef): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
            if (node == null || depth > 60) return null
            if (matches(node, ref)) return node
            for (index in 0 until node.childCount) {
                visit(node.getChild(index), depth + 1)?.let { return it }
            }
            return null
        }
        return visit(root, 0)
    }

    internal fun matches(node: AccessibilityNodeInfo, ref: ElementRef): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled) return false
        if (node.packageName?.toString() != ref.packageName) return false
        if (ref.className != null && node.className?.toString() != ref.className) return false
        if (ref.resourceId != null && node.viewIdResourceName != ref.resourceId) return false
        if (ref.text != null && ref.text != "[redacted]" && observedLabel(node.text) != ref.text) return false
        if (ref.description != null && ref.description != "[redacted]" &&
            observedLabel(node.contentDescription) != ref.description) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect.width() > 0 && rect.height() > 0 &&
            abs(rect.left - ref.bounds.left) <= 24 &&
            abs(rect.top - ref.bounds.top) <= 24 &&
            abs(rect.right - ref.bounds.right) <= 24 &&
            abs(rect.bottom - ref.bounds.bottom) <= 24
    }

    // Use the same whitespace/length normalization as ObservationEngine.
    private fun observedLabel(value: CharSequence?): String? =
        SensitiveTextRedactor.redactVisibleText(
            value?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.take(180),
        )

    fun clickableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < 7) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun editableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (index in 0 until node.childCount) {
            editableNode(node.getChild(index))?.let { return it }
        }
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.isEditable) return parent
            parent = parent.parent
            depth++
        }
        return null
    }
}
