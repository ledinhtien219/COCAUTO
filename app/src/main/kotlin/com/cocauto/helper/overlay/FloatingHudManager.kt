package com.cocauto.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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
 * Draggable compact on-screen ON/OFF floating toggle pill displayed over Clash of Clans.
 * - Compact mode: direct 1-tap [▶ BẬT] / [⏹ TẮT] button + [⚙] menu icon, freely draggable anywhere.
 * - Expanded mode: live status, battle counters, Pause/Resume, Stop, Calibration overlay, Open App, Hide.
 */
class FloatingHudManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayWindowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    private val onToggleRunPause: () -> Unit,
    private val onStopBot: () -> Unit,
    private val onToggleCalibration: () -> Unit,
    private val onOpenApp: () -> Unit,
) {
    private var rootFrame: FrameLayout? = null
    private var compactPill: LinearLayout? = null
    private var compactPowerBtn: TextView? = null
    private var compactMenuBtn: TextView? = null
    private var panelContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var uiScope: CoroutineScope? = null
    private var collectJob: Job? = null

    private var expanded = false
    private var collapsedX = 24
    private var collapsedY = 220

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

        collapsedX = dp(10)
        collapsedY = dp(120)

        val root = FrameLayout(context)

        // 1. Sleek draggable 1-tap [▶ BẬT / ⏹ TẮT] + [⚙] pill
        val pill = buildCompactPowerPill(dp)
        compactPill = pill

        // 2. Expandable detailed control panel (opened by tapping [⚙])
        val panel = buildActionPanel(dp).apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        panelContainer = panel

        root.addView(pill)
        root.addView(panel)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = collapsedX
            y = collapsedY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // Attach drag + 1-tap ON/OFF on the main power button
        compactPowerBtn?.let { powerBtn ->
            attachDragOrClick(
                targetView = powerBtn,
                root = root,
                params = params,
                touchSlop = touchSlop,
            ) {
                val status = BotStateStore.runtime.value.status
                if (status == BotRunStatus.RUNNING || status == BotRunStatus.WAITING_GAME) {
                    onStopBot()
                } else {
                    onToggleRunPause()
                }
            }
        }

        // Attach drag + expand menu on the [⚙] button
        compactMenuBtn?.let { menuBtn ->
            attachDragOrClick(
                targetView = menuBtn,
                root = root,
                params = params,
                touchSlop = touchSlop,
            ) {
                setExpanded(true)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragOrClick(
        targetView: View,
        root: FrameLayout,
        params: WindowManager.LayoutParams,
        touchSlop: Int,
        onTap: () -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        var startParamX = 0
        var startParamY = 0
        var isDragging = false

        targetView.setOnTouchListener { _, event ->
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
                        val viewW = (compactPill?.width ?: 180).coerceAtLeast(80)
                        val viewH = (compactPill?.height ?: 80).coerceAtLeast(60)
                        params.x = (startParamX + dx.toInt()).coerceIn(0, (screenW - viewW).coerceAtLeast(0))
                        params.y = (startParamY + dy.toInt()).coerceIn(0, (screenH - viewH).coerceAtLeast(0))
                        collapsedX = params.x
                        collapsedY = params.y
                        runCatching { windowManager.updateViewLayout(root, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onTap()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun buildCompactPowerPill(dp: (Int) -> Int): LinearLayout {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6161B22"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#F5C451"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val powerBtn = TextView(context).apply {
            text = "▶ BẬT"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2EA043"))
                cornerRadius = dp(18).toFloat()
            }
        }
        compactPowerBtn = powerBtn

        val menuBtn = TextView(context).apply {
            text = "⚙"
            setTextColor(Color.parseColor("#F5C451"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#26303B"))
                cornerRadius = dp(18).toFloat()
            }
        }
        compactMenuBtn = menuBtn

        wrapper.addView(powerBtn)
        wrapper.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(4), 1) })
        wrapper.addView(menuBtn)
        return wrapper
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
        compactPill = null
        compactPowerBtn = null
        compactMenuBtn = null
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
        val pill = compactPill ?: return
        val panel = panelContainer ?: return

        if (expand) {
            pill.visibility = View.GONE
            panel.visibility = View.VISIBLE
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
            pill.visibility = View.VISIBLE
            val (screenW, screenH) = currentScreenSize()
            val pillW = pill.width.coerceAtLeast(180)
            val pillH = pill.height.coerceAtLeast(80)
            params.x = collapsedX.coerceIn(0, (screenW - pillW).coerceAtLeast(0))
            params.y = collapsedY.coerceIn(0, (screenH - pillH).coerceAtLeast(0))
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

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnRun = makePillButton("▶ Bật", "#2EA043", dp) {
            val currentlyRunning = BotStateStore.runtime.value.status == BotRunStatus.RUNNING
            onToggleRunPause()
            if (!currentlyRunning) {
                setExpanded(false)
            }
        }
        runPauseBtn = btnRun

        val btnStop = makePillButton("⏹ Tắt", "#DA3633", dp) {
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

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val btnApp = makePillButton("⚙ Cài đặt", "#30363D", dp) {
            setExpanded(false)
            onOpenApp()
        }
        val btnHide = makePillButton("Ẩn nút nổi", "#30363D", dp) {
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
                val isRunning = snap.status == BotRunStatus.RUNNING || snap.status == BotRunStatus.WAITING_GAME
                val color = when (snap.status) {
                    BotRunStatus.RUNNING -> Color.parseColor("#3FB950")
                    BotRunStatus.PAUSED -> Color.parseColor("#F5C451")
                    BotRunStatus.CALIBRATING -> Color.parseColor("#58A6FF")
                    BotRunStatus.WAITING_GAME -> Color.parseColor("#E3B341")
                    BotRunStatus.STOPPED -> Color.parseColor("#8B949E")
                }

                // Update 1-tap compact ON/OFF button dynamically
                compactPowerBtn?.let { btn ->
                    btn.text = if (isRunning) "⏹ TẮT" else "▶ BẬT"
                    (btn.background as? GradientDrawable)?.setColor(
                        Color.parseColor(if (isRunning) "#DA3633" else "#2EA043"),
                    )
                }
                (compactPill?.background as? GradientDrawable)?.setStroke(
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.5f, context.resources.displayMetrics).toInt(),
                    if (isRunning) Color.parseColor("#3FB950") else Color.parseColor("#F5C451"),
                )

                statusBadge?.setTextColor(color)
                statusBadge?.text = "● ${snap.status.label}"
                actionLabel?.text = snap.currentAction
                val s = snap.stats
                statsLabel?.text = "Trận:${s.attacksCompleted} BB:${s.builderBaseAttacks} Next:${s.basesSkipped} Nâng:${s.buildingsUpgraded}/${s.wallsUpgraded}T"
                runPauseBtn?.text = when (snap.status) {
                    BotRunStatus.RUNNING, BotRunStatus.WAITING_GAME -> "⏸ Tạm dừng"
                    BotRunStatus.PAUSED -> "▶ Tiếp tục"
                    else -> "▶ Bật"
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
