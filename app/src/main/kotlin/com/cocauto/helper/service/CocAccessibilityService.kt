package com.cocauto.helper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.cocauto.helper.MainActivity
import com.cocauto.helper.data.ConfigStorage
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.overlay.CalibrationOverlayManager
import com.cocauto.helper.overlay.FloatingHudManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * AccessibilityService providing:
 * 1. Humanized touch gestures (tap, swipe, 2-finger pinch zoom-out) over Clash of Clans
 * 2. Reliable Android 11+ native takeScreenshot() capture (immune to MediaProjection rotation bugs!)
 * 3. Foreground package monitoring (detecting crashes / app switches)
 * 4. Draggable Floating Icon Bubble & Live Calibration Overlay via TYPE_ACCESSIBILITY_OVERLAY
 */
class CocAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotMutex = Mutex()
    private var lastScreenshotAtMs = 0L

    private lateinit var windowManager: WindowManager
    private lateinit var configStorage: ConfigStorage
    private var hudManager: FloatingHudManager? = null
    private var calibrationOverlay: CalibrationOverlayManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configStorage = ConfigStorage(applicationContext)

        hudManager = FloatingHudManager(
            context = this,
            windowManager = windowManager,
            overlayWindowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            onToggleRunPause = {
                val canCaptureViaAccessibility = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                if (!BotStateStore.runtime.value.mediaProjectionReady && !canCaptureViaAccessibility) {
                    openAppForProjection(autoStart = true)
                } else if (!BotStateStore.runtime.value.mediaProjectionReady && canCaptureViaAccessibility) {
                    BotForegroundService.startWithAccessibilityCapture(this, autoStartBot = true)
                } else {
                    BotForegroundService.toggleRunOrPause(this)
                }
            },
            onStopBot = { BotForegroundService.stopBot(this) },
            onToggleCalibration = { toggleCalibrationOverlay() },
            onOpenApp = { openAppForProjection(autoStart = false) },
        )

        calibrationOverlay = CalibrationOverlayManager(
            context = this,
            windowManager = windowManager,
            overlayWindowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            onSaveCalibration = { updated ->
                BotStateStore.updateCalibration(updated)
                configStorage.saveCalibration(updated)
            },
            onRunAutoCalibrate = { profile, callback ->
                BotForegroundService.runAutoCalibration(this, profile, callback)
            },
            onRunLiveOcrTest = { profile, callback ->
                BotForegroundService.runLiveOcrTest(this, profile, callback)
            },
            onSetOtherOverlaysHidden = { hidden ->
                setHudTemporarilyHidden(hidden)
            },
        )

        BotStateStore.updateRuntime { it.copy(accessibilityConnected = true) }
        BotStateStore.log("Đã kết nối Dịch vụ Trợ năng (AccessibilityService).", LogLevel.SUCCESS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() && pkg != packageName) {
                BotStateStore.updateRuntime { it.copy(foregroundPackage = pkg) }
            }
        }
    }

    override fun onInterrupt() {
        BotStateStore.log("Dịch vụ Trợ năng bị gián đoạn.", LogLevel.WARN)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        mainHandler.post {
            calibrationOverlay?.hide()
            hudManager?.hide()
        }
        if (instance === this) {
            instance = null
        }
        BotStateStore.updateRuntime {
            it.copy(
                accessibilityConnected = false,
                floatingHudVisible = false,
                calibrationOverlayVisible = false,
            )
        }
    }

    fun showFloatingHud(startExpanded: Boolean = false) {
        mainHandler.post { hudManager?.show(startExpanded) }
    }

    fun hideFloatingHud() {
        mainHandler.post { hudManager?.hide() }
    }

    fun toggleFloatingHud() {
        mainHandler.post {
            val hud = hudManager ?: return@post
            if (hud.isShowing) {
                hud.hide()
            } else {
                hud.show(startExpanded = false)
            }
        }
    }

    fun showCalibrationOverlay(autoDetectImmediately: Boolean = false) {
        mainHandler.post {
            calibrationOverlay?.show(BotStateStore.calibration.value, autoDetectImmediately)
        }
    }

    fun hideCalibrationOverlay() {
        mainHandler.post { calibrationOverlay?.hide() }
    }

    fun setHudTemporarilyHidden(hidden: Boolean) {
        mainHandler.post { hudManager?.setTemporarilyHidden(hidden) }
    }

    fun toggleCalibrationOverlay() {
        mainHandler.post {
            val overlay = calibrationOverlay ?: return@post
            if (overlay.isShowing) {
                overlay.hide()
            } else {
                overlay.show(BotStateStore.calibration.value)
            }
        }
    }

    private fun openAppForProjection(autoStart: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (autoStart) {
                putExtra(MainActivity.EXTRA_REQUEST_PROJECTION_AND_START, true)
            }
        }
        runCatching { startActivity(intent) }
    }

    /**
     * Captures a full-screen software ARGB_8888 Bitmap using Android 11+ AccessibilityService.takeScreenshot().
     * Rate-limited to >= 420ms between calls to respect Android's internal screenshot throttle.
     */
    suspend fun takeScreenshotBitmap(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        return screenshotMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastScreenshotAtMs
            if (elapsed in 0L until 420L) {
                delay(420L - elapsed)
            }
            lastScreenshotAtMs = System.currentTimeMillis()

            suspendCancellableCoroutine { cont ->
                runCatching {
                    takeScreenshot(
                        Display.DEFAULT_DISPLAY,
                        mainExecutor,
                        object : TakeScreenshotCallback {
                            override fun onSuccess(screenshot: ScreenshotResult) {
                                val buffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val softwareCopy = try {
                                    val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                                    val copy = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                                    hwBitmap?.recycle()
                                    copy
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    buffer.close()
                                }
                                if (cont.isActive) cont.resume(softwareCopy)
                            }

                            override fun onFailure(errorCode: Int) {
                                if (cont.isActive) cont.resume(null)
                            }
                        },
                    )
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    suspend fun tapNormalized(
        point: NormPoint,
        jitterPx: Int = 8,
        holdDurationMs: Long = Random.nextLong(42L, 88L),
    ): Boolean {
        val (w, h) = getScreenSize()
        val (rawX, rawY) = point.toPixel(w, h)
        val jitterX = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
        val jitterY = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
        val x = (rawX + jitterX).coerceIn(4f, (w - 4).toFloat())
        val y = (rawY + jitterY).coerceIn(4f, (h - 4).toFloat())

        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, holdDurationMs.coerceIn(25L, 400L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    suspend fun swipeNormalized(
        from: NormPoint,
        to: NormPoint,
        durationMs: Long = 360L,
    ): Boolean {
        val (w, h) = getScreenSize()
        val (x1, y1) = from.toPixel(w, h)
        val (x2, y2) = to.toPixel(w, h)

        val path = Path().apply {
            moveTo(x1.coerceIn(4f, (w - 4).toFloat()), y1.coerceIn(4f, (h - 4).toFloat()))
            lineTo(x2.coerceIn(4f, (w - 4).toFloat()), y2.coerceIn(4f, (h - 4).toFloat()))
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(80L, 2_000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureAwait(gesture)
    }

    /**
     * Performs a smooth two-finger pinch-in gesture to zoom the Clash of Clans camera all the way out.
     */
    suspend fun pinchZoomOut(durationMs: Long = 420L): Boolean {
        val (w, h) = getScreenSize()
        val f1StartX = w * 0.24f
        val f1StartY = h * 0.28f
        val f1EndX = w * 0.44f
        val f1EndY = h * 0.46f

        val f2StartX = w * 0.76f
        val f2StartY = h * 0.72f
        val f2EndX = w * 0.56f
        val f2EndY = h * 0.54f

        val path1 = Path().apply {
            moveTo(f1StartX, f1StartY)
            lineTo(f1EndX, f1EndY)
        }
        val path2 = Path().apply {
            moveTo(f2StartX, f2StartY)
            lineTo(f2EndX, f2EndY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path1, 0L, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0L, durationMs))
            .build()
        return dispatchGestureAwait(gesture)
    }

    private suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                mainHandler,
            )
            if (!accepted && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    companion object {
        @Volatile
        var instance: CocAccessibilityService? = null
            private set
    }
}
