package com.cocauto.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cocauto.helper.model.BotRunStatus
import com.cocauto.helper.model.BotStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Draggable circular floating icon overlay (chathead style) displayed over Clash of Clans.
 * - Starts compact as a single movable 52dp icon bubble so it never obstructs game buttons or OCR.
 * - Tapping the icon expands a sleek action panel (Run/Pause, Stop, Calibrate, Open App, Hide).
 * - Tapping the icon again or choosing "Chạy" / "Khung" collapses cleanly back into the icon.
 */
class FloatingHudManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onToggleRunPause: () -> Unit,
    private val onStopBot: () -> Unit,
    private val onToggleCalibration: () -> Unit,
    private val onOpenApp: () -> Unit,
) {
    private var rootFrame: FrameLayout? = null
    private var bubbleView: FloatingBubbleIconView? = null
    private var panelContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var uiScope: CoroutineScope? = null
    private var collectJob: Job? = null

    private var expanded = false
    private var collapsedX = 24
    private var collapsedY = 180

    private var statusBadge: TextView? = null
    private var actionLabel: TextView? = null
    private var statsLabel: TextView? = null
    private var runPauseBtn: TextView? = null

    val isShowing: Boolean get() = rootFrame != null

    @SuppressLint("ClickableViewAccessibility")
    fun show(startExpanded: Boolean = false) {
        if (rootFrame != null) {
            if (startExpanded != expanded) setExpanded(startExpanded)
            return
        }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                context.resources.displayMetrics,
            ).toInt()
        }

        collapsedX = dp(12)
        collapsedY = dp(84)

        val root = FrameLayout(context)

        // 1. Compact draggable circular floating icon (52dp x 52dp)
        val iconSize = dp(52)
        val bubble = FloatingBubbleIconView(context).apply {
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize)
        }
        bubbleView = bubble

        // 2. Expandable control panel (shown only when icon is tapped)
        val panel = buildActionPanel(dp).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        panelContainer = panel

        root.addView(bubble)
        root.addView(panel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = collapsedX
            y = collapsedY
        }

        // Unified drag + tap detector on the floating circular icon
        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var isDragging = false

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startParamX = params.x
                    startParamY = params.y
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val (screenW, screenH) = currentScreenSize()
                        params.x = (startParamX + dx.toInt()).coerceIn(0, (screenW - iconSize).coerceAtLeast(0))
                        params.y = (startParamY + dy.toInt()).coerceIn(0, (screenH - iconSize).coerceAtLeast(0))
                        collapsedX = params.x
                        collapsedY = params.y
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        setExpanded(true)
                    }
                    true
                }

                else -> false
            }
        }

        runCatching {
            windowManager.addView(root, params)
            rootFrame = root
            layoutParams = params
            BotStateStore.updateRuntime { it.copy(floatingHudVisible = true) }
            setExpanded(startExpanded)
            startObservingState()
        }
    }

    fun hide() {
        collectJob?.cancel()
        collectJob = null
        uiScope?.cancel()
        uiScope = null
        rootFrame?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootFrame = null
        bubbleView = null
        panelContainer = null
        layoutParams = null
        expanded = false
        BotStateStore.updateRuntime { it.copy(floatingHudVisible = false) }
    }

    fun collapse() {
        if (expanded) setExpanded(false)
    }

    fun setTemporarilyHidden(hidden: Boolean) {
        rootFrame?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
    }

    private fun setExpanded(expand: Boolean) {
        expanded = expand
        val root = rootFrame ?: return
        val params = layoutParams ?: return
        val bubble = bubbleView ?: return
        val panel = panelContainer ?: return

        if (expand) {
            bubble.visibility = View.GONE
            panel.visibility = View.VISIBLE
            // Ensure expanded panel stays inside screen bounds when opened near right/bottom edges
            val (screenW, screenH) = currentScreenSize()
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(screenW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.AT_MOST),
            )
            val panelW = panel.measuredWidth.coerceAtLeast(240)
            val panelH = panel.measuredHeight.coerceAtLeast(160)
            params.x = collapsedX.coerceIn(8, (screenW - panelW - 8).coerceAtLeast(8))
            params.y = collapsedY.coerceIn(8, (screenH - panelH - 8).coerceAtLeast(8))
        } else {
            panel.visibility = View.GONE
            bubble.visibility = View.VISIBLE
            val (screenW, screenH) = currentScreenSize()
            val iconSize = bubble.layoutParams.width.coerceAtLeast(100)
            params.x = collapsedX.coerceIn(0, (screenW - iconSize).coerceAtLeast(0))
            params.y = collapsedY.coerceIn(0, (screenH - iconSize).coerceAtLeast(0))
        }
        runCatching { windowManager.updateViewLayout(root, params) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildActionPanel(dp: (Int) -> Int): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F0161B22"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#F5C451"))
            }
        }

        // Draggable header bar with collapse button
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(context).apply {
            text = "● CoC Auto"
            setTextColor(Color.parseColor("#F5C451"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusBadge = badge

        val collapseIcon = TextView(context).apply {
            text = "Thu gọn ✕"
            setTextColor(Color.parseColor("#C9D1D9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(dp(8), dp(3), dp(6), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2A313C"))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { setExpanded(false) }
        }

        headerRow.addView(badge)
        headerRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(10), 1) })
        headerRow.addView(collapseIcon)
        container.addView(headerRow)

        // Allow dragging the expanded panel by its header too
        var hDownX = 0f
        var hDownY = 0f
        var hStartX = 0
        var hStartY = 0
        headerRow.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            val root = rootFrame ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hDownX = event.rawX
                    hDownY = event.rawY
                    hStartX = params.x
                    hStartY = params.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val (screenW, screenH) = currentScreenSize()
                    val panelW = container.width.coerceAtLeast(120)
                    val panelH = container.height.coerceAtLeast(100)
                    params.x = (hStartX + (event.rawX - hDownX).toInt()).coerceIn(0, (screenW - panelW).coerceAtLeast(0))
                    params.y = (hStartY + (event.rawY - hDownY).toInt()).coerceIn(0, (screenH - panelH).coerceAtLeast(0))
                    collapsedX = params.x
                    collapsedY = params.y
                    runCatching { windowManager.updateViewLayout(root, params) }
                    true
                }

                else -> false
            }
        }

        val actionText = TextView(context).apply {
            text = "Sẵn sàng"
            setTextColor(Color.parseColor("#E6EDF3"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(6), 0, dp(2))
            maxLines = 2
        }
        actionLabel = actionText
        container.addView(actionText)

        val statsText = TextView(context).apply {
            text = "Trận:0 BB:0 Next:0 Nâng:0/0T"
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(0, 0, 0, dp(8))
        }
        statsLabel = statsText
        container.addView(statsText)

        // Row 1: Primary controls (Run/Pause + Stop + Calibrate)
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnRun = makePillButton("▶ Chạy", "#2EA043", dp) {
            val currentlyRunning = BotStateStore.runtime.value.status == BotRunStatus.RUNNING
            onToggleRunPause()
            // Collapse automatically into the compact bubble when starting so it doesn't cover the game
            if (!currentlyRunning) {
                setExpanded(false)
            }
        }
        runPauseBtn = btnRun

        val btnStop = makePillButton("⏹ Dừng", "#DA3633", dp) {
            onStopBot()
        }

        val btnCalib = makePillButton("⊞ Khung", "#1F6FEB", dp) {
            setExpanded(false)
            onToggleCalibration()
        }

        row1.addView(btnRun)
        row1.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        row1.addView(btnStop)
        row1.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        row1.addView(btnCalib)
        container.addView(row1)

        // Row 2: Secondary controls (Open Settings App + Hide floating icon)
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val btnApp = makePillButton("⚙ Cài đặt", "#30363D", dp) {
            setExpanded(false)
            onOpenApp()
        }
        val btnHide = makePillButton("Ẩn icon", "#30363D", dp) {
            hide()
        }

        row2.addView(btnApp)
        row2.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        row2.addView(btnHide)
        container.addView(row2)

        return container
    }

    private fun startObservingState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        uiScope = scope
        collectJob = scope.launch {
            BotStateStore.runtime.collectLatest { snap ->
                val color = when (snap.status) {
                    BotRunStatus.RUNNING -> Color.parseColor("#3FB950")
                    BotRunStatus.PAUSED -> Color.parseColor("#F5C451")
                    BotRunStatus.CALIBRATING -> Color.parseColor("#58A6FF")
                    BotRunStatus.WAITING_GAME -> Color.parseColor("#E3B341")
                    BotRunStatus.STOPPED -> Color.parseColor("#8B949E")
                }
                bubbleView?.updateStatus(snap.status, color)
                statusBadge?.setTextColor(color)
                statusBadge?.text = "● ${snap.status.label}"
                actionLabel?.text = snap.currentAction
                val s = snap.stats
                statsLabel?.text = "Trận:${s.attacksCompleted} BB:${s.builderBaseAttacks} Next:${s.basesSkipped} Nâng:${s.buildingsUpgraded}/${s.wallsUpgraded}T"
                runPauseBtn?.text = when (snap.status) {
                    BotRunStatus.RUNNING, BotRunStatus.WAITING_GAME -> "⏸ Tạm dừng"
                    BotRunStatus.PAUSED -> "▶ Tiếp tục"
                    else -> "▶ Chạy"
                }
            }
        }
    }

    private fun currentScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun makePillButton(
        label: String,
        bgHex: String,
        dp: (Int) -> Int,
        onClick: () -> Unit,
    ): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            paint.isFakeBoldText = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(bgHex))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }
}

/**
 * Custom Canvas circular bubble icon with live status ring & play/pause/shield emblem.
 */
private class FloatingBubbleIconView(context: Context) : View(context) {
    private var status: BotRunStatus = BotRunStatus.STOPPED
    private var accentColor: Int = Color.parseColor("#F5C451")

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EB161B22")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5C451")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5C451")
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B949E")
        style = Paint.Style.FILL
    }
    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#161B22")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun updateStatus(newStatus: BotRunStatus, color: Int) {
        status = newStatus
        accentColor = color
        ringPaint.color = if (newStatus == BotRunStatus.STOPPED) Color.parseColor("#F5C451") else color
        dotPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val radius = (minOf(width, height) * 0.5f) - 6f

        // Base dark circle + outer status ring
        canvas.drawCircle(cx, cy, radius, bgPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Center emblem depending on status
        when (status) {
            BotRunStatus.RUNNING, BotRunStatus.WAITING_GAME -> {
                // Crisp double pause-bar / active gear feel -> show active green play arrow + "ON"
                glyphPaint.color = accentColor
                val barW = radius * 0.22f
                val barH = radius * 0.62f
                val gap = radius * 0.14f
                canvas.drawRoundRect(
                    cx - gap - barW,
                    cy - barH * 0.5f,
                    cx - gap,
                    cy + barH * 0.5f,
                    5f,
                    5f,
                    glyphPaint,
                )
                canvas.drawRoundRect(
                    cx + gap,
                    cy - barH * 0.5f,
                    cx + gap + barW,
                    cy + barH * 0.5f,
                    5f,
                    5f,
                    glyphPaint,
                )
            }

            BotRunStatus.PAUSED -> {
                // Gold Play triangle indicating ready to resume
                glyphPaint.color = accentColor
                val path = Path().apply {
                    moveTo(cx - radius * 0.22f, cy - radius * 0.34f)
                    lineTo(cx + radius * 0.38f, cy)
                    lineTo(cx - radius * 0.22f, cy + radius * 0.34f)
                    close()
                }
                canvas.drawPath(path, glyphPaint)
            }

            else -> {
                // Bold "CoC" emblem when idle/stopped
                labelPaint.textSize = radius * 0.62f
                labelPaint.color = Color.parseColor("#F5C451")
                val textY = cy - (labelPaint.descent() + labelPaint.ascent()) * 0.5f
                canvas.drawText("CoC", cx, textY, labelPaint)
            }
        }

        // Small live status indicator dot at bottom-right of the circular icon
        val dotR = radius * 0.22f
        val dotX = cx + radius * 0.68f
        val dotY = cy + radius * 0.68f
        canvas.drawCircle(dotX, dotY, dotR, dotPaint)
        canvas.drawCircle(dotX, dotY, dotR, dotBorderPaint)
    }
}
