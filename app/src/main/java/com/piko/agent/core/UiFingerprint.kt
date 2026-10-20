package com.piko.agent.core

import java.security.MessageDigest

/**
 * Builds a stable fingerprint of the meaningful parts of a screen. Used to
 * detect stale observations, repeated actions and screens that did not settle.
 */
object UiFingerprint {
    fun compute(
        packageName: String?,
        elements: List<FingerprintElement>,
        windowTitle: String? = null,
    ): String {
        val builder = StringBuilder()
        builder.append(packageName.orEmpty()).append('|').append(windowTitle.orEmpty())
        elements.asSequence()
            .filter { it.text != null || it.description != null || it.resourceId != null }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.text }, { it.description }))
            .take(160)
            .forEach { element ->
                builder.append('|')
                    .append(element.text.orEmpty().take(60))
                    .append('~')
                    .append(element.description.orEmpty().take(60))
                    .append('~')
                    .append(element.resourceId.orEmpty().substringAfterLast('/'))
                    .append('~')
                    .append(element.bounds.left).append(',')
                    .append(element.bounds.top).append(',')
                    .append(element.bounds.right).append(',')
                    .append(element.bounds.bottom)
                    .append('~')
                    .append(element.checked)
                    .append(element.selected)
                    .append(element.enabled)
            }
        return sha256(builder.toString())
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

data class FingerprintRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

data class FingerprintElement(
    val text: String?,
    val description: String?,
    val resourceId: String?,
    val bounds: FingerprintRect,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)
