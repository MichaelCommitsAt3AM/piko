package com.piko.agent.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.piko.agent.MainActivity
import com.piko.agent.agent.AgentRuntimeState
import com.piko.agent.core.AgentState
import kotlin.math.abs

/**
 * Draggable floating control. Built with platform views so it stays responsive
 * during model requests and does not depend on the activity lifecycle.
 */
@SuppressLint("ClickableViewAccessibility")
class AgentOverlay(
    private val context: Context,
    private val actions: Actions,
) {
    interface Actions {
        fun onPauseResume()
        fun onStop()
        fun onReviewConfirmation()
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val screenWidth: Int
        get() = context.resources.displayMetrics.widthPixels

    private var params = layoutParams()
    private var expandedView: View? = null
    private var minimizedView: View? = null
    private var expanded = true
    private var attached = false
    private var suppressController: ((Boolean) -> Unit)? = null

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var goalText: TextView
    private lateinit var actionText: TextView
    private lateinit var confirmationText: TextView
    private lateinit var reviewButton: TextView
    private lateinit var pauseButton: TextView
    private lateinit var stopButton: TextView
    private lateinit var minimizeButton: TextView
    private lateinit var bubbleStatusDot: View

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    fun show(state: AgentRuntimeState) {
        if (!canShow()) return
        if (attached) {
            update(state)
            return
        }
        runCatching {
            expandedView = buildExpanded()
            windowManager.addView(expandedView, params)
            attached = true
            expanded = true
            val suppress: (Boolean) -> Unit = { visible ->
                expandedView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                minimizedView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            }
            suppressController = suppress
            OverlaySuppressor.register(suppress)
            update(state)
        }
    }

    fun hide() {
        suppressController?.let(OverlaySuppressor::unregister)
        suppressController = null
        if (!attached) return
        runCatching {
            expandedView?.let { windowManager.removeView(it) }
            minimizedView?.let { windowManager.removeView(it) }
        }
        expandedView = null
        minimizedView = null
        attached = false
    }

    fun update(state: AgentRuntimeState) {
        if (!attached) return
        val active = state.state.isRunning || state.state == AgentState.PAUSED
        statusText.text = buildString {
            append(stateTitle(state.state))
            if (state.step > 0) append(" · ${state.step}${if (state.maxSteps > 0) "/${state.maxSteps}" else ""}")
        }
        val color = stateColor(state.state)
        statusDot.background = circle(color)
        bubbleStatusDot.background = circle(color)
        goalText.text = state.goal.ifBlank { "No task" }
        actionText.text = state.currentAction
            ?: state.pauseReason
            ?: state.error
            ?: if (state.state == AgentState.COMPLETED) (state.resultSummary ?: "Completed") else "Idle"
        val pending = state.pendingConfirmation
        confirmationText.visibility = if (pending != null) View.VISIBLE else View.GONE
        reviewButton.visibility = if (pending != null) View.VISIBLE else View.GONE
        if (pending != null) {
            confirmationText.text = "Approval needed: ${pending.summary}"
        }
        pauseButton.text = if (state.state == AgentState.PAUSED) "Resume" else "Pause"
        pauseButton.isEnabled = state.state.isRunning || state.state == AgentState.PAUSED
        val pauseColor = if (state.state == AgentState.PAUSED) LIME else PANEL_LIGHT
        pauseButton.setTextColor(if (state.state == AgentState.PAUSED) Color.BLACK else Color.WHITE)
        pauseButton.background = pill(pauseColor)
        stopButton.isEnabled = active
        if (state.state.isTerminal) {
            hide()
        }
    }

    private fun buildExpanded(): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xF2111623.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            elevation = dp(12).toFloat()
        }
        val width = minOf(dp(336), screenWidth - dp(32))
        root.minimumWidth = width
        root.layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusDot = View(context).apply {
            background = circle(LIME)
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) }
        }
        header.addView(statusDot)
        val brand = TextView(context).apply {
            text = "Piko"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        header.addView(brand)
        statusText = TextView(context).apply {
            setTextColor(0xFFAEB8CA.toInt())
            textSize = 11f
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
        }
        header.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        minimizeButton = TextView(context).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)
            contentDescription = "Minimize Piko overlay"
            setOnClickListener { minimize() }
        }
        header.addView(minimizeButton)
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        goalText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
            setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(goalText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        actionText = TextView(context).apply {
            setTextColor(0xFFD4FF4F.toInt())
            textSize = 12f
            maxLines = 2
        }
        root.addView(actionText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        confirmationText = TextView(context).apply {
            setTextColor(0xFFFFC857.toInt())
            textSize = 11f
            maxLines = 2
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        root.addView(confirmationText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }
        reviewButton = pillButton("Review", 0xFFFFC857.toInt(), Color.BLACK).apply {
            visibility = View.GONE
            setOnClickListener { actions.onReviewConfirmation() }
        }
        pauseButton = pillButton("Pause", PANEL_LIGHT, Color.WHITE).apply {
            setOnClickListener { actions.onPauseResume() }
        }
        stopButton = pillButton("Stop", 0xFFF35B6B.toInt(), Color.WHITE).apply {
            setOnClickListener { actions.onStop() }
        }
        controls.addView(reviewButton)
        controls.addView(pauseButton)
        controls.addView(stopButton)
        root.addView(controls, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        attachDrag(root, header, isBubble = false)
        return root
    }

    private fun buildMinimized(): View {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(LIME)
                setStroke(dp(2), 0x66000000)
            }
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54))
            elevation = dp(10).toFloat()
            contentDescription = "Expand Piko overlay"
        }
        val label = TextView(context).apply {
            text = "P"
            setTextColor(Color.BLACK)
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(label)
        bubbleStatusDot = View(context).apply {
            background = circle(0xFF111623.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        container.addView(bubbleStatusDot)
        attachDrag(container, container, isBubble = true)
        return container
    }

    private fun minimize() {
        if (!attached) return
        runCatching {
            expandedView?.let { windowManager.removeView(it) }
            minimizedView = buildMinimized()
            windowManager.addView(minimizedView, params)
            expanded = false
        }
    }

    private fun expand() {
        if (!attached) return
        runCatching {
            minimizedView?.let { windowManager.removeView(it) }
            expandedView = buildExpanded()
            windowManager.addView(expandedView, params)
            expanded = true
            update(com.piko.agent.agent.AgentRuntime.state())
        }
    }

    private fun attachDrag(view: View, handle: View, isBubble: Boolean) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) dragging = true
                    if (dragging) {
                        val maxX = maxOf(0, screenWidth - (view.width.takeIf { it > 0 } ?: dp(60)))
                        params.x = (startX + dx).coerceIn(0, maxX)
                        params.y = (startY + dy).coerceAtLeast(0)
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isBubble && !dragging) expand()
                    true
                }
                else -> false
            }
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(104)
    }

    private fun pillButton(label: String, color: Int, textColor: Int): TextView =
        TextView(context).apply {
            text = label
            setTextColor(textColor)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = pill(color)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
        }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = dp(50).toFloat()
        setColor(color)
    }

    private fun stateTitle(state: AgentState): String = when (state) {
        AgentState.IDLE -> "Idle"
        AgentState.PREPARING -> "Preparing"
        AgentState.OBSERVING -> "Observing"
        AgentState.REASONING -> "Thinking"
        AgentState.EXECUTING -> "Acting"
        AgentState.VERIFYING -> "Verifying"
        AgentState.AWAITING_USER_CONFIRMATION -> "Needs approval"
        AgentState.PAUSED -> "Paused"
        AgentState.COMPLETED -> "Done"
        AgentState.FAILED -> "Failed"
        AgentState.CANCELLED -> "Stopped"
    }

    private fun stateColor(state: AgentState): Int = when (state) {
        AgentState.PAUSED, AgentState.AWAITING_USER_CONFIRMATION -> 0xFFFFC857.toInt()
        AgentState.FAILED, AgentState.CANCELLED -> 0xFFF35B6B.toInt()
        AgentState.COMPLETED -> 0xFF65E6B4.toInt()
        else -> LIME
    }

    private fun openApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val LIME = 0xFFD4FF4F.toInt()
        private const val PANEL_LIGHT = 0x33FFFFFF
    }
}
