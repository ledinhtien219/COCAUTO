package com.cocauto.helper.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.BotRunStatus
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.CalibrationProfile
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
 * Guarantees safe Bitmap recycling, pause/stop checks, humanized touch timing,
 * and dual-source frame capture (MediaProjection + Android 11+ AccessibilityService fallback).
 */
class BotExecutionContext(
    val appContext: Context,
    val captureManager: ScreenCaptureManager,
    val ocrEngine: OcrEngine,
    val screenDetector: ScreenStateDetector,
) {
    val config: BotConfig get() = BotStateStore.config.value
    val calibration: CalibrationProfile get() = BotStateStore.calibration.value
    val accessibility: CocAccessibilityService? get() = CocAccessibilityService.instance

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
        val service = accessibility ?: return false
        val ok = service.tapNormalized(point, jitterPx = config.safety.humanizeJitterPx)
        if (delayAfter) humanDelay()
        return ok
    }

    suspend fun swipe(from: NormPoint, to: NormPoint, durationMs: Long = 380L): Boolean {
        ensureRunning()
        val service = accessibility ?: return false
        val ok = service.swipeNormalized(from, to, durationMs)
        humanDelay()
        return ok
    }

    suspend fun zoomOutCamera() {
        val service = accessibility ?: return
        repeat(2) {
            ensureRunning()
            service.pinchZoomOut(380L)
            sleep(240L)
        }
    }

    /**
     * Captures a live screen frame via MediaProjection (if ready) or Android 11+ AccessibilityService
     * native screenshot fallback, and guarantees the Bitmap is recycled immediately after [block].
     */
    suspend fun <T> withFrame(block: suspend (Bitmap) -> T): T? {
        ensureRunning()
        val bitmap = captureFrameDualSource() ?: return null
        return try {
            block(bitmap)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    private suspend fun captureFrameDualSource(): Bitmap? {
        // 1. Try MediaProjection if active
        if (captureManager.isReady) {
            captureManager.captureFrame(maxRetries = 3)?.let { return it }
        }
        // 2. Reliable Android 11+ AccessibilityService.takeScreenshot() fallback (immune to rotation bugs)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            accessibility?.takeScreenshotBitmap()?.let { return it }
        }
        return null
    }

    fun launchClashOfClans(): Boolean {
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
