package com.cocauto.helper.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.LogLevel
import kotlinx.coroutines.delay

/**
 * Manages Android MediaProjection -> VirtualDisplay -> ImageReader capture pipeline.
 * Fixes Android 14+ rotation issues by resizing the existing VirtualDisplay and swapping
 * ImageReader surfaces (never re-calling createVirtualDisplay on a single-use token),
 * and caches the latest rendered frame via OnImageAvailableListener.
 */
class ScreenCaptureManager(
    private val context: Context,
) {
    private val projectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    private val captureLock = Any()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cachedFrame: Bitmap? = null

    @Volatile
    private var currentWidth: Int = 0

    @Volatile
    private var currentHeight: Int = 0

    @Volatile
    private var currentDpi: Int = DisplayMetrics.DENSITY_DEFAULT

    val isReady: Boolean
        get() = mediaProjection != null && virtualDisplay != null && imageReader != null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            BotStateStore.log("MediaProjection đã dừng bởi hệ thống.", LogLevel.WARN)
            stop()
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val (liveW, liveH, liveDpi) = readDisplayMetrics()
            synchronized(captureLock) {
                if (mediaProjection != null && virtualDisplay != null &&
                    (liveW != currentWidth || liveH != currentHeight)
                ) {
                    resizeVirtualDisplayLocked(liveW, liveH, liveDpi)
                }
            }
        }
    }

    fun start(resultCode: Int, data: Intent): Boolean {
        stop()
        return try {
            val thread = HandlerThread("CoCScreenCapture").apply { start() }
            val threadHandler = Handler(thread.looper)
            handlerThread = thread
            handler = threadHandler

            val projection = projectionManager.getMediaProjection(resultCode, data) ?: return false
            projection.registerCallback(projectionCallback, threadHandler)
            mediaProjection = projection

            val (w, h, dpi) = readDisplayMetrics()
            createVirtualDisplayOnceLocked(w, h, dpi)
            displayManager.registerDisplayListener(displayListener, threadHandler)

            BotStateStore.updateRuntime { it.copy(mediaProjectionReady = true) }
            BotStateStore.log("Đã kích hoạt MediaProjection (${currentWidth}x${currentHeight}).", LogLevel.SUCCESS)
            true
        } catch (e: Exception) {
            BotStateStore.log("Không thể khởi tạo MediaProjection: ${e.message}", LogLevel.ERROR)
            stop()
            false
        }
    }

    /**
     * Returns a safe Bitmap copy of the latest screen frame from MediaProjection.
     * Automatically resizes the existing VirtualDisplay if display dimensions changed.
     */
    suspend fun captureFrame(maxRetries: Int = 5): Bitmap? {
        if (!isReady) return null

        val (liveW, liveH, liveDpi) = readDisplayMetrics()
        if (liveW != currentWidth || liveH != currentHeight) {
            synchronized(captureLock) {
                if (mediaProjection != null && virtualDisplay != null &&
                    (liveW != currentWidth || liveH != currentHeight)
                ) {
                    resizeVirtualDisplayLocked(liveW, liveH, liveDpi)
                }
            }
            delay(220L)
        }

        repeat(maxRetries) {
            val copy = synchronized(captureLock) {
                // First drain any freshly queued buffer
                imageReader?.let { drainReaderToCacheLocked(it) }
                cachedFrame?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (copy != null) return copy
            delay(100L)
        }
        return null
    }

    fun stop() {
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        synchronized(captureLock) {
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null

            runCatching { imageReader?.setOnImageAvailableListener(null, null) }
            runCatching { imageReader?.close() }
            imageReader = null

            cachedFrame?.let { if (!it.isRecycled) it.recycle() }
            cachedFrame = null

            runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
            runCatching { mediaProjection?.stop() }
            mediaProjection = null

            runCatching { handlerThread?.quitSafely() }
            handlerThread = null
            handler = null
        }
        BotStateStore.updateRuntime { it.copy(mediaProjectionReady = false) }
    }

    private fun createVirtualDisplayOnceLocked(width: Int, height: Int, dpi: Int) {
        val safeW = width.coerceAtLeast(320)
        val safeH = height.coerceAtLeast(320)
        val projection = mediaProjection ?: return

        val reader = ImageReader.newInstance(safeW, safeH, PixelFormat.RGBA_8888, 2)
        attachListenerLocked(reader)

        val display = projection.createVirtualDisplay(
            "CoCAutoCapture",
            safeW,
            safeH,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )

        imageReader = reader
        virtualDisplay = display
        currentWidth = safeW
        currentHeight = safeH
        currentDpi = dpi
    }

    /**
     * Android 14+ safe reconfiguration: resizes existing VirtualDisplay and swaps its Surface
     * instead of releasing and calling createVirtualDisplay() a second time.
     */
    private fun resizeVirtualDisplayLocked(width: Int, height: Int, dpi: Int) {
        val safeW = width.coerceAtLeast(320)
        val safeH = height.coerceAtLeast(320)
        val vd = virtualDisplay ?: return

        val oldReader = imageReader
        val newReader = ImageReader.newInstance(safeW, safeH, PixelFormat.RGBA_8888, 2)
        attachListenerLocked(newReader)

        runCatching {
            vd.resize(safeW, safeH, dpi)
            vd.surface = newReader.surface
        }.onFailure { err ->
            BotStateStore.log("Lỗi resize VirtualDisplay: ${err.message}", LogLevel.WARN)
        }

        imageReader = newReader
        currentWidth = safeW
        currentHeight = safeH
        currentDpi = dpi

        cachedFrame?.let { if (!it.isRecycled) it.recycle() }
        cachedFrame = null

        runCatching { oldReader?.setOnImageAvailableListener(null, null) }
        runCatching { oldReader?.close() }
    }

    private fun attachListenerLocked(reader: ImageReader) {
        reader.setOnImageAvailableListener({ r ->
            synchronized(captureLock) {
                if (r === imageReader) {
                    drainReaderToCacheLocked(r)
                }
            }
        }, handler)
    }

    private fun drainReaderToCacheLocked(reader: ImageReader) {
        val image: Image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride

            val rawBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            rawBitmap.copyPixelsFromBuffer(buffer)
            val clean = if (paddedWidth == width) {
                rawBitmap
            } else {
                val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
                rawBitmap.recycle()
                cropped
            }
            cachedFrame?.let { if (!it.isRecycled) it.recycle() }
            cachedFrame = clean
        } catch (_: Exception) {
            // Ignore transient frame copy errors during display transitions
        } finally {
            image.close()
        }
    }

    private fun readDisplayMetrics(): Triple<Int, Int, Int> {
        val dpi = context.resources.displayMetrics.densityDpi.coerceAtLeast(160)
        // Prefer live AccessibilityService window bounds if connected (accurately tracks landscape game!)
        CocAccessibilityService.instance?.getScreenSize()?.let { (w, h) ->
            if (w >= 320 && h >= 320) {
                return Triple(w, h, dpi)
            }
        }
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            display.getRealMetrics(dm)
            if (dm.widthPixels >= 320 && dm.heightPixels >= 320) {
                return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi.coerceAtLeast(160))
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi.coerceAtLeast(160))
        }
    }
}
