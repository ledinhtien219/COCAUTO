package com.cocauto.helper.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import kotlin.math.abs

data class DetectedTroopCard(
    val tapPoint: NormPoint,
    val count: Int,
    val isVisualFallback: Boolean = false,
    val rawLabel: String = "",
)

/**
 * Automatically detects every available troop, spell, and hero card on the live Clash of Clans
 * battle troop bar (bottom strip) without relying on hardcoded 10-slot spacing.
 *
 * Strategy:
 * 1. Enhanced OCR scans the bottom troop dock for real quantity badges ("x30", "x12", "×8", "x1")
 *    giving exact tap coordinates + real unit count for each available card.
 * 2. Visual framed-card segmentation scans the bar horizontally for colorful active cards
 *    (catching Heroes that have no "xN" quantity badge, or any troop badge missed by OCR).
 */
object AutoTroopBarDetector {

    private val countBadgeRegex = Regex("""(?i)[x×*]\s*([0-9OoIl|]{1,3})""")
    private val trailingCountRegex = Regex("""(?i)^([0-9OoIl|]{1,3})\s*[x×*]$""")

    /**
     * Pure Kotlin parser mapping OCR text lines on the bottom troop bar into sorted [DetectedTroopCard]s.
     */
    fun parseTroopCardsFromOcr(
        lines: List<OcrTextLine>,
        barRoi: NormRect = NormRect(0.02f, 0.79f, 0.98f, 0.99f),
    ): List<DetectedTroopCard> {
        val rawCards = mutableListOf<DetectedTroopCard>()

        for (line in lines) {
            // Quantity badges ("x30", "x8") appear along the upper/middle portion of troop cards
            if (line.bounds.centerY < barRoi.top - 0.02f || line.bounds.centerY > barRoi.bottom) continue

            val matches = countBadgeRegex.findAll(line.text).toList()
            if (matches.isNotEmpty()) {
                val totalChars = line.text.length.coerceAtLeast(1).toFloat()
                val lineW = line.bounds.width
                for (m in matches) {
                    val count = parseCountDigits(m.groupValues[1]) ?: continue
                    if (count !in 1..400) continue
                    val midFrac = ((m.range.first + m.range.last + 1) * 0.5f / totalChars).coerceIn(0.05f, 0.95f)
                    val cx = (line.bounds.left + lineW * midFrac).coerceIn(0.04f, 0.96f)
                    // Tap safely in the center of the card right below its "xN" count label
                    val cy = (line.bounds.bottom + 0.048f).coerceIn(0.875f, 0.945f)
                    rawCards.add(
                        DetectedTroopCard(
                            tapPoint = NormPoint(cx, cy),
                            count = count,
                            isVisualFallback = false,
                            rawLabel = m.value.trim(),
                        ),
                    )
                }
                continue
            }

            val trailing = trailingCountRegex.matchEntire(line.text.trim())
            if (trailing != null) {
                val count = parseCountDigits(trailing.groupValues[1])
                if (count != null && count in 1..400) {
                    val cx = line.bounds.centerX.coerceIn(0.04f, 0.96f)
                    val cy = (line.bounds.bottom + 0.048f).coerceIn(0.875f, 0.945f)
                    rawCards.add(
                        DetectedTroopCard(
                            tapPoint = NormPoint(cx, cy),
                            count = count,
                            isVisualFallback = false,
                            rawLabel = line.text.trim(),
                        ),
                    )
                }
            }
        }

        return dedupCardsLeftToRight(rawCards)
    }

    /**
     * Full live detection combining OCR quantity badges + visual active-card fallback (for Heroes / missed OCR).
     */
    suspend fun detectAvailableCards(
        frame: Bitmap,
        calibration: CalibrationProfile,
        ocrEngine: OcrEngine,
    ): List<DetectedTroopCard> {
        val barScanRoi = NormRect(
            left = 0.02f,
            top = (calibration.troopBarRoi.top - 0.045f).coerceIn(0.76f, 0.84f),
            right = 0.98f,
            bottom = 0.992f,
        ).normalized()

        val ocr = ocrEngine.recognizeRoi(frame, barScanRoi, enhance = true)
        val ocrCards = parseTroopCardsFromOcr(ocr.lines, barScanRoi)

        // Filter out any stale/grayed-out cards via HSV saturation check
        val activeOcrCards = ocrCards.filter { card ->
            val cardRect = NormRect(
                left = (card.tapPoint.x - 0.028f).coerceIn(0.01f, 0.94f),
                top = (card.tapPoint.y - 0.042f).coerceIn(0.80f, 0.92f),
                right = (card.tapPoint.x + 0.028f).coerceIn(0.06f, 0.99f),
                bottom = (card.tapPoint.y + 0.042f).coerceIn(0.88f, 0.99f),
            ).normalized()
            ImageAnalyzer.isTroopSlotAvailable(frame, cardRect)
        }

        // Visual scan catches Heroes (no "xN" label) and any active troop card whose "xN" wasn't OCR'd
        val visualCards = detectVisualActiveCards(frame, barScanRoi)
        val merged = mutableListOf<DetectedTroopCard>()
        merged.addAll(activeOcrCards)
        for (vc in visualCards) {
            val overlapsExisting = merged.any { abs(it.tapPoint.x - vc.tapPoint.x) < 0.055f }
            if (!overlapsExisting) {
                merged.add(vc)
            }
        }

        return dedupCardsLeftToRight(merged)
    }

    /**
     * Scans the bottom dock horizontally for contiguous saturated non-grass card blocks
     * (Clash of Clans active troop/hero/spell cards have distinct blue/purple/gold/skin colors
     * and bright card borders vs green background grass or monochrome gray spent slots).
     */
    private fun detectVisualActiveCards(frame: Bitmap, barRoi: NormRect): List<DetectedTroopCard> {
        if (frame.isRecycled || frame.width < 200 || frame.height < 120) return emptyList()

        val cols = 64
        val startX = (barRoi.left * frame.width).toInt().coerceIn(0, frame.width - 2)
        val endX = (barRoi.right * frame.width).toInt().coerceIn(startX + 10, frame.width - 1)
        val topY = (frame.height * 0.855f).toInt().coerceIn(0, frame.height - 4)
        val botY = (frame.height * 0.965f).toInt().coerceIn(topY + 4, frame.height - 1)

        val hsv = FloatArray(3)
        val colActive = BooleanArray(cols)

        for (c in 0 until cols) {
            val x = (startX + ((endX - startX) * (c + 0.5f) / cols)).toInt().coerceIn(0, frame.width - 1)
            var nonGrassVibrant = 0
            var samples = 0
            for (r in 0 until 8) {
                val y = (topY + ((botY - topY) * (r + 0.5f) / 8f)).toInt().coerceIn(0, frame.height - 1)
                Color.colorToHSV(frame.getPixel(x, y), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                samples++
                // Exclude dull olive-green CoC grass (H 72..120) & desaturated gray empty slots (S < 0.28)
                val isGrass = h in 72f..118f && s < 0.65f && v < 0.78f
                if (!isGrass && s >= 0.30f && v >= 0.30f) {
                    nonGrassVibrant++
                }
            }
            colActive[c] = samples > 0 && nonGrassVibrant >= 3
        }

        // Group contiguous active columns into card segments (a CoC card spans ~3..6 columns out of 64)
        val cards = mutableListOf<DetectedTroopCard>()
        var runStart = -1
        for (c in 0..cols) {
            val active = if (c < cols) colActive[c] else false
            if (active && runStart < 0) {
                runStart = c
            } else if (!active && runStart >= 0) {
                val runLen = c - runStart
                if (runLen in 2..14) {
                    // If multiple adjacent cards merged together, split into ~7.5%-wide card centers
                    val leftNorm = barRoi.left + (barRoi.width * runStart / cols)
                    val rightNorm = barRoi.left + (barRoi.width * c / cols)
                    val segW = rightNorm - leftNorm
                    val subCount = (segW / 0.072f).toInt().coerceIn(1, 10)
                    val stepW = segW / subCount
                    for (k in 0 until subCount) {
                        val cx = (leftNorm + stepW * (k + 0.5f)).coerceIn(0.05f, 0.95f)
                        cards.add(
                            DetectedTroopCard(
                                tapPoint = NormPoint(cx, 0.910f),
                                count = 6, // Conservative default count when visual-only (covers heroes/spells/troops)
                                isVisualFallback = true,
                                rawLabel = "card",
                            ),
                        )
                    }
                }
                runStart = -1
            }
        }
        return cards
    }

    private fun parseCountDigits(raw: String): Int? {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                'O', 'o' -> sb.append('0')
                'I', 'l', '|' -> sb.append('1')
            }
        }
        return sb.toString().toIntOrNull()
    }

    private fun dedupCardsLeftToRight(cards: List<DetectedTroopCard>): List<DetectedTroopCard> {
        val sorted = cards.sortedBy { it.tapPoint.x }
        val result = mutableListOf<DetectedTroopCard>()
        for (card in sorted) {
            val existingIdx = result.indexOfFirst { abs(it.tapPoint.x - card.tapPoint.x) < 0.048f }
            if (existingIdx < 0) {
                result.add(card)
            } else if (result[existingIdx].isVisualFallback && !card.isVisualFallback) {
                result[existingIdx] = card
            }
        }
        return result
    }
}
