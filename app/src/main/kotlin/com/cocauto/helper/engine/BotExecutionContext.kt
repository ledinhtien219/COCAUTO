package com.cocauto.helper.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import com.cocauto.helper.bridge.DeviceBridgeManager
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

    suspend fun tap(point: NormPoint, delayAfter: Boolean = true): Boolean {
        ensureRunning()
        val jitterPx = config.safety.humanizeJitterPx
        val holdMs = Random.nextLong(42L, 86L)

        val ok = if (isAdbBridgeMode()) {
            val bridge = bridgeManager.activeAdbBridge() ?: return false
            val (w, h) = lastFrameSize ?: bridge.getScreenSize()
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

    suspend fun swipe(from: NormPoint, to: NormPoint, durationMs: Long = 380L): Boolean {
        ensureRunning()
        val ok = if (isAdbBridgeMode()) {
            val bridge = bridgeManager.activeAdbBridge() ?: return false
            val (w, h) = lastFrameSize ?: bridge.getScreenSize()
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
            val bridge = bridgeManager.activeAdbBridge() ?: return
            val (w, h) = lastFrameSize ?: bridge.getScreenSize()
            repeat(2) {
                ensureRunning()
                bridge.pinchZoomOut(w, h)
                sleep(200L)
            }
            return
        }
        val service = accessibility ?: return
        repeat(2) {
            ensureRunning()
            service.pinchZoomOut(380L)
            sleep(240L)
        }
    }

    suspend fun readForegroundPackage(): String {
        if (isAdbBridgeMode()) {
            val pkg = bridgeManager.activeAdbBridge()?.getForegroundPackage().orEmpty()
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
        lastFrameSize = bitmap.width to bitmap.height
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
