package com.cocauto.helper.vision

import android.graphics.Bitmap
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.CurrencyType
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.HomeResources
import com.cocauto.helper.model.LootReading
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.StorageFillLevels

data class ScreenAnalysis(
    val state: GameScreenState,
    val suggestedTapPoint: NormPoint? = null,
    val detailText: String = "",
)

/**
 * Classifies live Clash of Clans screen frames using lightweight HSV checks + targeted ML Kit OCR.
 */
class ScreenStateDetector(
    private val ocrEngine: OcrEngine,
) {
    suspend fun analyzeScreen(
        frame: Bitmap,
        calibration: CalibrationProfile,
        foregroundPackage: String,
    ): ScreenAnalysis {
        if (foregroundPackage.isNotBlank() && foregroundPackage != COC_PACKAGE) {
            return ScreenAnalysis(GameScreenState.NOT_IN_GAME, detailText = foregroundPackage)
        }

        if (ImageAnalyzer.isDarkLoadingScreen(frame)) {
            return ScreenAnalysis(GameScreenState.LOADING, detailText = "Dark screen")
        }

        if (ImageAnalyzer.isCloudSearchScreen(frame)) {
            return ScreenAnalysis(GameScreenState.SEARCHING_CLOUDS, detailText = "Clouds")
        }

        // 1. Check center dialogs (Disconnect / Reload / Personal break / Welcome Back / Star Bonus)
        val wideDialogRoi = com.cocauto.helper.model.NormRect(0.18f, 0.06f, 0.82f, 0.88f)
        val centerOcr = ocrEngine.recognizeRoi(frame, wideDialogRoi, enhance = false)
        if (ResourceParser.isPersonalBreakText(centerOcr.fullText)) {
            return ScreenAnalysis(GameScreenState.PERSONAL_BREAK, detailText = centerOcr.fullText)
        }
        if (ResourceParser.isDisconnectDialogText(centerOcr.fullText)) {
            val reloadBtn = centerOcr.lines.firstOrNull { line ->
                val norm = ResourceParser.normalizeForMatch(line.text)
                norm.contains("reload") || norm.contains("try again") || norm.contains("tai lai") || norm.contains("thu lai")
            }?.center ?: NormPoint(0.50f, 0.62f)
            return ScreenAnalysis(GameScreenState.DISCONNECTED_DIALOG, suggestedTapPoint = reloadBtn, detailText = centerOcr.fullText)
        }

        // Check Welcome Back / Star Bonus / Reward popup BEFORE Home Village so Builder badge behind popup doesn't mask it
        if (ResourceParser.isStarBonusOrRewardPopup(centerOcr.fullText)) {
            val okTap = centerOcr.lines
                .filter { it.bounds.centerY > 0.55f }
                .firstOrNull { ResourceParser.isConfirmDialogButtonText(it.text) }
                ?.center ?: NormPoint(0.508f, 0.825f)
            return ScreenAnalysis(
                GameScreenState.POPUP_DIALOG,
                suggestedTapPoint = okTap,
                detailText = centerOcr.fullText.take(60),
            )
        }

        // 2. Check Battle Summary "Return Home"
        val returnHomeOcr = ocrEngine.recognizeRoi(frame, calibration.returnHomeRoi, enhance = true)
        if (ResourceParser.isReturnHomeText(returnHomeOcr.fullText)) {
            val tap = returnHomeOcr.lines.firstOrNull { ResourceParser.isReturnHomeText(it.text) }?.center
                ?: calibration.returnHomeButton
            return ScreenAnalysis(GameScreenState.BATTLE_SUMMARY, suggestedTapPoint = tap, detailText = returnHomeOcr.fullText)
        }

        // 3. Check Battle Scout ("Next" / "Tiếp theo" visible in wide right-bottom zone)
        val wideNextRoi = com.cocauto.helper.model.NormRect(
            left = minOf(calibration.nextButtonRoi.left, 0.74f),
            top = minOf(calibration.nextButtonRoi.top, 0.58f),
            right = 0.99f,
            bottom = maxOf(calibration.nextButtonRoi.bottom, 0.87f),
        ).normalized()
        val nextOcr = ocrEngine.recognizeRoi(frame, wideNextRoi, enhance = true)
        if (ResourceParser.isNextButtonText(nextOcr.fullText)) {
            val nextTap = nextOcr.lines.firstOrNull { ResourceParser.isNextButtonText(it.text) }?.center
                ?: calibration.nextBaseButton
            return ScreenAnalysis(GameScreenState.BATTLE_SCOUT, suggestedTapPoint = nextTap, detailText = nextOcr.fullText)
        }

        // 4. Check End Battle / Surrender button (wide bottom-left zone) — distinguish BATTLE_SCOUT vs BATTLE_ACTIVE!
        val wideEndRoi = com.cocauto.helper.model.NormRect(
            left = 0.01f,
            top = minOf(calibration.endBattleRoi.top, 0.60f),
            right = maxOf(calibration.endBattleRoi.right, 0.22f),
            bottom = maxOf(calibration.endBattleRoi.bottom, 0.86f),
        ).normalized()
        val endBattleOcr = ocrEngine.recognizeRoi(frame, wideEndRoi, enhance = true)
        if (ResourceParser.isEndBattleText(endBattleOcr.fullText)) {
            val endTap = endBattleOcr.lines.firstOrNull { ResourceParser.isEndBattleText(it.text) }?.center
                ?: calibration.endBattleButton
            // If top-left shows "Available Loot" / "Chiến lợi phẩm" + valid enemy loot numbers, treat as BATTLE_SCOUT
            val topLeftOcr = ocrEngine.recognizeRoi(
                frame,
                com.cocauto.helper.model.NormRect(0.01f, 0.04f, 0.32f, 0.38f),
                enhance = true,
            )
            val parsedLoot = ResourceParser.parseEnemyLootLines(topLeftOcr.lines)
            if (ResourceParser.isScoutBannerText(topLeftOcr.fullText) || (parsedLoot.gold > 0L && parsedLoot.elixir > 0L)) {
                return ScreenAnalysis(
                    GameScreenState.BATTLE_SCOUT,
                    suggestedTapPoint = calibration.nextBaseButton,
                    detailText = "Scout(Loot=${parsedLoot.gold}/${parsedLoot.elixir})",
                )
            }
            return ScreenAnalysis(GameScreenState.BATTLE_ACTIVE, suggestedTapPoint = endTap, detailText = endBattleOcr.fullText)
        }

        // 5. Check 2025/2026 Battle Hub ("Tìm Trận đấu" on left side) & Army Confirmation ("Tấn công!" bottom-right)
        // Check BEFORE Home Village because the right-side Builder badge ("3/3") remains visible behind the slide-out Battle Hub!
        val leftBattleHubOcr = ocrEngine.recognizeRoi(
            frame,
            com.cocauto.helper.model.NormRect(0.06f, 0.62f, 0.38f, 0.86f),
            enhance = false,
        )
        if (ResourceParser.isAttackMenuText(leftBattleHubOcr.fullText)) {
            val btn = leftBattleHubOcr.lines.firstOrNull { ResourceParser.isAttackMenuText(it.text) }?.center
                ?: calibration.findMatchButton
            return ScreenAnalysis(GameScreenState.ATTACK_MENU, suggestedTapPoint = btn, detailText = leftBattleHubOcr.fullText)
        }

        val armyConfirmOcr = ocrEngine.recognizeRoi(
            frame,
            com.cocauto.helper.model.NormRect(0.68f, 0.82f, 0.94f, 0.96f),
            enhance = false,
        )
        if (ResourceParser.isAttackMenuText(armyConfirmOcr.fullText)) {
            val btn = armyConfirmOcr.lines.firstOrNull { ResourceParser.isAttackMenuText(it.text) }?.center
                ?: calibration.armyConfirmAttackButton
            return ScreenAnalysis(GameScreenState.ATTACK_MENU, suggestedTapPoint = btn, detailText = armyConfirmOcr.fullText)
        }

        if (ResourceParser.isAttackMenuText(centerOcr.fullText)) {
            val btn = centerOcr.lines.firstOrNull { ResourceParser.isAttackMenuText(it.text) }?.center
                ?: calibration.findMatchButton
            return ScreenAnalysis(GameScreenState.ATTACK_MENU, suggestedTapPoint = btn, detailText = centerOcr.fullText)
        }

        // 6. Check Home Village via Builder badge ("0/2", bottom-right in 2025/2026 UI) or Attack label ("CHIẾN ĐẤU" / "ATTACK")
        val builderOcr = ocrEngine.recognizeRoi(frame, calibration.builderBadgeRoi, enhance = true)
        val builders = ResourceParser.parseBuilderStatus(builderOcr.fullText)
        if (builders != null) {
            return ScreenAnalysis(GameScreenState.HOME_VILLAGE, detailText = "${builders.freeBuilders}/${builders.totalBuilders}")
        }

        val attackBtnOcr = ocrEngine.recognizeRoi(frame, calibration.homeAttackLabelRoi, enhance = true)
        if (ResourceParser.isHomeAttackButtonText(attackBtnOcr.fullText)) {
            return ScreenAnalysis(GameScreenState.HOME_VILLAGE, detailText = attackBtnOcr.fullText)
        }

        return ScreenAnalysis(GameScreenState.UNKNOWN, detailText = centerOcr.fullText.take(60))
    }

    suspend fun readHomeResources(frame: Bitmap, calibration: CalibrationProfile): HomeResources {
        val goldOcr = ocrEngine.recognizeRoi(frame, calibration.homeGoldRoi, enhance = true)
        val elixirOcr = ocrEngine.recognizeRoi(frame, calibration.homeElixirRoi, enhance = true)
        val darkOcr = ocrEngine.recognizeRoi(frame, calibration.homeDarkElixirRoi, enhance = true)
        return HomeResources(
            gold = ResourceParser.parseResourceNumber(goldOcr.fullText) ?: 0L,
            elixir = ResourceParser.parseResourceNumber(elixirOcr.fullText) ?: 0L,
            darkElixir = ResourceParser.parseResourceNumber(darkOcr.fullText) ?: 0L,
        )
    }

    fun readStorageFillLevels(frame: Bitmap, calibration: CalibrationProfile): StorageFillLevels {
        return StorageFillLevels(
            goldPercent = ImageAnalyzer.measureStorageBarFillPercent(frame, calibration.homeGoldRoi, CurrencyType.GOLD),
            elixirPercent = ImageAnalyzer.measureStorageBarFillPercent(frame, calibration.homeElixirRoi, CurrencyType.ELIXIR),
            darkElixirPercent = ImageAnalyzer.measureStorageBarFillPercent(frame, calibration.homeDarkElixirRoi, CurrencyType.DARK_ELIXIR),
        )
    }

    suspend fun readBuilderStatus(frame: Bitmap, calibration: CalibrationProfile): BuilderStatus {
        val builderOcr = ocrEngine.recognizeRoi(frame, calibration.builderBadgeRoi, enhance = true)
        ResourceParser.parseStrictBuilderStatus(builderOcr.lines)?.let { return it }

        // Fallback: slightly widened crop around builderBadgeButton (strictly taking leftmost X/Y so Lab 1/1 is ignored)
        val btn = calibration.builderBadgeButton
        val fallbackRoi = com.cocauto.helper.model.NormRect(
            left = (btn.x - 0.055f).coerceIn(0.01f, 0.92f),
            top = (btn.y - 0.050f).coerceIn(0.01f, 0.92f),
            right = (btn.x + 0.045f).coerceIn(0.08f, 0.99f),
            bottom = (btn.y + 0.045f).coerceIn(0.08f, 0.99f),
        ).normalized()
        val fallbackOcr = ocrEngine.recognizeRoi(frame, fallbackRoi, enhance = true)
        return ResourceParser.parseStrictBuilderStatus(fallbackOcr.lines) ?: BuilderStatus()
    }

    /**
     * Reads Laboratory availability badge ("0/1" busy vs "1/1" idle) adjacent to Builder badge in 2026 UI.
     */
    suspend fun readLabStatus(frame: Bitmap, calibration: CalibrationProfile): BuilderStatus {
        val pt = calibration.labShortcutPoint
        val labRoi = com.cocauto.helper.model.NormRect(
            left = (pt.x - 0.040f).coerceIn(0.01f, 0.92f),
            top = (pt.y - 0.045f).coerceIn(0.01f, 0.92f),
            right = (pt.x + 0.045f).coerceIn(0.08f, 0.995f),
            bottom = (pt.y + 0.045f).coerceIn(0.08f, 0.995f),
        ).normalized()
        val ocr = ocrEngine.recognizeRoi(frame, labRoi, enhance = true)
        return ResourceParser.parseBuilderStatus(ocr.fullText) ?: BuilderStatus()
    }

    suspend fun readEnemyLoot(frame: Bitmap, calibration: CalibrationProfile): LootReading {
        val lootOcr = ocrEngine.recognizeRoi(frame, calibration.enemyLootRoi, enhance = true)
        return ResourceParser.parseEnemyLootLines(lootOcr.lines)
    }

    companion object {
        const val COC_PACKAGE = "com.supercell.clashofclans"
    }
}
