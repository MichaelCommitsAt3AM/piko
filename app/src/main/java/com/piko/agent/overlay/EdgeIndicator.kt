package com.piko.agent.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Four narrow windows mark control without covering the app's touch area. */
internal class EdgeIndicator(private val context: Context, private val overlayType: Int) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<View>()

    fun show() {
        if (views.isNotEmpty()) return
        val thickness = (3 * context.resources.displayMetrics.density).toInt().coerceAtLeast(2)
        val match = WindowManager.LayoutParams.MATCH_PARENT
        val specs = listOf(
            Triple(thickness, match, Gravity.LEFT or Gravity.TOP),
            Triple(thickness, match, Gravity.RIGHT or Gravity.TOP),
            Triple(match, thickness, Gravity.TOP or Gravity.LEFT),
            Triple(match, thickness, Gravity.BOTTOM or Gravity.LEFT),
        )
        try {
            for ((width, height, gravity) in specs) {
                val view = View(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                val params = WindowManager.LayoutParams(
                    width, height, overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply { this.gravity = gravity; alpha = 0.7f }
                manager.addView(view, params)
                views += view
            }
        } catch (error: Exception) {
            hide()
            throw error
        }
    }

    fun update(color: Int) { views.forEach { it.setBackgroundColor(color) } }
    fun visible(visible: Boolean) {
        views.forEach { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
    }
    fun hide() {
        views.forEach { runCatching { manager.removeView(it) } }
        views.clear()
    }
}
