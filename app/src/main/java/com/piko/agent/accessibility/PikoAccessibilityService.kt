package com.piko.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlin.coroutines.resume

class PikoAccessibilityService : AccessibilityService() {
    private val assistantScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private var idleOverlay: com.piko.agent.overlay.AgentOverlay? = null
    private var overlayJob: kotlinx.coroutines.Job? = null


    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
        overlayJob?.cancel()
        overlayJob = assistantScope.launch {
            kotlinx.coroutines.flow.combine(com.piko.agent.PikoApplication.from(this@PikoAccessibilityService).settingsRepository.settings,
                com.piko.agent.agent.AgentRuntime.state) { settings, state -> settings to state }.collect { (settings, state) ->
                if (!settings.floatingAssistantEnabled || state.isActive) {
                    idleOverlay?.hide(); idleOverlay = null
                } else {
                    if (idleOverlay == null) idleOverlay = com.piko.agent.overlay.AgentOverlay(this@PikoAccessibilityService,
                        object : com.piko.agent.overlay.AgentOverlay.Actions {
                            override fun onPauseResume() {}
                            override fun onStop() { com.piko.agent.PikoApplication.from(this@PikoAccessibilityService).speechOutput.stop() }
                            override fun onVoice() { com.piko.agent.VoiceInputActivity.open(this@PikoAccessibilityService) }
                            override fun onText(text: String) { com.piko.agent.service.AgentService.start(this@PikoAccessibilityService, text) }
                            override fun onApproveConfirmation(confirmationId: String) {}
                            override fun onDismiss() {
                                assistantScope.launch { com.piko.agent.PikoApplication.from(this@PikoAccessibilityService).settingsRepository.update { it.copy(floatingAssistantEnabled = false) } }
                            }
                        }, persistent = true)
                    idleOverlay?.show(state)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val foreground = rootInActiveWindow?.packageName?.toString() ?: event.packageName?.toString().orEmpty()
            val locked = getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == true
            com.piko.agent.overlay.OverlaySuppressor.setSecuritySuppressed(locked || foreground.contains("permissioncontroller", true) || com.piko.agent.core.SensitiveApps.isBlocked(foreground))
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> AccessibilityBridge.notifyEvent(event.packageName?.toString())
        }
    }

    override fun onInterrupt() {
        // Android asks the service to interrupt feedback. Piko has no spoken
        // feedback; the agent's own cancellation path handles active gestures.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        idleOverlay?.hide(); idleOverlay = null; overlayJob?.cancel()
        com.piko.agent.overlay.OverlaySuppressor.setSecuritySuppressed(false)
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        assistantScope.cancel()
        idleOverlay?.hide(); idleOverlay = null; overlayJob?.cancel()
        com.piko.agent.overlay.OverlaySuppressor.setSecuritySuppressed(false)
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return findFocusedEditable(root, 0)
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (node.isEditable && node.isFocused) return node
        for (index in 0 until node.childCount) {
            findFocusedEditable(node.getChild(index), depth + 1)?.let { return it }
        }
        return null
    }

    fun performGlobal(action: Int): Boolean = performGlobalAction(action)

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    suspend fun tap(x: Int, y: Int, durationMs: Long = 60, longPress: Boolean = false): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo(x + 1f, y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(30, 3000)))
            .build()
        return dispatch(gesture)
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    durationMs.coerceIn(80, 3000),
                ),
            )
            .build()
        return dispatch(gesture)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
}
