package com.cocauto.helper.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import com.cocauto.helper.bridge.DeviceBridgeManager
import com.cocauto.helper.bridge.TouchStep
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.BotRunStatus
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.ControlBackendMode
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.service.CocAccessibilityService
import com.cocauto.helper.service.ScreenCaptureManager
import com.cocauto.helper.vision.OcrEngine
import com.cocauto.helper.vision.ScreenStateDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

data class NormTouchStep(
    val point: NormPoint,
    val holdMs: Long = 45L,
    val pauseAfterMs: Long = 30L,
)

/**
 * Shared execution context passed to all bot engines.
 * Dispatches screenshots, humanized taps/swipes, foreground-app checks, and launches through
 * the active ADB Bridge (Shizuku / Local ADB TCP / PC HTTP ADB) with ZERO AccessibilityService required,
 * or falls back cleanly to Legacy Accessibility/MediaProjection mode if selected.
 */
class BotExecutionContext(
    val appContext: Context,
    val bridgeManager: DeviceBridgeManager,
    val captureManager: ScreenCaptureManager,
    val ocrEngine: OcrEngine,
    val screenDetector: ScreenStateDetector,
    private val onSetHudHidden: (Boolean) -> Unit = {},
) {
    val config: BotConfig get() = BotStateStore.config.value
    val calibration: CalibrationProfile get() = BotStateStore.calibration.value
    val accessibility: CocAccessibilityService? get() = CocAccessibilityService.instance

    @Volatile
    private var lastFrameSize: Pair<Int, Int>? = null

    private fun isAdbBridgeMode(): Boolean = config.bridge.mode != ControlBackendMode.ACCESSIBILITY_LEGACY

    fun setHudTemporarilyHidden(hidden: Boolean) {
        onSetHudHidden(hidden)
        accessibility?.setHudTemporarilyHidden(hidden)
    }

    suspend fun ensureRunning() {
        currentCoroutineContext().ensureActive()
        while (BotStateStore.runtime.value.status == BotRunStatus.PAUSED ||
            BotStateStore.runtime.value.calibrationOverlayVisible
        ) {
            currentCoroutineContext().ensureActive()
            delay(250L)
        }
        val status = BotStateStore.runtime.value.status
        if (status == BotRunStatus.STOPPED) {
            throw CancellationException("Bot stopped by user")
        }
    }

    suspend fun sleep(ms: Long) {
        var remaining = ms.coerceAtLeast(0L)
        while (remaining > 0L) {
            ensureRunning()
            val chunk = minOf(remaining, 250L)
            delay(chunk)
            remaining -= chunk
        }
    }

    suspend fun humanDelay(multiplier: Float = 1f) {
        val safety = config.safety
        val minMs = (safety.actionDelayMinMs * multiplier).toLong().coerceAtLeast(60L)
        val maxMs = (safety.actionDelayMaxMs * multiplier).toLong().coerceAtLeast(minMs + 20L)
        sleep(Random.nextLong(minMs, maxMs + 1L))
    }

    suspend fun tap(point: NormPoint, delayAfter: Boolean = true, holdMsOverride: Long? = null): Boolean {
        ensureRunning()
        val jitterPx = config.safety.humanizeJitterPx
        val holdMs = holdMsOverride?.coerceIn(40L, 1_200L) ?: Random.nextLong(42L, 86L)

        val ok = if (isAdbBridgeMode()) {
            val bridge = bridgeManager.ensureConnected() ?: return false
            val (w, h) = bridge.lastCapturedPhysicalSize() ?: lastFrameSize ?: bridge.getScreenSize()
            val (rawX, rawY) = point.toPixel(w, h)
            val jx = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            val jy = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
            val x = (rawX + jx).toInt().coerceIn(4, (w - 4).coerceAtLeast(5))
            val y = (rawY + jy).toInt().coerceIn(4, (h - 4).coerceAtLeast(5))
            bridge.tap(x, y, holdMs)
        } else {
            val service = accessibility ?: return false
            service.tapNormalized(point, jitterPx = jitterPx, holdDurationMs = holdMs)
        }

        if (delayAfter) humanDelay()
        return ok
    }

    /**
     * Executes a batch of normalized taps/holds in a single native ADB injection session
     * (zero per-tap shell fork overhead -> 4x-5x faster troop deployment & resource harvesting!).
     */
    suspend fun executeNormalizedBatch(steps: List<NormTouchStep>): Boolean {
        if (steps.isEmpty()) return true
        ensureRunning()
        val jitterPx = config.safety.humanizeJitterPx

        if (isAdbBridgeMode()) {
            val bridge = bridgeManager.ensureConnected() ?: return false
            val (w, h) = bridge.lastCapturedPhysicalSize() ?: lastFrameSize ?: bridge.getScreenSize()
            val pixelSteps = steps.map { s ->
                val (rawX, rawY) = s.point.toPixel(w, h)
                val jx = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
                val jy = if (jitterPx > 0) Random.nextInt(-jitterPx, jitterPx + 1) else 0
                TouchStep(
                    xPx = (rawX + jx).toInt().coerceIn(4, (w - 4).coerceAtLeast(5)),
                    yPx = (rawY + jy).toInt().coerceIn(4, (h - 4).coerceAtLeast(5)),
                    holdMs = s.holdMs.coerceIn(25L, 1_200L),
                    pauseAfterMs = s.pauseAfterMs.coerceIn(0L, 1_500L),
                )
            }
            return bridge.executeTouchBatch(pixelSteps)
        }

        val service = accessibility ?: return false
        for (s in steps) {
            ensureRunning()
            service.tapNormalized(s.point, jitterPx = jitterPx, holdDurationMs = s.holdMs)
            if (s.pauseAfterMs > 0L) sleep(s.pauseAfterMs)
        }
        return true
    }

    suspend fun swipe(from: NormPoint, to: NormPoint, durationMs: Long = 320L): Boolean {
        ensureRunning()
        val ok = if (isAdbBridgeMode()) {
            val bridge = bridgeManager.ensureConnected() ?: return false
            val (w, h) = bridge.lastCapturedPhysicalSize() ?: lastFrameSize ?: bridge.getScreenSize()
            val (x1, y1) = from.toPixel(w, h)
            val (x2, y2) = to.toPixel(w, h)
            bridge.swipe(
                x1.toInt().coerceIn(4, (w - 4).coerceAtLeast(5)),
                y1.toInt().coerceIn(4, (h - 4).coerceAtLeast(5)),
                x2.toInt().coerceIn(4, (w - 4).coerceAtLeast(5)),
                y2.toInt().coerceIn(4, (h - 4).coerceAtLeast(5)),
                durationMs,
            )
        } else {
            val service = accessibility ?: return false
            service.swipeNormalized(from, to, durationMs)
        }
        humanDelay()
        return ok
    }

    suspend fun zoomOutCamera() {
        if (isAdbBridgeMode()) {
            val bridge = bridgeManager.ensureConnected() ?: return
            val (w, h) = bridge.lastCapturedPhysicalSize() ?: lastFrameSize ?: bridge.getScreenSize()
            ensureRunning()
            // Single native pinchZoomOut call already executes 2 smooth pinches inside 1 JVM (~350ms total!)
            bridge.pinchZoomOut(w, h)
            return
        }
        val service = accessibility ?: return
        repeat(2) {
            ensureRunning()
            service.pinchZoomOut(320L)
            sleep(160L)
        }
    }

    suspend fun readForegroundPackage(): String {
        if (isAdbBridgeMode()) {
            val pkg = bridgeManager.ensureConnected()?.getForegroundPackage().orEmpty()
            if (pkg.isNotBlank()) {
                BotStateStore.updateRuntime { it.copy(foregroundPackage = pkg) }
            }
            return pkg
        }
        return BotStateStore.runtime.value.foregroundPackage
    }

    /**
     * Captures a live screen frame via the active ADB Bridge (or MediaProjection/Accessibility in legacy mode)
     * and guarantees the Bitmap is recycled immediately after [block].
     */
    suspend fun <T> withFrame(block: suspend (Bitmap) -> T): T? {
        ensureRunning()
        val bitmap = captureFrameActiveSource() ?: return null
        val physSize = if (isAdbBridgeMode()) bridgeManager.activeAdbBridge()?.lastCapturedPhysicalSize() else null
        lastFrameSize = physSize ?: (bitmap.width to bitmap.height)
        return try {
            block(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun captureFrameActiveSource(): Bitmap? {
        if (isAdbBridgeMode()) {
            val bridge = bridgeManager.ensureConnected()
            bridge?.captureScreen()?.let { return it }
            // If capture failed (e.g. Wi-Fi -> 4G switched mid-run), re-run autoConnect once and retry
            bridgeManager.autoConnect(reason = "phục hồi chụp màn hình", forceReconnect = true, logOnSuccess = true)
            return bridgeManager.activeAdbBridge()?.captureScreen()
        }
        if (captureManager.isReady) {
            captureManager.captureFrame(maxRetries = 3)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            accessibility?.takeScreenshotBitmap()?.let { return it }
        }
        return null
    }

    suspend fun launchClashOfClans(): Boolean {
        if (isAdbBridgeMode()) {
            val launchedByAdb = bridgeManager.activeAdbBridge()?.launchPackage(ScreenStateDetector.COC_PACKAGE) == true
            if (launchedByAdb) {
                BotStateStore.log("Đang mở Clash of Clans qua ADB cầu nối...", LogLevel.INFO)
                return true
            }
        }
        return try {
            val intent = appContext.packageManager
                .getLaunchIntentForPackage(ScreenStateDetector.COC_PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (intent != null) {
                appContext.startActivity(intent)
                BotStateStore.log("Đang tự động mở Clash of Clans...", LogLevel.INFO)
                true
            } else {
                BotStateStore.log("Không tìm thấy game Clash of Clans trên máy.", LogLevel.ERROR)
                false
            }
        } catch (e: Exception) {
            BotStateStore.log("Lỗi mở Clash of Clans: ${e.message}", LogLevel.ERROR)
            false
        }
    }
}
