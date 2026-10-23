package com.piko.agent.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.piko.agent.R
import com.piko.agent.agent.AgentRuntimeState
import com.piko.agent.core.AgentState
import kotlin.math.abs
import kotlin.math.max

/**
 * Draggable floating assistant anchored to the bottom of the screen. The
 * current stage is the primary content: a status line and a headline explain
 * what Piko is doing right now. Built with platform views so it stays
 * responsive while model requests are in flight and does not depend on the
 * activity lifecycle.
 */
@SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
class AgentOverlay(
    private val context: Context,
    private val actions: Actions,
    private val persistent: Boolean = false,
) {
    interface Actions {
        fun onPauseResume()
        fun onStop()
        fun onVoice()
        fun onText(text: String) {}
        fun onTyping(active: Boolean) {}
        fun onDismiss() {}
        fun onApproveConfirmation(confirmationId: String)
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("piko_overlay_position", Context.MODE_PRIVATE)

    private val overlayType = if (context is AccessibilityService)
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private val edges = EdgeIndicator(context, overlayType)

    private var panelX = prefs.getInt(KEY_X, 0)
    private var panelY = prefs.getInt(KEY_Y, 0)
    private var panelPositioned = prefs.getBoolean(KEY_ANCHORED, false)
    private var panelYBeforeKeyboard: Int? = null
    private var keyboardInset = 0

    private var params = layoutParams()
    private var expandedView: View? = null
    private var minimizedView: View? = null
    private var expanded = true
    private var attached = false
    private var suppressController: ((Boolean) -> Unit)? = null

    private lateinit var tokens: Tokens
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var stageText: TextView
    private lateinit var detailText: TextView
    private lateinit var reviewButton: LinearLayout
    private lateinit var stopButton: LinearLayout
    private lateinit var stopIcon: ImageView
    private lateinit var stopLabel: TextView
    private lateinit var screenButton: TextView
    private lateinit var footer: LinearLayout
    private lateinit var sendButton: RoundIconButton
    private lateinit var input: EditText
    private lateinit var bubbleStatusDot: View

    private var inputActive = false
    private var completingInput = false
    private var touchable = true

    fun canShow(): Boolean = context is AccessibilityService || Settings.canDrawOverlays(context)

    fun show(state: AgentRuntimeState) {
        if (!canShow()) return
        if (attached) {
            if (!OverlaySuppressor.isSuppressed) setVisible(true)
            update(state)
            return
        }
        runCatching {
            edges.show()
            params.width = panelWidthPx()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            touchable = true
            expandedView = buildExpanded()
            windowManager.addView(expandedView, params)
            attached = true
            expanded = true
            val suppress: (Boolean) -> Unit = { visible -> setVisible(visible) }
            suppressController = suppress
            OverlaySuppressor.register(suppress)
            update(state)
            placeExpanded(expandedView)
        }.onFailure {
            hide()
            android.util.Log.w("PikoOverlay", "Could not display floating controls", it)
        }
    }

    private fun setVisible(visible: Boolean) {
        expandedView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        minimizedView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        edges.visible(visible)
    }

    fun hide() {
        closeInputSession()
        panelYBeforeKeyboard?.let { panelY = it }
        panelYBeforeKeyboard = null
        keyboardInset = 0
        suppressController?.let(OverlaySuppressor::unregister)
        suppressController = null
        edges.hide()
        listOfNotNull(expandedView, minimizedView).forEach { view ->
            if (view.parent != null) runCatching { windowManager.removeView(view) }
        }
        expandedView = null
        minimizedView = null
        attached = false
    }

    fun update(state: AgentRuntimeState) {
        if (!attached) return
        val color = stateColor(state)
        edges.update(state.state)

        if (!expanded) {
            setTouchable(state.state != AgentState.EXECUTING)
            if (::bubbleStatusDot.isInitialized) bubbleStatusDot.background = dot(color, tokens.panel)
            return
        }

        val stage = describe(state)
        val active = state.state.isRunning || state.state == AgentState.PAUSED
        val pending = state.pendingConfirmation

        statusText.text = stage.status
        statusDot.background = circle(color)

        stageText.text = stage.headline
        detailText.text = stage.detail.orEmpty()
        detailText.visibility = if (stage.detail.isNullOrBlank()) View.GONE else View.VISIBLE

        reviewButton.visibility = if (pending != null && !state.state.isTerminal) View.VISIBLE else View.GONE
        reviewButton.isEnabled = pending != null && !state.state.isTerminal
        reviewButton.setOnClickListener(if (pending != null && !state.state.isTerminal) View.OnClickListener {
            closeInputSession()
            reviewButton.isEnabled = false
            actions.onApproveConfirmation(pending.id)
        } else null)

        val stopActive = active
        val stopColor = if (stopActive) tokens.onDanger else tokens.onDisabled
        stopButton.isEnabled = stopActive
        stopButton.background = if (stopActive) rippled(tokens.danger, 23) else rounded(tokens.disabled, 23)
        stopLabel.setTextColor(stopColor)
        stopIcon.setColorFilter(stopColor)

        screenButton.isEnabled = !active
        screenButton.alpha = if (active) 0.4f else 1f
        footer.visibility = if (active) View.GONE else View.VISIBLE

        setTouchable(state.state != AgentState.EXECUTING)
        if (state.state.isTerminal && !persistent) {
            hide()
        } else {
            expandedView?.let { placeExpanded(it) }
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildExpanded(): View {
        tokens = currentTokens()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(tokens.panel, 26, tokens.panelStroke)
            elevation = dp(18).toFloat()
            clipToOutline = true
            alpha = 0f
        }

        buildHeader(root)
        buildStage(root)
        buildControls(root)
        buildInput(root)
        buildFooter(root)

        attachDrag(root, root.getChildAt(0), isBubble = false)
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                closeInputSession()
                true
            } else {
                false
            }
        }
        root.viewTreeObserver.addOnWindowFocusChangeListener { hasFocus ->
            if (!hasFocus && inputActive && !completingInput) cancelInput()
        }
        root.setOnApplyWindowInsetsListener { _, insets ->
            onKeyboardInset(insets.getInsets(WindowInsets.Type.ime()).bottom)
            insets
        }
        root.post {
            root.animate().alpha(1f).setDuration(180L).start()
            root.requestApplyInsets()
        }
        return root
    }

    private fun buildHeader(root: LinearLayout) {
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val avatar = FrameLayout(context).apply {
            background = circle(tokens.accent)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_overlay_spark)
                setColorFilter(tokens.onAccent)
                layoutParams = FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER)
            })
        }
        header.addView(avatar)

        val titles = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        titles.addView(TextView(context).apply {
            text = "Piko"
            setTextColor(tokens.title)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        })
        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        statusDot = View(context).apply {
            background = circle(tokens.running)
            layoutParams = LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) }
        }
        statusRow.addView(statusDot)
        statusText = TextView(context).apply {
            setTextColor(tokens.muted)
            textSize = 12f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titles.addView(statusRow)
        header.addView(titles)

        val collapse = RoundIconButton(context, R.drawable.ic_overlay_chevron_down).apply {
            contentDescription = "Collapse Piko assistant"
            style(40, Color.TRANSPARENT, tokens.muted, iconSize = 21)
            setOnClickListener { minimize() }
        }
        header.addView(collapse, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun buildStage(root: LinearLayout) {
        val stage = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        stageText = TextView(context).apply {
            setTextColor(tokens.title)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.12f)
            letterSpacing = -0.015f
            includeFontPadding = false
        }
        stage.addView(stageText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        detailText = TextView(context).apply {
            setTextColor(tokens.muted)
            textSize = 13f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(5), 0, 0)
            visibility = View.GONE
        }
        stage.addView(detailText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        reviewButton = centeredAction(
            iconRes = R.drawable.ic_overlay_check,
            label = "Approve and continue",
            iconSize = 18,
            fill = tokens.warn,
            tint = tokens.onWarn,
        ).apply {
            visibility = View.GONE
            contentDescription = "Approve pending action and continue task"
        }
        stage.addView(reviewButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44),
        ).apply { topMargin = dp(12) })

        root.addView(stage, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun buildControls(root: LinearLayout) {
        stopButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rippled(tokens.danger, 23)
            isClickable = true
            isFocusable = true
            contentDescription = "Stop current task"
            setOnClickListener {
                closeInputSession()
                actions.onStop()
            }
        }
        stopIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_overlay_stop)
            setColorFilter(tokens.onDanger)
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(8) }
        }
        stopLabel = TextView(context).apply {
            text = "Stop"
            setTextColor(tokens.onDanger)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        }
        stopButton.addView(stopIcon)
        stopButton.addView(stopLabel)
        root.addView(stopButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46),
        ).apply { topMargin = dp(14) })
    }

    private fun centeredAction(
        iconRes: Int,
        label: String,
        iconSize: Int,
        fill: Int,
        tint: Int,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rippled(fill, 23)
        isClickable = true
        isFocusable = true
        addView(ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            layoutParams = LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { marginEnd = dp(8) }
        })
        addView(TextView(context).apply {
            text = label
            setTextColor(tint)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
        })
    }

    private fun buildInput(root: LinearLayout) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }

        input = EditText(context).apply {
            hint = "Message Piko…"
            setTextColor(tokens.title)
            setHintTextColor(tokens.muted)
            textSize = 14f
            maxLines = 3
            minHeight = dp(46)
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(tokens.field, 23, tokens.fieldStroke)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            textCursorDrawable?.setTint(tokens.accent)
            highlightColor = 0x33808080
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) beginInput()
                false
            }
            setOnFocusChangeListener { _, focused ->
                background = rounded(tokens.field, 23, if (focused) tokens.muted else tokens.fieldStroke)
                if (!focused && inputActive && !completingInput) cancelInput()
            }
            doAfterTextChanged { refreshSendButton() }
            setOnEditorActionListener { _, actionId, event ->
                val send = actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
                if (send) {
                    sendMessage()
                    true
                } else {
                    false
                }
            }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        sendButton = RoundIconButton(context, R.drawable.ic_overlay_send).apply {
            contentDescription = "Send message"
            setOnClickListener { sendMessage() }
        }
        row.addView(sendButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })

        val micButton = RoundIconButton(context, R.drawable.ic_overlay_mic).apply {
            contentDescription = "Talk to Piko"
            style(46, tokens.field, tokens.title, iconSize = 20, stroke = tokens.fieldStroke)
            setOnClickListener {
                closeInputSession()
                actions.onVoice()
            }
        }
        row.addView(micButton, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginStart = dp(8) })

        root.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })
        refreshSendButton()
    }

    private fun buildFooter(root: LinearLayout) {
        footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        screenButton = footerAction("Ask screen") {
            closeInputSession()
            context.startActivity(
                Intent(context, com.piko.agent.VoiceInputActivity::class.java)
                    .putExtra("screenQuestion", true)
                    .putExtra("useVoice", false)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        footer.addView(screenButton)
        footer.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        footer.addView(footerAction("Dismiss") {
            hide()
            actions.onDismiss()
        })
        root.addView(footer, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun buildMinimized(): View {
        val container = FrameLayout(context).apply {
            background = circle(tokens.accent)
            layoutParams = FrameLayout.LayoutParams(dp(56), dp(56))
            elevation = dp(14).toFloat()
            contentDescription = "Open Piko assistant"
            isClickable = true
            isFocusable = true
        }
        container.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_overlay_spark)
            setColorFilter(tokens.onAccent)
            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
        })
        bubbleStatusDot = View(context).apply {
            background = dot(tokens.running, tokens.panel)
            layoutParams = FrameLayout.LayoutParams(dp(13), dp(13), Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(2)
                bottomMargin = dp(2)
            }
        }
        container.addView(bubbleStatusDot)
        attachDrag(container, container, isBubble = true)
        return container
    }

    // -------------------------------------------------------------- behaviour

    private fun minimize() {
        if (!attached || !expanded) return
        closeInputSession()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching {
            expandedView?.let { windowManager.removeView(it) }
            minimizedView = buildMinimized()
            params.width = dp(56)
            params.height = dp(56)
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            touchable = true
            params.x = (screenWidth() - dp(56) - dp(16)).coerceAtLeast(0)
            params.y = bottomInset() + dp(24)
            windowManager.addView(minimizedView, params)
            expanded = false
            if (OverlaySuppressor.isSuppressed) minimizedView?.visibility = View.INVISIBLE
            update(com.piko.agent.agent.AgentRuntime.state())
        }
    }

    private fun expand() {
        if (!attached || expanded) return
        runCatching {
            closeInputSession()
            minimizedView?.let { windowManager.removeView(it) }
            minimizedView = null
            params.width = panelWidthPx()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            touchable = true
            expandedView = buildExpanded()
            params.x = panelX
            params.y = panelY
            windowManager.addView(expandedView, params)
            expanded = true
            if (OverlaySuppressor.isSuppressed) expandedView?.visibility = View.INVISIBLE
            update(com.piko.agent.agent.AgentRuntime.state())
            placeExpanded(expandedView)
        }
    }

    private fun placeExpanded(view: View?) {
        view ?: return
        view.post {
            if (!attached || !expanded || expandedView !== view) return@post
            val height = view.height.takeIf { it > 0 } ?: return@post
            val width = view.width.takeIf { it > 0 } ?: return@post
            if (!panelPositioned) {
                panelX = (screenWidth() - width) / 2
                panelY = bottomInset() + dp(12)
            }
            panelX = panelX.coerceIn(0, max(0, screenWidth() - width))
            panelY = panelY.coerceIn(0, max(0, screenHeight() - height))
            if (params.x != panelX || params.y != panelY) {
                params.x = panelX
                params.y = panelY
                runCatching { windowManager.updateViewLayout(view, params) }
            }
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
                        val width = view.width.takeIf { it > 0 } ?: dp(56)
                        val height = view.height.takeIf { it > 0 } ?: dp(56)
                        params.x = (startX + dx).coerceIn(0, max(0, screenWidth() - width))
                        params.y = (startY - dy).coerceIn(0, max(0, screenHeight() - height))
                        if (!isBubble) {
                            panelX = params.x
                            panelY = params.y
                        }
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        if (isBubble) {
                            val width = view.width.takeIf { it > 0 } ?: dp(56)
                            params.x = if (params.x + width / 2 < screenWidth() / 2) 0 else screenWidth() - width
                            runCatching { windowManager.updateViewLayout(view, params) }
                        } else {
                            panelX = params.x
                            panelY = params.y
                            panelPositioned = true
                            prefs.edit()
                                .putInt(KEY_X, panelX)
                                .putInt(KEY_Y, panelY)
                                .putBoolean(KEY_ANCHORED, true)
                                .apply()
                        }
                    }
                    if (isBubble && !dragging) expand()
                    true
                }
                else -> false
            }
        }
    }

    private fun beginInput() {
        if (!inputActive) {
            inputActive = true
            actions.onTyping(true)
        }
        setWindowFocusable(true)
        input.post {
            input.requestFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        // Some OEM overlay windows never dispatch IME insets. Lift the panel
        // once, after giving the system a chance to report the keyboard.
        input.postDelayed({
            if (inputActive && keyboardInset == 0) {
                if (panelYBeforeKeyboard == null) panelYBeforeKeyboard = panelY
                panelY = (screenHeight() * 0.45f).toInt()
                placeExpanded(expandedView)
            }
        }, 320L)
    }

    private fun onKeyboardInset(inset: Int) {
        if (inset == keyboardInset) return
        keyboardInset = inset
        if (inset > 0) {
            if (panelYBeforeKeyboard == null) panelYBeforeKeyboard = panelY
            panelY = inset + dp(12)
            placeExpanded(expandedView)
        } else {
            if (inputActive) cancelInput()
            restorePanelY()
        }
    }

    private fun restorePanelY() {
        val previous = panelYBeforeKeyboard ?: return
        panelYBeforeKeyboard = null
        panelY = previous
        placeExpanded(expandedView)
    }

    private fun sendMessage() {
        val text = input.text?.toString()?.trim()?.take(2000).orEmpty()
        if (text.isBlank()) return
        input.setText("")
        completingInput = true
        input.clearFocus()
        hideKeyboard()
        setWindowFocusable(false)
        inputActive = false
        refreshSendButton()
        actions.onText(text)
        actions.onTyping(false)
        completingInput = false
        if (keyboardInset == 0) restorePanelY()
    }

    private fun cancelInput() {
        inputActive = false
        hideKeyboard()
        setWindowFocusable(false)
        actions.onTyping(false)
        if (keyboardInset == 0) restorePanelY()
    }

    private fun closeInputSession() {
        completingInput = true
        inputActive = false
        runCatching { if (::input.isInitialized) input.clearFocus() }
        hideKeyboard()
        runCatching { setWindowFocusable(false) }
        completingInput = false
        actions.onTyping(false)
        if (keyboardInset == 0) restorePanelY()
    }

    private fun setWindowFocusable(focusable: Boolean) {
        val view = expandedView ?: return
        params.flags = if (focusable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun setTouchable(touchable: Boolean) {
        if (this.touchable == touchable) return
        val view = if (expanded) expandedView else minimizedView ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
        this.touchable = touchable
    }

    private fun hideKeyboard() {
        if (!::input.isInitialized) return
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun refreshSendButton() {
        if (!::sendButton.isInitialized) return
        val enabled = !input.text.isNullOrBlank()
        sendButton.isEnabled = enabled
        sendButton.style(
            46,
            if (enabled) tokens.accent else tokens.disabled,
            if (enabled) tokens.onAccent else tokens.onDisabled,
        )
    }

    // ----------------------------------------------------------------- state

    private data class Stage(val status: String, val headline: String, val detail: String?)

    private fun describe(state: AgentRuntimeState): Stage {
        val app = state.currentApp?.takeIf { it.isNotBlank() }
        val action = state.currentAction?.trim()?.takeIf { it.isNotBlank() }
        return when (state.state) {
            AgentState.IDLE -> Stage(
                "Idle",
                "Ready when you are",
                "Type a message or tap the microphone to ask Piko.",
            )
            AgentState.PREPARING -> Stage(
                "Preparing",
                action ?: "Getting ready…",
                state.goal.takeIf { it.isNotBlank() }?.let { "Task: $it" },
            )
            AgentState.OBSERVING -> Stage(
                "Reading screen",
                "Reading the screen…",
                app?.let { "Looking at $it" } ?: "Capturing what is on screen",
            )
            AgentState.REASONING -> Stage(
                "Thinking",
                action ?: "Planning the next step…",
                app?.let { "In $it" }
                    ?: state.modelName.takeIf { it.isNotBlank() }?.let { "Using $it" },
            )
            AgentState.EXECUTING -> Stage(
                "Acting",
                action ?: "Carrying out the step…",
                app?.let { "In $it" } ?: "Performing the step",
            )
            AgentState.VERIFYING -> Stage(
                "Verifying",
                "Checking the result…",
                app?.let { "In $it" } ?: "Comparing the screen",
            )
            AgentState.AWAITING_USER_CONFIRMATION -> Stage(
                "Needs approval",
                "Your approval is needed",
                state.pendingConfirmation?.let { pending ->
                    listOfNotNull(
                        pending.summary.takeIf { it.isNotBlank() },
                        pending.reason.takeIf { it.isNotBlank() },
                    ).joinToString("\n")
                } ?: "Open Piko to review the next action.",
            )
            AgentState.PAUSED -> {
                val reason = state.pauseReason?.takeIf { it.isNotBlank() }
                if (reason?.startsWith("Listening", ignoreCase = true) == true) {
                    Stage(
                        "Listening",
                        "Listening…",
                        "Send a message to steer the current task.",
                    )
                } else {
                    Stage(
                        "Paused",
                        "Paused",
                        reason ?: "Resume from the notification or the Piko app.",
                    )
                }
            }
            AgentState.COMPLETED -> {
                val headline = state.resultSummary?.takeIf { it.isNotBlank() } ?: "Task completed"
                Stage("Done", headline, state.verification?.takeIf { it.isNotBlank() && it != headline })
            }
            AgentState.FAILED -> {
                val headline = state.error?.takeIf { it.isNotBlank() }
                    ?: state.resultSummary?.takeIf { it.isNotBlank() }
                    ?: "The task could not be completed"
                val detail = state.verification
                    ?.takeIf { it.isNotBlank() && it != headline }
                    ?: state.resultSummary?.takeIf { it.isNotBlank() && it != headline }
                Stage("Failed", headline, detail)
            }
            AgentState.CANCELLED -> Stage(
                "Stopped",
                "Task stopped",
                state.error?.takeIf { it.isNotBlank() }
                    ?: state.resultSummary?.takeIf {
                        it.isNotBlank() && it != "Task cancelled before it started."
                    },
            )
        }
    }

    private fun stateColor(state: AgentRuntimeState): Int = when (state.state) {
        AgentState.IDLE -> tokens.muted
        AgentState.PAUSED, AgentState.AWAITING_USER_CONFIRMATION -> tokens.warn
        AgentState.FAILED -> tokens.failed
        AgentState.COMPLETED -> tokens.done
        AgentState.CANCELLED -> tokens.muted
        else -> tokens.running
    }

    // -------------------------------------------------------------- utilities

    private fun layoutParams() = WindowManager.LayoutParams(
        panelWidthPx(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        overlayType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.LEFT
        x = if (panelPositioned) {
            panelX
        } else {
            ((screenWidth() - panelWidthPx()) / 2).coerceAtLeast(0)
        }
        y = if (panelPositioned) panelY else bottomInset() + dp(12)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        setFitInsetsTypes(0)
    }

    private fun panelWidthPx(): Int =
        minOf(dp(356), screenWidth() - dp(24)).coerceAtLeast(dp(240))

    private fun screenWidth(): Int = windowManager.currentWindowMetrics.bounds.width()

    private fun screenHeight(): Int = windowManager.currentWindowMetrics.bounds.height()

    private fun bottomInset(): Int = windowManager.currentWindowMetrics.windowInsets
        .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom

    private fun footerAction(label: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(tokens.muted)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = dp(40)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(0x22808080.toInt()),
                null,
                null,
            )
            setOnClickListener { onClick() }
        }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun dot(color: Int, ring: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(2), ring)
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun rippled(color: Int, radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x33808080.toInt()),
            rounded(color, radiusDp),
            mask,
        )
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private data class Tokens(
        val panel: Int,
        val panelStroke: Int,
        val title: Int,
        val muted: Int,
        val field: Int,
        val fieldStroke: Int,
        val accent: Int,
        val onAccent: Int,
        val danger: Int,
        val onDanger: Int,
        val disabled: Int,
        val onDisabled: Int,
        val warn: Int,
        val onWarn: Int,
        val running: Int,
        val done: Int,
        val failed: Int,
    )

    private fun currentTokens(): Tokens {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) Tokens(
            panel = 0xFF1A1C19.toInt(),
            panelStroke = 0xFF363636.toInt(),
            title = 0xFFF0F0E9.toInt(),
            muted = 0xFFAAAAAA.toInt(),
            field = 0xFF262822.toInt(),
            fieldStroke = 0xFF3A3D35.toInt(),
            accent = 0xFFE5E5E5.toInt(),
            onAccent = 0xFF181818.toInt(),
            danger = 0xFFF2A1A1.toInt(),
            onDanger = 0xFF2A1010.toInt(),
            disabled = 0xFF2A2C26.toInt(),
            onDisabled = 0xFF77776F.toInt(),
            warn = 0xFFE3BD76.toInt(),
            onWarn = 0xFF251A00.toInt(),
            running = 0xFFE5E5E5.toInt(),
            done = 0xFF80C8A8.toInt(),
            failed = 0xFFF2A1A1.toInt(),
        ) else Tokens(
            panel = 0xFFF3F2EC.toInt(),
            panelStroke = 0xFFDCDBD3.toInt(),
            title = 0xFF252823.toInt(),
            muted = 0xFF666666.toInt(),
            field = 0xFFEAE9E2.toInt(),
            fieldStroke = 0xFFD3D2CA.toInt(),
            accent = 0xFF252823.toInt(),
            onAccent = 0xFFF8F7F3.toInt(),
            danger = 0xFFB3261E.toInt(),
            onDanger = 0xFFFFFFFF.toInt(),
            disabled = 0xFFE3E2DA.toInt(),
            onDisabled = 0xFF9A9A92.toInt(),
            warn = 0xFF805400.toInt(),
            onWarn = 0xFFFFFFFF.toInt(),
            running = 0xFF252823.toInt(),
            done = 0xFF24694F.toInt(),
            failed = 0xFFB3261E.toInt(),
        )
    }

    private companion object {
        const val KEY_X = "x"
        const val KEY_Y = "y"
        const val KEY_ANCHORED = "bottom_anchored"
    }
}
