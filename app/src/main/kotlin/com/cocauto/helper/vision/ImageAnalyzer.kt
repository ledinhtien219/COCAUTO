package com.cocauto.helper.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.cocauto.helper.model.CurrencyType
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Lightweight pure-Kotlin computer vision helpers operating on Android Bitmaps:
 * - Safe normalized ROI cropping & OCR contrast enhancement
 * - HSV classification for clouds, loading screens, troop availability, and currency icons
 * - Collector bubble hotspot scanning across the Home/Builder village
 * - Downscaled Normalized Cross-Correlation (NCC) template matching
 */
object ImageAnalyzer {

    fun cropNormalized(source: Bitmap, rect: NormRect): Bitmap? {
        if (source.isRecycled || source.width < 10 || source.height < 10) return null
        val norm = rect.normalized()
        val x = (norm.left * source.width).toInt().coerceIn(0, source.width - 2)
        val y = (norm.top * source.height).toInt().coerceIn(0, source.height - 2)
        val w = ((norm.right - norm.left) * source.width).toInt().coerceIn(2, source.width - x)
        val h = ((norm.bottom - norm.top) * source.height).toInt().coerceIn(2, source.height - y)
        return runCatching { Bitmap.createBitmap(source, x, y, w, h) }.getOrNull()
    }

    /**
     * Upscales small UI crops 2x and increases contrast slightly to make Clash of Clans'
     * white-with-black-outline font much sharper for ML Kit OCR.
     */
    fun enhanceForOcr(crop: Bitmap): Bitmap {
        val scale = if (crop.width < 420) 2 else 1
        val outW = (crop.width * scale).coerceAtLeast(32)
        val outH = (crop.height * scale).coerceAtLeast(32)
        val scaled = if (scale > 1) {
            Bitmap.createScaledBitmap(crop, outW, outH, true)
        } else {
            crop.copy(Bitmap.Config.ARGB_8888, false)
        }

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val contrast = 1.28f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val matrix = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled !== crop) {
            scaled.recycle()
        }
        return result
    }

    /**
     * Detects full-screen bright white matchmaking clouds (checking both center and outer edges
     * with strict brightness V >= 0.90 so centered cream/white dialog popups like "Welcome Back"
     * surrounded by dimmed village grass are NEVER misclassified as clouds!).
     */
    fun isCloudSearchScreen(frame: Bitmap): Boolean {
        val fullStats = sampleRegionHsv(frame, NormRect(0.05f, 0.08f, 0.95f, 0.92f), gridCols = 14, gridRows = 10)
        if (fullStats.whiteCloudRatio < 0.72f) return false
        val leftEdge = sampleRegionHsv(frame, NormRect(0.03f, 0.15f, 0.15f, 0.85f), gridCols = 4, gridRows = 8)
        val rightEdge = sampleRegionHsv(frame, NormRect(0.85f, 0.15f, 0.97f, 0.85f), gridCols = 4, gridRows = 8)
        return leftEdge.whiteCloudRatio >= 0.65f && rightEdge.whiteCloudRatio >= 0.65f
    }

    /**
     * Detects dark/black screen during app launch or scene transition.
     */
    fun isDarkLoadingScreen(frame: Bitmap): Boolean {
        val stats = sampleRegionHsv(frame, NormRect(0.15f, 0.15f, 0.85f, 0.85f), gridCols = 12, gridRows = 8)
        return stats.darkRatio >= 0.85f
    }

    /**
     * Checks whether a troop slot still has color saturation (available) vs turned grayscale (empty).
     */
    fun isTroopSlotAvailable(frame: Bitmap, slotRect: NormRect): Boolean {
        val stats = sampleRegionHsv(frame, slotRect, gridCols = 8, gridRows = 8)
        // Available slots contain saturated character artwork or colorful backgrounds
        return stats.colorfulRatio >= 0.20f && stats.avgSaturation >= 0.19f
    }

    /**
     * Estimates how full (0..100%) a Home Village resource bar (Gold, Elixir, Dark Elixir) is
     * by sampling colored bar pixels along the upper/lower strips of [barRoi] (dodging centered digits
     * and the rightmost resource coin/drop icon). Works at any Town Hall level without manual capacity input.
     */
    fun measureStorageBarFillPercent(
        frame: Bitmap,
        barRoi: NormRect,
        currency: CurrencyType,
    ): Int {
        if (frame.isRecycled || frame.width < 100 || frame.height < 100) return 0
        val norm = barRoi.normalized()
        // Sample the bar interior excluding the rightmost ~16% icon area
        val leftPx = (norm.left * frame.width).toInt().coerceIn(0, frame.width - 2)
        val rightPx = ((norm.left + norm.width * 0.84f) * frame.width).toInt().coerceIn(leftPx + 4, frame.width - 1)
        val topPx = (norm.top * frame.height).toInt().coerceIn(0, frame.height - 2)
        val bottomPx = (norm.bottom * frame.height).toInt().coerceIn(topPx + 4, frame.height - 1)
        val barH = bottomPx - topPx

        // Sample upper & lower strips to avoid white outline text numbers in the vertical center
        val sampleRows = listOf(
            (topPx + barH * 0.20f).toInt().coerceIn(topPx, bottomPx),
            (topPx + barH * 0.28f).toInt().coerceIn(topPx, bottomPx),
            (topPx + barH * 0.76f).toInt().coerceIn(topPx, bottomPx),
            (topPx + barH * 0.84f).toInt().coerceIn(topPx, bottomPx),
        )

        val cols = 28
        val hsv = FloatArray(3)
        var filledCols = 0

        for (c in 0 until cols) {
            val x = (leftPx + ((rightPx - leftPx) * (c + 0.5f) / cols)).toInt().coerceIn(0, frame.width - 1)
            var matchedInCol = false
            for (y in sampleRows) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                val isFilled = when (currency) {
                    CurrencyType.GOLD -> h in 30f..62f && s >= 0.48f && v >= 0.55f
                    CurrencyType.ELIXIR -> h in 275f..338f && s >= 0.42f && v >= 0.42f
                    CurrencyType.DARK_ELIXIR -> h in 205f..285f && s >= 0.18f && v in 0.16f..0.48f
                    CurrencyType.GEMS -> false
                    CurrencyType.UNKNOWN -> s >= 0.45f && v >= 0.50f
                }
                if (isFilled) {
                    matchedInCol = true
                    break
                }
            }
            if (matchedInCol) filledCols++
        }

        return ((filledCols * 100f) / cols).toInt().coerceIn(0, 100)
    }

    /**
     * Locates Clash of Clans's vivid green circular checkmark button ("✓") displayed above a newly
     * placed building blueprint so the bot can confirm construction cleanly.
     */
    fun findGreenCheckmarkButton(frame: Bitmap): NormPoint? {
        if (frame.isRecycled || frame.width < 120 || frame.height < 80) return null

        val hsv = FloatArray(3)
        val left = (frame.width * 0.16f).toInt()
        val right = (frame.width * 0.84f).toInt()
        val top = (frame.height * 0.14f).toInt()
        val bottom = (frame.height * 0.80f).toInt()
        val step = (frame.width / 120).coerceIn(8, 22)

        val greenHits = mutableListOf<Pair<Int, Int>>()
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                // Bright saturated lime-emerald button (distinct from duller background grass)
                if (h in 92f..146f && s >= 0.62f && v >= 0.62f) {
                    greenHits.add(x to y)
                }
                x += step
            }
            y += step
        }
        if (greenHits.size < 4) return null

        // Find the densest local cluster within ~4% screen radius
        val clusterRadius = frame.width * 0.042
        var bestCluster: List<Pair<Int, Int>> = emptyList()
        for (pt in greenHits) {
            val neighbors = greenHits.filter { other ->
                hypot((other.first - pt.first).toDouble(), (other.second - pt.second).toDouble()) <= clusterRadius
            }
            if (neighbors.size > bestCluster.size) {
                bestCluster = neighbors
            }
        }
        if (bestCluster.size < 4) return null

        val avgX = bestCluster.map { it.first }.average().toFloat() / frame.width
        val avgY = bestCluster.map { it.second }.average().toFloat() / frame.height
        return NormPoint(avgX.coerceIn(0.10f, 0.90f), avgY.coerceIn(0.10f, 0.85f))
    }

    /**
     * Identifies whether a cost icon right next to a price text is Gold, Elixir, Dark Elixir, or Gems
     * by examining HSV pixel votes slightly to the right/around [costBounds].
     */
    fun detectCurrencyForCost(frame: Bitmap, costBounds: NormRect): CurrencyType {
        if (frame.isRecycled || frame.width < 10 || frame.height < 10) return CurrencyType.UNKNOWN
        val iconRect = NormRect(
            left = (costBounds.right - 0.004f).coerceIn(0f, 0.96f),
            top = (costBounds.top - 0.008f).coerceIn(0f, 0.96f),
            right = (costBounds.right + 0.042f).coerceIn(0.02f, 0.995f),
            bottom = (costBounds.bottom + 0.008f).coerceIn(0.02f, 0.995f),
        ).normalized()

        val hsv = FloatArray(3)
        var goldVotes = 0
        var elixirVotes = 0
        var gemVotes = 0
        var darkVotes = 0
        val startX = (iconRect.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val endX = (iconRect.right * frame.width).toInt().coerceIn(startX + 1, frame.width)
        val startY = (iconRect.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val endY = (iconRect.bottom * frame.height).toInt().coerceIn(startY + 1, frame.height)

        val stepX = ((endX - startX) / 10).coerceAtLeast(1)
        val stepY = ((endY - startY) / 10).coerceAtLeast(1)

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                when {
                    h in 95f..158f && s >= 0.45f && v >= 0.45f -> gemVotes++
                    h in 32f..64f && s >= 0.45f && v >= 0.55f -> goldVotes++
                    (h in 275f..340f) && s >= 0.38f && v >= 0.40f -> elixirVotes++
                    v in 0.04f..0.28f && s <= 0.45f -> darkVotes++
                }
                x += stepX
            }
            y += stepY
        }

        return when {
            gemVotes >= 5 && gemVotes >= goldVotes && gemVotes >= elixirVotes -> CurrencyType.GEMS
            goldVotes >= 6 && goldVotes >= elixirVotes -> CurrencyType.GOLD
            elixirVotes >= 6 && elixirVotes > goldVotes -> CurrencyType.ELIXIR
            darkVotes >= 14 -> CurrencyType.DARK_ELIXIR
            else -> CurrencyType.UNKNOWN
        }
    }

    /**
     * Checks whether an upgrade price label (e.g. "200 000" or "1 000") is rendered in Clash of Clans's
     * bright coral/red unaffordable text color (meaning the player lacks enough resources right now).
     */
    fun isCostTextRedUnaffordable(frame: Bitmap, costBounds: NormRect): Boolean {
        if (frame.isRecycled || frame.width < 10 || frame.height < 10) return false
        val norm = costBounds.normalized()
        val startX = (norm.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val endX = (norm.right * frame.width).toInt().coerceIn(startX + 1, frame.width)
        val startY = (norm.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val endY = (norm.bottom * frame.height).toInt().coerceIn(startY + 1, frame.height)

        val hsv = FloatArray(3)
        var redVotes = 0
        var total = 0
        val cols = 12
        val rows = 6

        for (r in 0 until rows) {
            val py = (startY + ((endY - startY) * (r + 0.5f) / rows)).toInt().coerceIn(0, frame.height - 1)
            for (c in 0 until cols) {
                val px = (startX + ((endX - startX) * (c + 0.5f) / cols)).toInt().coerceIn(0, frame.width - 1)
                Color.colorToHSV(frame.getPixel(px, py), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                total++
                if ((h <= 18f || h >= 342f) && s >= 0.42f && v >= 0.55f) {
                    redVotes++
                }
            }
        }
        return total > 0 && (redVotes >= 5 || redVotes.toFloat() / total >= 0.08f)
    }

    /**
     * Scans the central village area for floating resource collector bubbles (Gold coin,
     * Elixir droplet, Dark Elixir droplet framed by a bright round white/cream speech halo).
     * Returns deduplicated normalized tap points.
     */
    fun findCollectorBubblePoints(frame: Bitmap, maxPoints: Int = 12): List<NormPoint> {
        if (frame.isRecycled || frame.width < 120 || frame.height < 80) return emptyList()

        val hsv = FloatArray(3)
        val scanLeft = (frame.width * 0.14f).toInt()
        val scanRight = (frame.width * 0.86f).toInt()
        val scanTop = (frame.height * 0.16f).toInt()
        val scanBottom = (frame.height * 0.80f).toInt()
        val step = (frame.width / 110).coerceIn(8, 22)

        val rawHits = mutableListOf<NormPoint>()
        var y = scanTop
        while (y < scanBottom) {
            var x = scanLeft
            while (x < scanRight) {
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                val isGoldCoin = h in 40f..58f && s >= 0.65f && v >= 0.78f
                val isElixirDrop = h in 285f..330f && s >= 0.58f && v >= 0.62f
                if (isGoldCoin || isElixirDrop) {
                    // Confirm nearby bright bubble ring pixel (characteristic white/cream outline of CoC collector icon)
                    if (hasBrightBubbleHalo(frame, x, y, step)) {
                        rawHits.add(NormPoint(x.toFloat() / frame.width, y.toFloat() / frame.height))
                    }
                }
                x += step
            }
            y += step
        }

        // Cluster nearby hits within 5.5% screen distance so each collector is tapped once
        val clustered = mutableListOf<NormPoint>()
        for (hit in rawHits) {
            val existsNearby = clustered.any { existing ->
                hypot((existing.x - hit.x).toDouble(), (existing.y - hit.y).toDouble()) < 0.055
            }
            if (!existsNearby) {
                clustered.add(hit)
                if (clustered.size >= maxPoints) break
            }
        }
        return clustered
    }

    private fun hasBrightBubbleHalo(frame: Bitmap, cx: Int, cy: Int, radius: Int): Boolean {
        val offsets = arrayOf(
            intArrayOf(0, -radius),
            intArrayOf(radius, 0),
            intArrayOf(0, radius),
            intArrayOf(-radius, 0),
        )
        val hsv = FloatArray(3)
        var brightRingHits = 0
        for (o in offsets) {
            val px = (cx + o[0]).coerceIn(0, frame.width - 1)
            val py = (cy + o[1]).coerceIn(0, frame.height - 1)
            Color.colorToHSV(frame.getPixel(px, py), hsv)
            if (hsv[2] >= 0.80f && hsv[1] <= 0.25f) {
                brightRingHits++
            }
        }
        return brightRingHits >= 2
    }

    /**
     * Downscaled grayscale Normalized Cross-Correlation (NCC) template matching.
     * Useful when matching custom saved UI templates.
     */
    fun matchTemplateNcc(
        haystack: Bitmap,
        needle: Bitmap,
        searchRect: NormRect = NormRect(0f, 0f, 1f, 1f),
        downscale: Int = 2,
        stride: Int = 2,
    ): Pair<NormPoint, Float>? {
        val scale = downscale.coerceAtLeast(1)
        val hs = Bitmap.createScaledBitmap(
            haystack,
            (haystack.width / scale).coerceAtLeast(16),
            (haystack.height / scale).coerceAtLeast(16),
            true,
        )
        val ns = Bitmap.createScaledBitmap(
            needle,
            (needle.width / scale).coerceAtLeast(4),
            (needle.height / scale).coerceAtLeast(4),
            true,
        )
        try {
            if (ns.width >= hs.width || ns.height >= hs.height) return null
            val hGray = toGrayscale(hs)
            val nGray = toGrayscale(ns)

            val nCount = nGray.size
            val nMean = nGray.average().toFloat()
            var nVar = 0f
            for (v in nGray) {
                val d = v - nMean
                nVar += d * d
            }
            if (nVar < 1e-3f) return null

            val norm = searchRect.normalized()
            val minX = (norm.left * hs.width).toInt().coerceIn(0, hs.width - ns.width)
            val maxX = ((norm.right * hs.width).toInt() - ns.width).coerceIn(minX, hs.width - ns.width)
            val minY = (norm.top * hs.height).toInt().coerceIn(0, hs.height - ns.height)
            val maxY = ((norm.bottom * hs.height).toInt() - ns.height).coerceIn(minY, hs.height - ns.height)

            var bestScore = -1f
            var bestX = minX
            var bestY = minY

            var y = minY
            while (y <= maxY) {
                var x = minX
                while (x <= maxX) {
                    var sumH = 0f
                    for (ny in 0 until ns.height) {
                        val hRow = (y + ny) * hs.width + x
                        for (nx in 0 until ns.width) {
                            sumH += hGray[hRow + nx]
                        }
                    }
                    val hMean = sumH / nCount
                    var cov = 0f
                    var hVar = 0f
                    for (ny in 0 until ns.height) {
                        val hRow = (y + ny) * hs.width + x
                        val nRow = ny * ns.width
                        for (nx in 0 until ns.width) {
                            val dh = hGray[hRow + nx] - hMean
                            val dn = nGray[nRow + nx] - nMean
                            cov += dh * dn
                            hVar += dh * dh
                        }
                    }
                    val denom = sqrt(hVar * nVar)
                    val score = if (denom > 1e-3f) cov / denom else 0f
                    if (score > bestScore) {
                        bestScore = score
                        bestX = x
                        bestY = y
                    }
                    x += stride
                }
                y += stride
            }

            val cx = (bestX + ns.width * 0.5f) / hs.width.toFloat()
            val cy = (bestY + ns.height * 0.5f) / hs.height.toFloat()
            return NormPoint(cx, cy) to bestScore
        } finally {
            hs.recycle()
            ns.recycle()
        }
    }

    private fun toGrayscale(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return out
    }

    private data class RegionHsvStats(
        val whiteCloudRatio: Float,
        val darkRatio: Float,
        val colorfulRatio: Float,
        val avgSaturation: Float,
    )

    private fun sampleRegionHsv(
        frame: Bitmap,
        rect: NormRect,
        gridCols: Int,
        gridRows: Int,
    ): RegionHsvStats {
        val norm = rect.normalized()
        val left = (norm.left * frame.width).toInt().coerceIn(0, frame.width - 1)
        val right = (norm.right * frame.width).toInt().coerceIn(left + 1, frame.width)
        val top = (norm.top * frame.height).toInt().coerceIn(0, frame.height - 1)
        val bottom = (norm.bottom * frame.height).toInt().coerceIn(top + 1, frame.height)

        val hsv = FloatArray(3)
        var total = 0
        var whiteClouds = 0
        var dark = 0
        var colorful = 0
        var satSum = 0f

        for (r in 0 until gridRows) {
            val py = (top + ((bottom - top) * (r + 0.5f) / gridRows)).toInt().coerceIn(0, frame.height - 1)
            for (c in 0 until gridCols) {
                val px = (left + ((right - left) * (c + 0.5f) / gridCols)).toInt().coerceIn(0, frame.width - 1)
                Color.colorToHSV(frame.getPixel(px, py), hsv)
                val s = hsv[1]
                val v = hsv[2]
                total++
                satSum += s
                if (v >= 0.89f && s <= 0.13f) whiteClouds++
                if (v <= 0.10f) dark++
                if (s >= 0.30f && v >= 0.28f) colorful++
            }
        }
        if (total == 0) return RegionHsvStats(0f, 0f, 0f, 0f)
        return RegionHsvStats(
            whiteCloudRatio = whiteClouds.toFloat() / total,
            darkRatio = dark.toFloat() / total,
            colorfulRatio = colorful.toFloat() / total,
            avgSaturation = satSum / total,
        )
    }

    /**
     * Quick perceptual difference score (0..1) between two frames inside a region
     * to detect whether a battle or dialog is frozen/unchanged.
     */
    fun frameDifference(a: Bitmap, b: Bitmap, rect: NormRect = NormRect(0.15f, 0.15f, 0.85f, 0.85f)): Float {
        if (a.width != b.width || a.height != b.height || a.isRecycled || b.isRecycled) return 1f
        val norm = rect.normalized()
        val left = (norm.left * a.width).toInt().coerceIn(0, a.width - 1)
        val right = (norm.right * a.width).toInt().coerceIn(left + 1, a.width)
        val top = (norm.top * a.height).toInt().coerceIn(0, a.height - 1)
        val bottom = (norm.bottom * a.height).toInt().coerceIn(top + 1, a.height)

        var diffSum = 0f
        var samples = 0
        for (r in 0 until 8) {
            val y = (top + (bottom - top) * (r + 0.5f) / 8f).toInt().coerceIn(0, a.height - 1)
            for (c in 0 until 12) {
                val x = (left + (right - left) * (c + 0.5f) / 12f).toInt().coerceIn(0, a.width - 1)
                val pa = a.getPixel(x, y)
                val pb = b.getPixel(x, y)
                val dr = abs(((pa shr 16) and 0xFF) - ((pb shr 16) and 0xFF))
                val dg = abs(((pa shr 8) and 0xFF) - ((pb shr 8) and 0xFF))
                val db = abs((pa and 0xFF) - (pb and 0xFF))
                diffSum += (dr + dg + db) / (3f * 255f)
                samples++
            }
        }
        return if (samples == 0) 1f else diffSum / samples
    }
}
