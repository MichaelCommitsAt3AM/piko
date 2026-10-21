package com.piko.agent.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.piko.agent.core.AgentState
import kotlin.math.sin

/** Transparent edge windows carry a broad atmospheric glow and never receive touches. */
internal class EdgeIndicator(private val context: Context, private val overlayType: Int) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val views = mutableListOf<GlowView>()
    private var state = AgentState.IDLE

    // Android reports physical density approximately; 20 mm gives the intended ~2 cm reach.
    private val glowReach: Float
        get() {
            val bounds = manager.currentWindowMetrics.bounds
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_MM, 20f, context.resources.displayMetrics,
            ).coerceIn(1f, minOf(bounds.width(), bounds.height()) * 0.45f)
        }

    fun show() {
        if (views.isNotEmpty()) return
        val thickness = kotlin.math.ceil(glowReach).toInt()
        val match = WindowManager.LayoutParams.MATCH_PARENT
        val specs = listOf(
            Triple(thickness, match, Gravity.LEFT or Gravity.TOP),
            Triple(thickness, match, Gravity.RIGHT or Gravity.TOP),
            Triple(match, thickness, Gravity.TOP or Gravity.LEFT),
            Triple(match, thickness, Gravity.BOTTOM or Gravity.LEFT),
        )
        try {
            for ((width, height, gravity) in specs) {
                val view = GlowView(context, width == match).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                val params = WindowManager.LayoutParams(
                    width, height, overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    this.gravity = gravity
                    // All four strips share physical screen coordinates, including system bars.
                    setFitInsetsTypes(0)
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                manager.addView(view, params)
                views += view
            }
        } catch (error: Exception) {
            hide()
            throw error
        }
    }

    fun update(state: AgentState) {
        this.state = state
        views.forEach { it.invalidate() }
    }

    fun visible(visible: Boolean) {
        views.forEach { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
    }

    fun hide() {
        views.forEach { runCatching { manager.removeView(it) } }
        views.clear()
    }

    private inner class GlowView(context: Context, private val horizontal: Boolean) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val frame = RectF()
        private val rotation = Matrix()
        private val location = IntArray(2)
        private var shader: SweepGradient? = null
        private var screenWidth = 0
        private var screenHeight = 0

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (state == AgentState.IDLE || state.isTerminal) return
            val bounds = manager.currentWindowMetrics.bounds
            if (bounds.width() != screenWidth || bounds.height() != screenHeight || shader == null) {
                screenWidth = bounds.width()
                screenHeight = bounds.height()
                shader = SweepGradient(screenWidth / 2f, screenHeight / 2f, intArrayOf(
                    0xFF68B8FF.toInt(), 0xFF7775FF.toInt(), 0xFFCB9EFF.toInt(),
                    0xFF79E0F4.toInt(), 0xFF68B8FF.toInt(),
                ), null)
            }
            val waiting = state == AgentState.PAUSED || state == AgentState.AWAITING_USER_CONFIRMATION
            val moving = !waiting && ValueAnimator.areAnimatorsEnabled()
            val time = (SystemClock.uptimeMillis() % 12000L) / 12000f
            rotation.setRotate(if (moving) time * 360f else 0f, screenWidth / 2f, screenHeight / 2f)
            shader?.setLocalMatrix(rotation)
            paint.shader = if (waiting) null else shader
            paint.color = if (waiting) 0xFFFFC857.toInt() else android.graphics.Color.WHITE
            val pulse = if (moving) 0.88f + 0.12f * sin(time * Math.PI * 8).toFloat() else 1f
            getLocationOnScreen(location)
            val save = canvas.save()
            // Vertical strips own the corners; horizontal strips exclude them to avoid seams.
            if (horizontal) {
                val band = kotlin.math.ceil(glowReach).toFloat()
                canvas.clipRect(band, 0f, width - band, height.toFloat())
            }
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            frame.set(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat())
            val radius = 28 * density
            // Many faint layers fade into the app without a bright outline.
            // Shared screen coordinates keep the four windows visually seamless.
            val reach = glowReach * pulse
            for (layer in 64 downTo 1) {
                paint.strokeWidth = 2f * reach * layer / 64f
                paint.alpha = 2
                canvas.drawRoundRect(frame, radius, radius, paint)
            }
            canvas.restoreToCount(save)
            if (moving && isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
        }
    }
}
