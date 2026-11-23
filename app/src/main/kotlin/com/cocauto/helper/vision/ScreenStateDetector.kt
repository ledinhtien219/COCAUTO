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

        // 1. Check center dialogs (Disconnect / Reload / Personal break / Star Bonus)
        val centerOcr = ocrEngine.recognizeRoi(frame, calibration.dialogCenterRoi, enhance = false)
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

        // 2. Check Battle Summary "Return Home"
        val returnHomeOcr = ocrEngine.recognizeRoi(frame, calibration.returnHomeRoi, enhance = true)
        if (ResourceParser.isReturnHomeText(returnHomeOcr.fullText)) {
            val tap = returnHomeOcr.lines.firstOrNull { ResourceParser.isReturnHomeText(it.text) }?.center
                ?: calibration.returnHomeButton
            return ScreenAnalysis(GameScreenState.BATTLE_SUMMARY, suggestedTapPoint = tap, detailText = returnHomeOcr.fullText)
        }

        // 3. Check Battle Scout ("Next" button visible)
        val nextOcr = ocrEngine.recognizeRoi(frame, calibration.nextButtonRoi, enhance = true)
        if (ResourceParser.isNextButtonText(nextOcr.fullText)) {
            return ScreenAnalysis(GameScreenState.BATTLE_SCOUT, suggestedTapPoint = calibration.nextBaseButton, detailText = nextOcr.fullText)
        }

        // 4. Check Active Battle ("End Battle" / "Surrender" button visible)
        val endBattleOcr = ocrEngine.recognizeRoi(frame, calibration.endBattleRoi, enhance = true)
        if (ResourceParser.isEndBattleText(endBattleOcr.fullText)) {
            return ScreenAnalysis(GameScreenState.BATTLE_ACTIVE, suggestedTapPoint = calibration.endBattleButton, detailText = endBattleOcr.fullText)
        }

        // 5. Check Attack Menu / Army confirmation screen
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

        // 7. Check Star Bonus / Reward popup
        if (ResourceParser.isStarBonusOrRewardPopup(centerOcr.fullText)) {
            return ScreenAnalysis(
                GameScreenState.POPUP_DIALOG,
                suggestedTapPoint = NormPoint(0.50f, 0.72f),
                detailText = centerOcr.fullText,
            )
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
        return ResourceParser.parseBuilderStatus(builderOcr.fullText) ?: BuilderStatus()
    }

    suspend fun readEnemyLoot(frame: Bitmap, calibration: CalibrationProfile): LootReading {
        val lootOcr = ocrEngine.recognizeRoi(frame, calibration.enemyLootRoi, enhance = true)
        return ResourceParser.parseEnemyLootLines(lootOcr.lines)
    }

    companion object {
        const val COC_PACKAGE = "com.supercell.clashofclans"
    }
}
