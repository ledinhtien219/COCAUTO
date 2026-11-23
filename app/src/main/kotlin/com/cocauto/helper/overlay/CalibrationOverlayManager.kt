package com.cocauto.helper.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.cocauto.helper.model.AspectPreset
import com.cocauto.helper.model.BotRunStatus
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import com.cocauto.helper.vision.AutoCalibrationResult
import kotlin.math.hypot

/**
 * Interactive full-screen Calibration Overlay rendered on top of Clash of Clans.
 * Features 1-tap "Tự nhận diện" (Auto-Detect) that temporarily hides overlays,
 * captures a clean game frame, detects all Clash of Clans buttons/resource bars via OCR,
 * and snaps every ROI box and anchor point automatically. Manual dragging remains optional.
 */
class CalibrationOverlayManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onSaveCalibration: (CalibrationProfile) -> Unit,
    private val onRunAutoCalibrate: (CalibrationProfile, onResult: (AutoCalibrationResult?) -> Unit) -> Unit,
    private val onRunLiveOcrTest: (CalibrationProfile, onResult: (String) -> Unit) -> Unit,
    private val onSetOtherOverlaysHidden: (Boolean) -> Unit = {},
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var rootContainer: FrameLayout? = null
    private var canvasView: CalibrationCanvasView? = null
    private var testBanner: TextView? = null

    val isShowing: Boolean get() = rootContainer != null

    fun show(initialProfile: CalibrationProfile, autoDetectImmediately: Boolean = false) {
        if (rootContainer != null) {
            if (autoDetectImmediately) triggerAutoDetect()
            return
        }

        val dp = { value: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value.toFloat(),
                context.resources.displayMetrics,
            ).toInt()
        }

        val frame = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#44000000"))
        }

        val editorView = CalibrationCanvasView(context, initialProfile)
        canvasView = editorView
        frame.addView(
            editorView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Top floating control bar
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE161B22"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#F5C451"))
            }
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(context).apply {
            text = "Hiệu chỉnh  "
            setTextColor(Color.parseColor("#F5C451"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            paint.isFakeBoldText = true
        }
        btnRow.addView(title)

        btnRow.addView(makeBarBtn("✨ Tự nhận diện", "#0EA5E9", dp) {
            triggerAutoDetect()
        })
        btnRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        btnRow.addView(makeBarBtn("Lưu", "#2EA043", dp) {
            val updated = editorView.profile
            onSaveCalibration(updated)
            BotStateStore.log("Đã lưu hồ sơ hiệu chỉnh tọa độ mới.", LogLevel.SUCCESS)
            hide()
        })
        btnRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        btnRow.addView(makeBarBtn("Thử OCR", "#1F6FEB", dp) {
            triggerCleanLiveOcrTest()
        })
        btnRow.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(dp(6), 1) })
        btnRow.addView(makeBarBtn("Đóng", "#DA3633", dp) { hide() })

        topBar.addView(btnRow)

        val resultLabel = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#A5F3FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, dp(4), 0, 0)
        }
        testBanner = resultLabel
        topBar.addView(resultLabel)

        val barParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = dp(8)
        }
        frame.addView(topBar, barParams)

        val windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Render edge-to-edge across display cutout / punch-hole so overlay coordinates match
            // physical screen capture & AccessibilityService.dispatchGesture() 1:1!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        runCatching {
            windowManager.addView(frame, windowParams)
            rootContainer = frame
            BotStateStore.updateRuntime {
                it.copy(
                    calibrationOverlayVisible = true,
                    status = if (it.status == BotRunStatus.STOPPED) BotRunStatus.CALIBRATING else it.status,
                )
            }
            if (autoDetectImmediately) {
                mainHandler.postDelayed({ triggerAutoDetect() }, 900L)
            }
        }
    }

    /**
     * Temporarily hides all overlays so the screenshot captures 100% pure Clash of Clans UI,
     * then runs AutoCalibrationEngine and snaps all boxes + saves automatically.
     */
    private fun triggerAutoDetect() {
        val editor = canvasView ?: return
        val container = rootContainer ?: return
        val currentProfile = editor.profile

        testBanner?.visibility = View.VISIBLE
        testBanner?.text = "Đang tự động quét & nhận diện màn hình game..."

        container.visibility = View.INVISIBLE
        onSetOtherOverlaysHidden(true)

        mainHandler.postDelayed({
            onRunAutoCalibrate(currentProfile) { result ->
                mainHandler.post {
                    onSetOtherOverlaysHidden(false)
                    container.visibility = View.VISIBLE
                    if (result == null) {
                        testBanner?.visibility = View.VISIBLE
                        testBanner?.text = "Không chụp được ảnh màn hình. Hãy tắt rồi bật lại Dịch vụ Trợ năng CoC Auto!"
                    } else {
                        if (result.hasDetections) {
                            editor.profile = result.profile
                            editor.invalidate()
                            onSaveCalibration(result.profile)
                        }
                        testBanner?.visibility = View.VISIBLE
                        testBanner?.text = result.summaryText()
                    }
                }
            }
        }, 160L)
    }

    private fun triggerCleanLiveOcrTest() {
        val editor = canvasView ?: return
        val container = rootContainer ?: return
        val currentProfile = editor.profile

        container.visibility = View.INVISIBLE
        onSetOtherOverlaysHidden(true)

        mainHandler.postDelayed({
            onRunLiveOcrTest(currentProfile) { summary ->
                mainHandler.post {
                    onSetOtherOverlaysHidden(false)
                    container.visibility = View.VISIBLE
                    testBanner?.visibility = View.VISIBLE
                    testBanner?.text = summary
                }
            }
        }, 160L)
    }

    fun hide() {
        rootContainer?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        rootContainer = null
        canvasView = null
        testBanner = null
        BotStateStore.updateRuntime {
            it.copy(
                calibrationOverlayVisible = false,
                status = if (it.status == BotRunStatus.CALIBRATING) BotRunStatus.STOPPED else it.status,
            )
        }
    }

    private fun makeBarBtn(label: String, colorHex: String, dp: (Int) -> Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            paint.isFakeBoldText = true
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(colorHex))
                cornerRadius = dp(7).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }
}

private enum class DragKind {
    RECT_MOVE,
    RECT_RESIZE,
    POINT_MOVE,
}

private data class RectItem(
    val id: String,
    val label: String,
    val color: Int,
    val getter: (CalibrationProfile) -> NormRect,
    val setter: (CalibrationProfile, NormRect) -> CalibrationProfile,
)

private data class PointItem(
    val id: String,
    val label: String,
    val color: Int,
    val getter: (CalibrationProfile) -> NormPoint,
    val setter: (CalibrationProfile, NormPoint) -> CalibrationProfile,
)

private class CalibrationCanvasView(
    context: Context,
    var profile: CalibrationProfile,
) : View(context) {

    private val rectItems = listOf(
        RectItem("enemyLoot", "Loot địch", Color.parseColor("#F59E0B"), { it.enemyLootRoi }, { p, r -> p.copy(enemyLootRoi = r) }),
        RectItem("homeGold", "Vàng nhà", Color.parseColor("#EAB308"), { it.homeGoldRoi }, { p, r -> p.copy(homeGoldRoi = r) }),
        RectItem("homeElixir", "Dầu nhà", Color.parseColor("#EC4899"), { it.homeElixirRoi }, { p, r -> p.copy(homeElixirRoi = r) }),
        RectItem("homeDark", "Dầu đen", Color.parseColor("#A855F7"), { it.homeDarkElixirRoi }, { p, r -> p.copy(homeDarkElixirRoi = r) }),
        RectItem("builderBadge", "Thợ xây", Color.parseColor("#22C55E"), { it.builderBadgeRoi }, { p, r -> p.copy(builderBadgeRoi = r, builderBadgeButton = r.centerPoint()) }),
        RectItem("builderList", "Menu nâng cấp", Color.parseColor("#38BDF8"), { it.builderSuggestionsRoi }, { p, r -> p.copy(builderSuggestionsRoi = r) }),
        RectItem("troopBar", "Thanh lính", Color.parseColor("#FB7185"), { it.troopBarRoi }, { p, r -> p.copy(troopBarRoi = r) }),
    )

    private val pointItems = listOf(
        PointItem("attack", "Đánh", Color.parseColor("#EF4444"), { it.homeAttackButton }, { p, pt -> p.copy(homeAttackButton = pt, bbAttackButton = pt) }),
        PointItem("findMatch", "Tìm trận", Color.parseColor("#F97316"), { it.findMatchButton }, { p, pt -> p.copy(findMatchButton = pt) }),
        PointItem("nextBase", "Tiếp", Color.parseColor("#FACC15"), { it.nextBaseButton }, { p, pt -> p.copy(nextBaseButton = pt) }),
        PointItem("endBattle", "Kết thúc", Color.parseColor("#F87171"), { it.endBattleButton }, { p, pt -> p.copy(endBattleButton = pt) }),
        PointItem("returnHome", "Về nhà", Color.parseColor("#34D399"), { it.returnHomeButton }, { p, pt -> p.copy(returnHomeButton = pt) }),
        PointItem("boat", "Thuyền BB", Color.parseColor("#60A5FA"), { it.boatToBuilderBase }, { p, pt -> p.copy(boatToBuilderBase = pt) }),
        PointItem("deployT", "Đỉnh Bắc", Color.parseColor("#4ADE80"), { it.deployTop }, { p, pt -> p.copy(deployTop = pt) }),
        PointItem("deployR", "Đỉnh Đông", Color.parseColor("#4ADE80"), { it.deployRight }, { p, pt -> p.copy(deployRight = pt) }),
        PointItem("deployB", "Đỉnh Nam", Color.parseColor("#4ADE80"), { it.deployBottom }, { p, pt -> p.copy(deployBottom = pt) }),
        PointItem("deployL", "Đỉnh Tây", Color.parseColor("#4ADE80"), { it.deployLeft }, { p, pt -> p.copy(deployLeft = pt) }),
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val diamondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#AA4ADE80")
        pathEffect = DashPathEffect(floatArrayOf(16f, 10f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        isFakeBoldText = true
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private var activeDragKind: DragKind? = null
    private var activeRectItem: RectItem? = null
    private var activePointItem: PointItem? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        // Draw dashed deployment diamond connecting the 4 perimeter corners
        val top = profile.deployTop.toPixel(width, height)
        val right = profile.deployRight.toPixel(width, height)
        val bottom = profile.deployBottom.toPixel(width, height)
        val left = profile.deployLeft.toPixel(width, height)
        canvas.drawLine(left.first, left.second, top.first, top.second, diamondPaint)
        canvas.drawLine(top.first, top.second, right.first, right.second, diamondPaint)
        canvas.drawLine(right.first, right.second, bottom.first, bottom.second, diamondPaint)
        canvas.drawLine(bottom.first, bottom.second, left.first, left.second, diamondPaint)

        // Draw editable ROI boxes
        for (item in rectItems) {
            val r = item.getter(profile).normalized()
            val rectF = RectF(r.left * w, r.top * h, r.right * w, r.bottom * h)
            fillPaint.color = (item.color and 0x00FFFFFF) or 0x33000000
            strokePaint.color = item.color
            canvas.drawRect(rectF, fillPaint)
            canvas.drawRect(rectF, strokePaint)

            // Resize handle at bottom-right
            fillPaint.color = item.color
            canvas.drawCircle(rectF.right, rectF.bottom, 14f, fillPaint)
            canvas.drawText(item.label, rectF.left + 6f, (rectF.top - 8f).coerceAtLeast(28f), textPaint)
        }

        // Draw draggable anchor points
        for (item in pointItems) {
            val (px, py) = item.getter(profile).toPixel(width, height)
            fillPaint.color = (item.color and 0x00FFFFFF) or 0xAA000000.toInt()
            strokePaint.color = Color.WHITE
            canvas.drawCircle(px, py, 20f, fillPaint)
            canvas.drawCircle(px, py, 20f, strokePaint)
            canvas.drawText(item.label, px - 36f, py - 26f, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = x
                lastTouchY = y
                activeDragKind = null
                activeRectItem = null
                activePointItem = null

                // 1. Check points first (highest priority)
                for (item in pointItems) {
                    val (px, py) = item.getter(profile).toPixel(width, height)
                    if (hypot((x - px).toDouble(), (y - py).toDouble()) <= 44.0) {
                        activePointItem = item
                        activeDragKind = DragKind.POINT_MOVE
                        return true
                    }
                }

                // 2. Check ROI resize handles
                for (item in rectItems) {
                    val r = item.getter(profile).normalized()
                    val rx = r.right * w
                    val ry = r.bottom * h
                    if (hypot((x - rx).toDouble(), (y - ry).toDouble()) <= 42.0) {
                        activeRectItem = item
                        activeDragKind = DragKind.RECT_RESIZE
                        return true
                    }
                }

                // 3. Check ROI box body drag
                for (item in rectItems) {
                    val r = item.getter(profile).normalized()
                    if (x in (r.left * w)..(r.right * w) && y in (r.top * h)..(r.bottom * h)) {
                        activeRectItem = item
                        activeDragKind = DragKind.RECT_MOVE
                        return true
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dxNorm = (x - lastTouchX) / w
                val dyNorm = (y - lastTouchY) / h
                lastTouchX = x
                lastTouchY = y

                when (activeDragKind) {
                    DragKind.POINT_MOVE -> {
                        val item = activePointItem ?: return true
                        val cur = item.getter(profile)
                        val updated = NormPoint(
                            x = (cur.x + dxNorm).coerceIn(0.01f, 0.99f),
                            y = (cur.y + dyNorm).coerceIn(0.01f, 0.99f),
                        )
                        profile = item.setter(profile, updated)
                        invalidate()
                    }

                    DragKind.RECT_MOVE -> {
                        val item = activeRectItem ?: return true
                        val cur = item.getter(profile).normalized()
                        val rw = cur.width
                        val rh = cur.height
                        val newLeft = (cur.left + dxNorm).coerceIn(0.005f, 0.995f - rw)
                        val newTop = (cur.top + dyNorm).coerceIn(0.005f, 0.995f - rh)
                        profile = item.setter(profile, NormRect(newLeft, newTop, newLeft + rw, newTop + rh))
                        invalidate()
                    }

                    DragKind.RECT_RESIZE -> {
                        val item = activeRectItem ?: return true
                        val cur = item.getter(profile).normalized()
                        val newRight = (cur.right + dxNorm).coerceIn(cur.left + 0.03f, 0.995f)
                        val newBottom = (cur.bottom + dyNorm).coerceIn(cur.top + 0.03f, 0.995f)
                        profile = item.setter(profile, NormRect(cur.left, cur.top, newRight, newBottom))
                        invalidate()
                    }

                    null -> Unit
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeDragKind = null
                activeRectItem = null
                activePointItem = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
