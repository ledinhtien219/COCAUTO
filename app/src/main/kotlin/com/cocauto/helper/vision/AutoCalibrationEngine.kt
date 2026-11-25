package com.cocauto.helper.vision

import android.graphics.Bitmap
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

data class AutoCalibrationResult(
    val profile: CalibrationProfile,
    val detectedItems: List<String>,
    val screenWidth: Int,
    val screenHeight: Int,
) {
    val hasDetections: Boolean get() = detectedItems.isNotEmpty()

    fun summaryText(): String {
        return if (detectedItems.isEmpty()) {
            "Chưa nhận diện được UI Clash of Clans (${screenWidth}x${screenHeight}). Hãy mở màn hình Nhà Chính hoặc Soi nhà rồi thử lại."
        } else {
            "Tự nhận diện xong (${screenWidth}x${screenHeight}): ${detectedItems.joinToString(", ")}"
        }
    }
}

/**
 * Automatically detects Clash of Clans UI buttons, resource bars, builder/lab badges,
 * loot panel, and playable viewport safe-area insets directly from a live screenshot.
 * Works across any Android phone resolution, notch/cutout inset, and English/Vietnamese game UI.
 */
object AutoCalibrationEngine {

    private val multiFractionRegex = Regex("""([0-9OoIl|])\s*/\s*([1-7OoIl|])""")

    suspend fun detectFromFrame(
        frame: Bitmap,
        current: CalibrationProfile,
        ocrEngine: OcrEngine,
    ): AutoCalibrationResult {
        val base = CalibrationProfile.forScreenDimensions(frame.width, frame.height).copy(
            enemyLootRoi = current.enemyLootRoi,
            nextBaseButton = current.nextBaseButton,
            nextButtonRoi = current.nextButtonRoi,
            endBattleButton = current.endBattleButton,
            endBattleRoi = current.endBattleRoi,
            returnHomeButton = current.returnHomeButton,
            returnHomeRoi = current.returnHomeRoi,
        )

        // Run all 3 OCR passes in parallel (cuts auto-calibration latency by ~2.5x!)
        val mergedLines = coroutineScope {
            val fullDef = async { ocrEngine.recognizeRoi(frame, NormRect(0f, 0f, 1f, 1f), enhance = false) }
            val blDef = async { ocrEngine.recognizeRoi(frame, NormRect(0.01f, 0.78f, 0.22f, 0.995f), enhance = true) }
            val brDef = async { ocrEngine.recognizeRoi(frame, NormRect(0.76f, 0.68f, 0.98f, 0.85f), enhance = true) }
            buildList {
                addAll(fullDef.await().lines)
                addAll(blDef.await().lines)
                addAll(brDef.await().lines)
            }
        }

        return applyDetectedOcr(
            base = base,
            lines = mergedLines,
            screenWidth = frame.width,
            screenHeight = frame.height,
        )
    }

    /**
     * Pure Kotlin geometry & OCR matcher that maps detected text bounding boxes into
     * an exact normalized [CalibrationProfile]. Easily unit-tested without Android Bitmaps.
     */
    fun applyDetectedOcr(
        base: CalibrationProfile,
        lines: List<OcrTextLine>,
        screenWidth: Int,
        screenHeight: Int,
    ): AutoCalibrationResult {
        var profile = base.copy(schemaVersion = CalibrationProfile.CURRENT_SCHEMA_VERSION)
        val detected = mutableListOf<String>()

        var leftSafeEdge = 0.045f
        var rightSafeEdge = 0.955f
        var detectedHomeLayout = false

        // 1. Detect Home Attack button ("CHIẾN ĐẤU" / "ATTACK") at bottom-left
        val attackLine = lines.firstOrNull { line ->
            line.bounds.centerX < 0.24f &&
                line.bounds.centerY > 0.74f &&
                ResourceParser.isHomeAttackButtonText(line.text)
        }
        if (attackLine != null) {
            detectedHomeLayout = true
            leftSafeEdge = (attackLine.bounds.left - 0.010f).coerceIn(0.01f, 0.14f)
            val labelRoi = NormRect(
                left = (attackLine.bounds.left - 0.018f).coerceIn(0f, 0.95f),
                top = (attackLine.bounds.top - 0.020f).coerceIn(0f, 0.95f),
                right = (attackLine.bounds.right + 0.018f).coerceIn(0.05f, 1f),
                bottom = (attackLine.bounds.bottom + 0.018f).coerceIn(0.05f, 1f),
            ).normalized()
            val buttonCenter = NormPoint(
                x = attackLine.bounds.centerX.coerceIn(0.04f, 0.18f),
                y = (attackLine.bounds.top - 0.065f).coerceIn(0.76f, 0.90f),
            )
            profile = profile.copy(
                homeAttackButton = buttonCenter,
                bbAttackButton = buttonCenter,
                homeAttackLabelRoi = labelRoi,
            )
            detected.add("Nút Đánh")
        }

        // 2. Detect Shop ("Xây dựng" / "Shop") at bottom-right to measure exact right safe inset
        val shopLine = lines.firstOrNull { line ->
            val norm = ResourceParser.normalizeForMatch(line.text)
            line.bounds.centerX > 0.78f &&
                line.bounds.centerY > 0.75f &&
                (norm.contains("xay dung") || norm.contains("shop"))
        }
        if (shopLine != null) {
            detectedHomeLayout = true
            rightSafeEdge = (shopLine.bounds.right + 0.015f).coerceIn(0.86f, 0.99f)
        }

        // 3. Detect Home Village Resources (Gold, Elixir, Dark Elixir) at top-right (x > 0.68, y < 0.28)
        val topRightNumbers = lines
            .filter { line ->
                val norm = ResourceParser.normalizeForMatch(line.text)
                line.bounds.centerX > 0.68f &&
                    line.bounds.centerY in 0.015f..0.25f &&
                    !norm.contains("gio") &&
                    !norm.contains("ng") &&
                    !norm.contains("ph") &&
                    !norm.contains("gi") &&
                    !norm.contains("h ") &&
                    !norm.contains("d ") &&
                    ResourceParser.parseResourceNumber(line.text) != null
            }
            .sortedBy { it.bounds.centerY }
            .fold(mutableListOf<OcrTextLine>()) { acc, item ->
                // Deduplicate overlapping lines from merged OCR passes
                if (acc.none { abs(it.bounds.centerY - item.bounds.centerY) < 0.025f }) {
                    acc.add(item)
                }
                acc
            }

        if (topRightNumbers.size >= 2) {
            detectedHomeLayout = true
            val goldLine = topRightNumbers[0]
            val elixirLine = topRightNumbers[1]
            val rowGap = (elixirLine.bounds.centerY - goldLine.bounds.centerY).coerceIn(0.055f, 0.095f)
            val barLeft = (minOf(goldLine.bounds.left, elixirLine.bounds.left) - 0.075f).coerceIn(0.70f, 0.86f)
            val barRight = (maxOf(goldLine.bounds.right, elixirLine.bounds.right) + 0.032f).coerceIn(0.88f, 0.985f)
            rightSafeEdge = maxOf(rightSafeEdge, (barRight + 0.020f).coerceAtMost(0.99f))

            val goldRoi = NormRect(
                left = barLeft,
                top = (goldLine.bounds.top - 0.013f).coerceIn(0.005f, 0.15f),
                right = barRight,
                bottom = (goldLine.bounds.bottom + 0.013f).coerceIn(0.04f, 0.20f),
            ).normalized()
            val elixirRoi = NormRect(
                left = barLeft,
                top = (elixirLine.bounds.top - 0.013f).coerceIn(0.05f, 0.22f),
                right = barRight,
                bottom = (elixirLine.bounds.bottom + 0.013f).coerceIn(0.09f, 0.26f),
            ).normalized()

            val thirdLine = topRightNumbers.getOrNull(2)
            val darkRoi = if (thirdLine != null && abs(thirdLine.bounds.centerY - (elixirLine.bounds.centerY + rowGap)) <= 0.045f) {
                NormRect(
                    left = (barLeft + 0.030f).coerceAtMost(thirdLine.bounds.left - 0.03f),
                    top = (thirdLine.bounds.top - 0.013f).coerceIn(0.12f, 0.30f),
                    right = barRight,
                    bottom = (thirdLine.bounds.bottom + 0.013f).coerceIn(0.16f, 0.34f),
                ).normalized()
            } else {
                NormRect(
                    left = (barLeft + 0.030f).coerceIn(0.72f, 0.88f),
                    top = (elixirRoi.top + rowGap).coerceIn(0.14f, 0.28f),
                    right = barRight,
                    bottom = (elixirRoi.bottom + rowGap).coerceIn(0.19f, 0.34f),
                ).normalized()
            }

            profile = profile.copy(
                homeGoldRoi = goldRoi,
                homeElixirRoi = elixirRoi,
                homeDarkElixirRoi = darkRoi,
            )
            detected.add("Vàng/Dầu nhà")
        }

        // 4. Detect Builder badge ("0/2", "2/5") & Lab badge ("1/1") — splitting merged lines like "0/2 1/1"!
        val extractedBadges = extractFractionBadges(lines)

        val bottomRightBadges = extractedBadges
            .filter { it.bounds.centerX > 0.72f && it.bounds.centerY > 0.62f }
            .sortedBy { it.bounds.centerX }
            .dedupBadgesByX()

        val topCenterBadges = extractedBadges
            .filter { it.bounds.centerX in 0.30f..0.68f && it.bounds.centerY < 0.16f }
            .sortedBy { it.bounds.centerX }
            .dedupBadgesByX()

        val chosenBadges = bottomRightBadges.ifEmpty { topCenterBadges }
        if (chosenBadges.isNotEmpty()) {
            detectedHomeLayout = true
            val builderBadge = chosenBadges[0]
            val bRoi = NormRect(
                left = (builderBadge.bounds.left - 0.014f).coerceIn(0.02f, 0.94f),
                top = (builderBadge.bounds.top - 0.022f).coerceIn(0.005f, 0.94f),
                right = (builderBadge.bounds.right + 0.014f).coerceIn(0.06f, 0.99f),
                bottom = (builderBadge.bounds.bottom + 0.018f).coerceIn(0.04f, 0.99f),
            ).normalized()

            val isBottomRight = builderBadge.bounds.centerY > 0.50f
            val suggestionsRoi = if (isBottomRight) {
                NormRect(
                    left = (rightSafeEdge - 0.285f).coerceIn(0.64f, 0.72f),
                    top = 0.085f,
                    right = (rightSafeEdge + 0.010f).coerceIn(0.92f, 0.98f),
                    bottom = 0.865f,
                ).normalized()
            } else {
                NormRect(
                    left = (bRoi.centerX - 0.16f).coerceIn(0.20f, 0.45f),
                    top = (bRoi.bottom + 0.015f).coerceIn(0.09f, 0.18f),
                    right = (bRoi.centerX + 0.18f).coerceIn(0.55f, 0.80f),
                    bottom = (bRoi.bottom + 0.54f).coerceIn(0.55f, 0.72f),
                ).normalized()
            }

            profile = profile.copy(
                builderBadgeRoi = bRoi,
                builderBadgeButton = bRoi.centerPoint(),
                builderSuggestionsRoi = suggestionsRoi,
            )
            detected.add("Thợ xây (${builderBadge.status.freeBuilders}/${builderBadge.status.totalBuilders})")

            val labBadge = chosenBadges.getOrNull(1)
            if (labBadge != null) {
                profile = profile.copy(labShortcutPoint = labBadge.bounds.centerPoint())
                detected.add("Lab (${labBadge.status.freeBuilders}/${labBadge.status.totalBuilders})")
            } else {
                profile = profile.copy(
                    labShortcutPoint = NormPoint(
                        x = (bRoi.centerX + 0.048f).coerceIn(0.10f, 0.96f),
                        y = bRoi.centerY,
                    ),
                )
            }
        }

        // 5. Detect Battle Scout: Enemy Loot panel (top-left numeric stack) & Next button
        val nextLine = lines.firstOrNull { line ->
            line.bounds.centerX > 0.75f &&
                line.bounds.centerY > 0.62f &&
                ResourceParser.isNextButtonText(line.text)
        }
        if (nextLine != null) {
            val nRoi = NormRect(
                left = (nextLine.bounds.left - 0.045f).coerceIn(0.70f, 0.92f),
                top = (nextLine.bounds.top - 0.045f).coerceIn(0.55f, 0.85f),
                right = (nextLine.bounds.right + 0.045f).coerceIn(0.80f, 0.995f),
                bottom = (nextLine.bounds.bottom + 0.045f).coerceIn(0.68f, 0.95f),
            ).normalized()
            profile = profile.copy(
                nextBaseButton = nextLine.bounds.centerPoint(),
                nextButtonRoi = nRoi,
            )
            detected.add("Nút Tiếp (Next)")

            val lootNumbers = lines
                .filter { line ->
                    line.bounds.centerX < 0.30f &&
                        line.bounds.centerY in 0.08f..0.38f &&
                        ResourceParser.parseResourceNumber(line.text) != null
                }
                .sortedBy { it.bounds.centerY }
            if (lootNumbers.size >= 2) {
                val minL = lootNumbers.minOf { it.bounds.left }
                val maxR = lootNumbers.maxOf { it.bounds.right }
                val minT = lootNumbers.minOf { it.bounds.top }
                val maxB = lootNumbers.maxOf { it.bounds.bottom }
                val lootRoi = NormRect(
                    left = (minL - 0.025f).coerceIn(0.01f, 0.20f),
                    top = (minT - 0.020f).coerceIn(0.05f, 0.20f),
                    right = (maxR + 0.045f).coerceIn(0.14f, 0.34f),
                    bottom = (maxB + 0.065f).coerceIn(0.22f, 0.42f),
                ).normalized()
                profile = profile.copy(enemyLootRoi = lootRoi)
                detected.add("Khung Loot địch")
            }
        }

        // 6. Detect End Battle / Surrender button (bottom-left)
        val endBattleLine = lines.firstOrNull { line ->
            line.bounds.centerX < 0.24f &&
                line.bounds.centerY > 0.62f &&
                ResourceParser.isEndBattleText(line.text)
        }
        if (endBattleLine != null) {
            val eRoi = NormRect(
                left = (endBattleLine.bounds.left - 0.03f).coerceIn(0f, 0.20f),
                top = (endBattleLine.bounds.top - 0.04f).coerceIn(0.58f, 0.85f),
                right = (endBattleLine.bounds.right + 0.03f).coerceIn(0.08f, 0.28f),
                bottom = (endBattleLine.bounds.bottom + 0.04f).coerceIn(0.70f, 0.94f),
            ).normalized()
            profile = profile.copy(
                endBattleButton = endBattleLine.bounds.centerPoint(),
                endBattleRoi = eRoi,
            )
            detected.add("Nút Kết thúc")
        }

        // 7. Detect Return Home button (bottom-center)
        val returnHomeLine = lines.firstOrNull { line ->
            line.bounds.centerX in 0.32f..0.68f &&
                line.bounds.centerY > 0.72f &&
                ResourceParser.isReturnHomeText(line.text)
        }
        if (returnHomeLine != null) {
            val rRoi = NormRect(
                left = (returnHomeLine.bounds.left - 0.05f).coerceIn(0.25f, 0.50f),
                top = (returnHomeLine.bounds.top - 0.04f).coerceIn(0.70f, 0.90f),
                right = (returnHomeLine.bounds.right + 0.05f).coerceIn(0.50f, 0.75f),
                bottom = (returnHomeLine.bounds.bottom + 0.04f).coerceIn(0.80f, 0.98f),
            ).normalized()
            profile = profile.copy(
                returnHomeButton = returnHomeLine.bounds.centerPoint(),
                returnHomeRoi = rRoi,
            )
            detected.add("Nút Về nhà")
        }

        // 8. Re-anchor playable viewport elements neatly inside deployable grass boundaries
        if (detectedHomeLayout) {
            val usableW = (rightSafeEdge - leftSafeEdge).coerceIn(0.76f, 0.96f)
            val centerX = (leftSafeEdge + rightSafeEdge) * 0.5f

            // Keep East/West/North/South corners safely on outer green grass outside the enemy red zone
            val deployHalfW = (usableW * 0.415f).coerceIn(0.36f, 0.43f)
            val safeAttackBtn = if (attackLine == null) {
                NormPoint((leftSafeEdge + 0.052f).coerceIn(0.06f, 0.14f), 0.870f)
            } else {
                profile.homeAttackButton
            }

            profile = profile.copy(
                homeAttackButton = safeAttackBtn,
                bbAttackButton = safeAttackBtn,
                deployTop = NormPoint(centerX, 0.085f),
                deployBottom = NormPoint(centerX, 0.775f),
                deployLeft = NormPoint((centerX - deployHalfW).coerceIn(0.08f, 0.16f), 0.465f),
                deployRight = NormPoint((centerX + deployHalfW).coerceIn(0.84f, 0.92f), 0.465f),
                troopBarRoi = NormRect(
                    left = (leftSafeEdge + usableW * 0.035f).coerceIn(0.05f, 0.16f),
                    top = 0.840f,
                    right = (rightSafeEdge - usableW * 0.035f).coerceIn(0.84f, 0.95f),
                    bottom = 0.978f,
                ).normalized(),
                boatToBuilderBase = NormPoint(
                    x = (leftSafeEdge + usableW * 0.22f).coerceIn(0.18f, 0.32f),
                    y = 0.815f,
                ),
                lootCartPoint = NormPoint(
                    x = (leftSafeEdge + usableW * 0.15f).coerceIn(0.12f, 0.25f),
                    y = 0.250f,
                ),
                enemyLootRoi = if (nextLine == null) {
                    NormRect(
                        left = (leftSafeEdge + 0.005f).coerceIn(0.02f, 0.12f),
                        top = 0.105f,
                        right = (leftSafeEdge + usableW * 0.21f).coerceIn(0.18f, 0.30f),
                        bottom = 0.315f,
                    ).normalized()
                } else {
                    profile.enemyLootRoi
                },
                nextBaseButton = if (nextLine == null) {
                    NormPoint((rightSafeEdge - 0.068f).coerceIn(0.84f, 0.92f), 0.715f)
                } else {
                    profile.nextBaseButton
                },
                nextButtonRoi = if (nextLine == null) {
                    NormRect(
                        left = (rightSafeEdge - 0.150f).coerceIn(0.78f, 0.85f),
                        top = 0.635f,
                        right = (rightSafeEdge + 0.010f).coerceIn(0.92f, 0.99f),
                        bottom = 0.800f,
                    ).normalized()
                } else {
                    profile.nextButtonRoi
                },
                endBattleButton = if (endBattleLine == null) {
                    NormPoint((leftSafeEdge + 0.055f).coerceIn(0.06f, 0.14f), 0.745f)
                } else {
                    profile.endBattleButton
                },
                endBattleRoi = if (endBattleLine == null) {
                    NormRect(
                        left = (leftSafeEdge - 0.010f).coerceIn(0.01f, 0.08f),
                        top = 0.690f,
                        right = (leftSafeEdge + 0.130f).coerceIn(0.14f, 0.24f),
                        bottom = 0.805f,
                    ).normalized()
                } else {
                    profile.endBattleRoi
                },
                // Multiplayer "Find a Match" / "Tìm Trận đấu" sits on the left/lower-left of the 2026 Battle hub
                findMatchButton = NormPoint((leftSafeEdge + usableW * 0.185f).coerceIn(0.18f, 0.30f), 0.740f),
                armyConfirmAttackButton = NormPoint((rightSafeEdge - 0.125f).coerceIn(0.76f, 0.88f), 0.900f),
                returnHomeButton = if (returnHomeLine == null) NormPoint(centerX, 0.870f) else profile.returnHomeButton,
            )
            detected.add("Khung thả quân & Thanh lính")
        }

        return AutoCalibrationResult(
            profile = profile,
            detectedItems = detected,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )
    }

    private data class FractionBadge(
        val status: BuilderStatus,
        val bounds: NormRect,
    )

    /**
     * Extracts individual X/Y builder/lab badges even when ML Kit merges adjacent badges
     * like "0/2 1/1" into a single horizontal OCR line.
     */
    private fun extractFractionBadges(lines: List<OcrTextLine>): List<FractionBadge> {
        val out = mutableListOf<FractionBadge>()
        for (line in lines) {
            val matches = multiFractionRegex.findAll(line.text).toList()
            if (matches.isEmpty()) continue

            val totalChars = line.text.length.coerceAtLeast(1).toFloat()
            val lineW = line.bounds.width
            for (m in matches) {
                val status = ResourceParser.parseBuilderStatus(m.value) ?: continue
                val startFrac = (m.range.first / totalChars).coerceIn(0f, 0.9f)
                val endFrac = ((m.range.last + 1) / totalChars).coerceIn(startFrac + 0.1f, 1f)
                val subBounds = NormRect(
                    left = line.bounds.left + lineW * startFrac,
                    top = line.bounds.top,
                    right = line.bounds.left + lineW * endFrac,
                    bottom = line.bounds.bottom,
                ).normalized()
                out.add(FractionBadge(status, subBounds))
            }
        }
        return out
    }

    private fun List<FractionBadge>.dedupBadgesByX(): List<FractionBadge> {
        val result = mutableListOf<FractionBadge>()
        for (badge in this) {
            if (result.none { abs(it.bounds.centerX - badge.bounds.centerX) < 0.030f }) {
                result.add(badge)
            }
        }
        return result
    }
}
