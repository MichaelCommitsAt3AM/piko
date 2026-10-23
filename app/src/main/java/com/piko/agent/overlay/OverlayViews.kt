package com.piko.agent.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Small platform-view building blocks for the floating assistant. They stay
 * dependency-free so the overlay remains responsive while a model request runs.
 */

/** Circular icon button with a built-in press ripple and runtime tinting. */
internal class RoundIconButton(
    context: Context,
    iconRes: Int,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val icon = ImageView(context).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        addView(icon, LayoutParams(dp(20), dp(20), Gravity.CENTER))
        isClickable = true
        isFocusable = true
    }

    fun style(diameter: Int, fill: Int, tint: Int, iconSize: Int = 20, stroke: Int? = null) {
        layoutParams = (layoutParams ?: LayoutParams(dp(diameter), dp(diameter))).apply {
            width = dp(diameter)
            height = dp(diameter)
        }
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (stroke != null) setStroke(dp(1), stroke)
        }
        icon.setColorFilter(tint)
        icon.layoutParams = (icon.layoutParams as LayoutParams).apply {
            width = dp(iconSize)
            height = dp(iconSize)
        }
        foreground = rippleMask(oval = true)
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun rippleMask(oval: Boolean): RippleDrawable {
        val mask = GradientDrawable().apply {
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = dp(23).toFloat()
            setColor(0xFFFFFFFF.toInt())
        }
        return RippleDrawable(ColorStateList.valueOf(0x33808080.toInt()), null, mask)
    }
}
