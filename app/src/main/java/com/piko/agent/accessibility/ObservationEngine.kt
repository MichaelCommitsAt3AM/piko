package com.piko.agent.accessibility

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.piko.agent.core.FingerprintElement
import com.piko.agent.core.FingerprintRect
import com.piko.agent.core.SensitiveTextRedactor
import com.piko.agent.core.UiFingerprint
import com.piko.agent.model.ElementBounds
import com.piko.agent.model.ElementRef
import com.piko.agent.model.ObservationResult
import com.piko.agent.model.PikoSettings
import com.piko.agent.model.ScreenObservation
import com.piko.agent.model.UiElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Builds the structured observation sent to the model. Only meaningful,
 * visible and actionable nodes are kept; passwords and credential-like fields
 * are redacted before they can leave the device.
 */
class ObservationEngine(
    private val context: Context,
    private val screenshotEngine: ScreenshotEngine,
) {
    suspend fun observe(
        settings: PikoSettings,
        includeScreenshot: Boolean = false,
    ): ObservationResult = withContext(Dispatchers.Default) {
        val service = AccessibilityBridge.service
            ?: return@withContext ObservationResult(
                observation = null,
                error = "Accessibility is disabled. Enable Piko in Settings > Accessibility.",
            )
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) {
            return@withContext ObservationResult(
                observation = null,
                error = "The device is locked. Piko paused rather than acting on the lock screen.",
            )
        }

        var root = service.rootInActiveWindow
        var attempts = 0
        while (root == null && attempts < 4) {
            delay(120)
            root = service.rootInActiveWindow
            attempts++
        }
        if (root == null) {
            root = service.windows.asSequence()
                .sortedByDescending { it.layer }
                .mapNotNull { window -> runCatching { window.root }.getOrNull() }
                .firstOrNull()
        }
        if (root == null) {
            return@withContext ObservationResult(
                observation = null,
                error = "No accessible window is available. The screen may be locked, transitioning, or showing protected content.",
            )
        }

        val foregroundPackage = root.packageName?.toString()
        if (foregroundPackage == context.packageName) {
            return@withContext ObservationResult(
                observation = null,
                error = "Piko's own interface is in the foreground. Switch to the app you want Piko to operate.",
            )
        }
        com.piko.agent.core.SensitiveApps.blockReason(foregroundPackage)?.let { reason ->
            return@withContext ObservationResult(
                observation = null,
                error = reason,
            )
        }

        val windowManager = context.getSystemService(WindowManager::class.java)
        val bounds = windowManager.currentWindowMetrics.bounds
        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        val screenWidth = bounds.width()
        val screenHeight = bounds.height()
        val screenRect = Rect(0, 0, screenWidth, screenHeight)

        val elements = mutableListOf<UiElement>()
        val refs = mutableMapOf<String, ElementRef>()
        val seen = HashSet<String>()
        var visited = 0
        var truncated = false
        val appPackage = foregroundPackage

        fun traverse(node: AccessibilityNodeInfo?, parentId: String?, clickableAncestor: Boolean, depth: Int) {
            if (node == null || truncated) return
            visited++
            if (visited > MAX_VISITED) {
                truncated = true
                return
            }
            if (depth > MAX_DEPTH) return

            val nodePackage = node.packageName?.toString() ?: appPackage
            if (nodePackage == context.packageName) return

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val visible = node.isVisibleToUser && rect.width() > 2 && rect.height() > 2 &&
                Rect.intersects(rect, screenRect)
            val className = node.className?.toString()
            val rawText = node.text?.toString()?.trim()
            val rawDescription = node.contentDescription?.toString()?.trim()
            val rawHint = node.hintText?.toString()?.trim()
            val resourceId = node.viewIdResourceName
            val interactive = node.isClickable || node.isLongClickable || node.isScrollable ||
                node.isEditable || node.isCheckable
            val hasLabel = !rawText.isNullOrBlank() || !rawDescription.isNullOrBlank()
            val focusedEditable = node.isEditable && node.isFocused
            val keep = visible && (interactive || hasLabel || focusedEditable)

            var childParentId = parentId
            if (keep) {
                val password = node.isPassword
                val redact = SensitiveTextRedactor.shouldRedact(
                    resourceId = resourceId,
                    hint = rawHint,
                    className = className,
                    isPassword = password,
                )
                val text = if (redact && !rawText.isNullOrBlank()) {
                    "[redacted]"
                } else {
                    SensitiveTextRedactor.redactVisibleText(rawText?.limit())
                }
                val description = if (redact && !rawDescription.isNullOrBlank()) {
                    "[redacted]"
                } else {
                    SensitiveTextRedactor.redactVisibleText(rawDescription?.limit())
                }
                val signature = "$nodePackage|$className|$text|$description|$resourceId|$rect"
                if (!interactive && seen.contains(signature)) {
                    // Duplicate non-interactive label (common in WebViews).
                } else {
                    seen.add(signature)
                    if (elements.size >= MAX_ELEMENTS) {
                        truncated = true
                        return
                    }
                    val id = "element_${elements.size + 1}"
                    val role = roleFor(className, node)
                    val element = UiElement(
                        id = id,
                        className = className,
                        text = text,
                        description = description,
                        resourceId = resourceId?.takeIf { it.isNotBlank() },
                        packageName = nodePackage,
                        bounds = ElementBounds(rect.left, rect.top, rect.right, rect.bottom),
                        parentId = parentId,
                        clickable = node.isClickable,
                        longClickable = node.isLongClickable,
                        scrollable = node.isScrollable,
                        editable = node.isEditable,
                        checkable = node.isCheckable,
                        checked = node.isChecked,
                        selected = node.isSelected,
                        enabled = node.isEnabled,
                        focused = node.isFocused,
                        password = password,
                        clickableAncestor = clickableAncestor && !node.isClickable,
                        role = role,
                    )
                    elements += element
                    refs[id] = ElementRef(
                        id = id,
                        className = className,
                        text = text,
                        description = description,
                        resourceId = resourceId,
                        packageName = nodePackage,
                        bounds = element.bounds,
                        clickable = element.clickable,
                        longClickable = element.longClickable,
                        scrollable = element.scrollable,
                        editable = element.editable,
                        password = element.password,
                        checked = element.checked,
                    )
                    childParentId = id
                }
            }

            val nextClickableAncestor = clickableAncestor || node.isClickable
            for (index in 0 until node.childCount) {
                traverse(node.getChild(index), childParentId, nextClickableAncestor, depth + 1)
            }
        }

        traverse(root, null, false, 0)

        val appLabel = appPackage?.let { pkg ->
            runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }
        val windowTitle = runCatching {
            service.windows.firstOrNull { it.isActive }?.title?.toString()
        }.getOrNull()
        val dialog = detectDialog(service, elements, root, windowTitle)

        var secure = false
        var screenshotWidth = 0
        var screenshotHeight = 0
        var screenshotSource: String? = null
        var scaleX = 1f
        var scaleY = 1f
        var screenshotBytes: com.piko.agent.model.ScreenshotCapture? = null
        if (includeScreenshot) {
            screenshotEngine.lastSettings = settings
            val shot = screenshotEngine.capture(settings)
            secure = shot.secure
            if (shot.capture != null) {
                screenshotBytes = shot.capture
                screenshotWidth = shot.capture.width
                screenshotHeight = shot.capture.height
                screenshotSource = shot.capture.source
                if (screenshotWidth > 0 && screenshotHeight > 0) {
                    scaleX = screenWidth.toFloat() / screenshotWidth
                    scaleY = screenHeight.toFloat() / screenshotHeight
                }
            }
        }

        val fingerprint = UiFingerprint.compute(
            packageName = appPackage,
            windowTitle = windowTitle,
            elements = elements.map { element ->
                FingerprintElement(
                    text = element.text,
                    description = element.description,
                    resourceId = element.resourceId,
                    bounds = FingerprintRect(
                        element.bounds.left,
                        element.bounds.top,
                        element.bounds.right,
                        element.bounds.bottom,
                    ),
                    checked = element.checked,
                    selected = element.selected,
                    enabled = element.enabled,
                )
            },
        )

        val observation = ScreenObservation(
            timestampMs = System.currentTimeMillis(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rotation = rotation,
            foregroundPackage = appPackage,
            appLabel = appLabel,
            windowTitle = windowTitle,
            elements = elements,
            fingerprint = fingerprint,
            secureScreen = secure,
            rootAvailable = true,
            hasDialog = dialog,
            truncated = truncated,
            focusedElementId = elements.firstOrNull { it.focused }?.id,
            screenshotIncluded = screenshotBytes != null,
            screenshotWidth = screenshotWidth,
            screenshotHeight = screenshotHeight,
            screenshotSource = screenshotSource,
            screenshotScaleX = scaleX,
            screenshotScaleY = scaleY,
            screenshotMimeType = screenshotBytes?.let { "image/jpeg" },
        )
        ObservationResult(
            observation = observation,
            screenshot = screenshotBytes,
            refs = refs,
        )
    }

    private fun detectDialog(
        service: PikoAccessibilityService,
        elements: List<UiElement>,
        root: AccessibilityNodeInfo,
        windowTitle: String?,
    ): Boolean {
        val lowerTitle = windowTitle?.lowercase(Locale.ROOT).orEmpty()
        if (root.className?.toString()?.contains("Dialog", ignoreCase = true) == true) return true
        if (lowerTitle.contains("dialog") || lowerTitle.contains("alert")) return true
        val texts = elements.mapNotNull { it.text?.lowercase(Locale.ROOT) }.toSet()
        val permissionLike = (texts.contains("allow") || texts.contains("while using the app")) &&
            (texts.contains("deny") || texts.contains("don't allow") || texts.contains("cancel"))
        if (permissionLike) return true
        val dialogWindows = runCatching {
            service.windows.count { window ->
                window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
                    window.isActive
            }
        }.getOrDefault(0)
        return dialogWindows > 0 && texts.size < 40
    }

    private fun roleFor(className: String?, node: AccessibilityNodeInfo): String {
        val name = className?.substringAfterLast('.').orEmpty()
        return when {
            node.isEditable -> "text field"
            name.contains("Button", true) -> "button"
            name.contains("CheckBox", true) -> "checkbox"
            name.contains("Switch", true) -> "switch"
            name.contains("Radio", true) -> "radio"
            name.contains("SeekBar", true) -> "slider"
            name.contains("Spinner", true) -> "dropdown"
            name.contains("RecyclerView", true) || name.contains("ListView", true) -> "list"
            name.contains("ScrollView", true) -> "scroll view"
            name.contains("Tab", true) -> "tab"
            name.contains("ImageView", true) || name.contains("ImageButton", true) -> "image"
            name.contains("WebView", true) -> "web view"
            name.contains("TextView", true) -> "text"
            node.isClickable -> "clickable"
            else -> "view"
        }
    }

    private fun String.limit(): String = replace(Regex("\\s+"), " ").trim().take(MAX_TEXT_LENGTH)

    companion object {
        private const val MAX_ELEMENTS = 120
        private const val MAX_VISITED = 3000
        private const val MAX_DEPTH = 40
        private const val MAX_TEXT_LENGTH = 180
    }
}
