package com.cocauto.helper.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedScreenFrame(
    val bitmap: Bitmap,
    val physicalWidth: Int,
    val physicalHeight: Int,
)

/**
 * Decodes Android raw `screencap` (uncompressed RGBA_8888, ~150ms on Android 16 vs ~735ms for PNG)
 * with automatic 2x downsampling when resolution > 1600px (4x faster OCR & pixel scanning!)
 * while preserving exact physical display dimensions for accurate touch injection.
 */
object RawScreencapDecoder {
    private const val PIXEL_FORMAT_RGBA_8888 = 1

    fun decodeRawRgba(raw: ByteArray): DecodedScreenFrame? {
        if (raw.size < 64) return null
        val header = ByteBuffer.wrap(raw, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        val width = header.int
        val height = header.int
        val format = header.int

        if (format != PIXEL_FORMAT_RGBA_8888 || width !in 200..6000 || height !in 200..6000) {
            return null
        }

        val pixelBytesLong = width.toLong() * height.toLong() * 4L
        if (pixelBytesLong > Int.MAX_VALUE) return null
        val headerSize = raw.size - pixelBytesLong.toInt()
        // Android 8–11 uses 12-byte header (w, h, fmt); Android 12–17 uses 16-byte header (+ colorSpace)
        if (headerSize != 12 && headerSize != 16) return null

        val step = if (maxOf(width, height) > 1600) 2 else 1
        val dstW = (width / step).coerceAtLeast(1)
        val dstH = (height / step).coerceAtLeast(1)
        val pixels = IntArray(dstW * dstH)

        val rowStride = width * 4
        val xInc = step * 4
        var dstIdx = 0
        for (dy in 0 until dstH) {
            var srcOff = headerSize + (dy * step) * rowStride
            for (dx in 0 until dstW) {
                val r = raw[srcOff].toInt() and 0xFF
                val g = raw[srcOff + 1].toInt() and 0xFF
                val b = raw[srcOff + 2].toInt() and 0xFF
                pixels[dstIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                srcOff += xInc
            }
        }

        val bitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, dstW, 0, 0, dstW, dstH)
        return DecodedScreenFrame(bitmap, width, height)
    }

    fun decodePngDownsampled(pngBytes: ByteArray): DecodedScreenFrame? {
        if (pngBytes.size < 100) return null
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, boundsOpts)
        val physW = boundsOpts.outWidth
        val physH = boundsOpts.outHeight
        if (physW < 100 || physH < 100) return null

        val sampleSize = if (maxOf(physW, physH) > 1600) 2 else 1
        val decodeOpts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val bmp = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, decodeOpts) ?: return null
        return DecodedScreenFrame(bmp, physW, physH)
    }
}
