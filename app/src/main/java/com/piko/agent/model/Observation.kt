package com.piko.agent.model

import kotlinx.serialization.Serializable

@Serializable
data class ElementBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
}

@Serializable
data class UiElement(
    val id: String,
    val className: String? = null,
    val text: String? = null,
    val description: String? = null,
    val resourceId: String? = null,
    val packageName: String? = null,
    val bounds: ElementBounds,
    val parentId: String? = null,
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val scrollable: Boolean = false,
    val editable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val password: Boolean = false,
    val clickableAncestor: Boolean = false,
    val role: String? = null,
)

@Serializable
data class ScreenObservation(    val timestampMs: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val rotation: Int,
    val foregroundPackage: String? = null,
    val appLabel: String? = null,
    val windowTitle: String? = null,
    val elements: List<UiElement> = emptyList(),
    val fingerprint: String = "",
    val secureScreen: Boolean = false,
    val rootAvailable: Boolean = true,
    val hasDialog: Boolean = false,
    val truncated: Boolean = false,
    val focusedElementId: String? = null,
    val screenshotIncluded: Boolean = false,
    val screenshotWidth: Int = 0,
    val screenshotHeight: Int = 0,
    val screenshotSource: String? = null,
    val screenshotScaleX: Float = 1f,
    val screenshotScaleY: Float = 1f,
    val screenshotMimeType: String? = null,
) {
    val bounds get() = com.piko.agent.core.ScreenBounds(screenWidth, screenHeight)
    val interactiveCount: Int get() = elements.count { it.clickable || it.editable || it.scrollable || it.longClickable }
}

data class ScreenshotCapture(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val source: String,
    val capturedAtMs: Long,
    val secure: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = bytes.contentHashCode()
}

data class ElementRef(
    val id: String,
    val className: String?,
    val text: String?,
    val description: String?,
    val resourceId: String?,
    val packageName: String?,
    val bounds: ElementBounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val checked: Boolean,
)

data class ObservationResult(
    val observation: ScreenObservation?,
    val screenshot: ScreenshotCapture? = null,
    val refs: Map<String, ElementRef> = emptyMap(),
    val error: String? = null,
)
