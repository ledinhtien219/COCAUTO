package com.cocauto.helper.vision

import android.graphics.Bitmap
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.CurrencyType
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.HomeResources
import com.cocauto.helper.model.LootReading
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import com.cocauto.helper.model.StorageFillLevels
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class ScreenAnalysis(
    val state: GameScreenState,
    val suggestedTapPoint: NormPoint? = null,
    val detailText: String = "",
    val preParsedLoot: LootReading? = null,
    val fullLines: List<OcrTextLine> = emptyList(),
)

/**
 * High-speed classifier for live Clash of Clans screen frames using lightweight HSV checks
 * + SINGLE-PASS full-screen ML Kit OCR (~110ms instead of 8 sequential OCRs ~1,400ms!).
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

        // Fast SINGLE full-frame OCR pass — classifies every game screen in ~100-140ms!
        val fullOcr = ocrEngine.recognizeRoi(frame, NormRect(0f, 0f, 1f, 1f), enhance = false)
        classifyFromLines(fullOcr.lines, calibration)?.let { return it }

        // Lightweight parallel fallback ONLY when single pass couldn't classify (e.g. heavily stylized text)
        return coroutineScope {
            val builderDeferred = async { ocrEngine.recognizeRoi(frame, calibration.builderBadgeRoi, enhance = true) }
            val nextDeferred = async { ocrEngine.recognizeRoi(frame, calibration.nextButtonRoi, enhance = true) }
            val returnDeferred = async { ocrEngine.recognizeRoi(frame, calibration.returnHomeRoi, enhance = true) }

            val ret = returnDeferred.await()
            if (ResourceParser.isReturnHomeText(ret.fullText)) {
                val tap = ret.lines.firstOrNull { ResourceParser.isReturnHomeText(it.text) }?.center
                    ?: calibration.returnHomeButton
                return@coroutineScope ScreenAnalysis(GameScreenState.BATTLE_SUMMARY, tap, ret.fullText, fullLines = fullOcr.lines)
            }

            val nxt = nextDeferred.await()
            if (ResourceParser.isNextButtonText(nxt.fullText)) {
                val tap = nxt.lines.firstOrNull { ResourceParser.isNextButtonText(it.text) }?.center
                    ?: calibration.nextBaseButton
                return@coroutineScope ScreenAnalysis(GameScreenState.BATTLE_SCOUT, tap, nxt.fullText, fullLines = fullOcr.lines)
            }

            val bld = builderDeferred.await()
            val builders = ResourceParser.parseBuilderStatus(bld.fullText)
            if (builders != null) {
                return@coroutineScope ScreenAnalysis(
                    GameScreenState.HOME_VILLAGE,
                    detailText = "${builders.freeBuilders}/${builders.totalBuilders}",
                    fullLines = fullOcr.lines,
                )
            }

            ScreenAnalysis(GameScreenState.UNKNOWN, detailText = fullOcr.fullText.take(60), fullLines = fullOcr.lines)
        }
    }

    private fun classifyFromLines(
        lines: List<OcrTextLine>,
        calibration: CalibrationProfile,
    ): ScreenAnalysis? {
        if (lines.isEmpty()) return null

        // 1. Center dialogs (Personal Break, Disconnect/Reload, Welcome Back, Treasure Chest Reward, Star Bonus)
        val centerLines = lines.filter { it.bounds.centerX in 0.16f..0.84f && it.bounds.centerY in 0.05f..0.95f }
        val centerText = centerLines.joinToString(" ") { it.text }
        if (ResourceParser.isPersonalBreakText(centerText)) {
            return ScreenAnalysis(GameScreenState.PERSONAL_BREAK, detailText = centerText.take(60), fullLines = lines)
        }
        if (ResourceParser.isDisconnectDialogText(centerText)) {
            val reloadBtn = centerLines.firstOrNull { line ->
                val norm = ResourceParser.normalizeForMatch(line.text)
                norm.contains("reload") || norm.contains("try again") || norm.contains("tai lai") || norm.contains("thu lai")
            }?.center ?: NormPoint(0.50f, 0.62f)
            return ScreenAnalysis(GameScreenState.DISCONNECTED_DIALOG, reloadBtn, centerText.take(60), fullLines = lines)
        }
        if (ResourceParser.isStarBonusOrRewardPopup(centerText)) {
            val okTap = centerLines
                .filter { it.bounds.centerY > 0.55f }
                .firstOrNull { ResourceParser.isConfirmDialogButtonText(it.text) }
                ?.center ?: NormPoint(0.508f, 0.825f)
            return ScreenAnalysis(GameScreenState.POPUP_DIALOG, okTap, centerText.take(60), fullLines = lines)
        }

        // 2. Battle Summary ("Về làng" / "Return Home" at bottom-center)
        val returnLine = lines.firstOrNull {
            it.bounds.centerX in 0.30f..0.70f &&
                it.bounds.centerY > 0.76f &&
                ResourceParser.isReturnHomeText(it.text)
        }
        if (returnLine != null) {
            return ScreenAnalysis(GameScreenState.BATTLE_SUMMARY, returnLine.center, returnLine.text, fullLines = lines)
        }

        // 3. Battle Scout ("Tiếp theo" / "Next" at bottom-right) — also pre-parse top-left enemy loot in 0ms!
        val nextLine = lines.firstOrNull {
            it.bounds.centerX > 0.74f &&
                it.bounds.centerY in 0.58f..0.86f &&
                ResourceParser.isNextButtonText(it.text)
        }
        if (nextLine != null) {
            val topLeftLootLines = lines.filter { it.bounds.centerX < 0.30f && it.bounds.centerY in 0.08f..0.36f }
            val preLoot = ResourceParser.parseEnemyLootLines(topLeftLootLines).takeIf { it.isValid }
            return ScreenAnalysis(
                state = GameScreenState.BATTLE_SCOUT,
                suggestedTapPoint = nextLine.center,
                detailText = nextLine.text,
                preParsedLoot = preLoot,
                fullLines = lines,
            )
        }

        // 4. End Battle / Surrender ("Kết thúc Trận chiến" / "Đầu hàng" at bottom-left)
        val endLine = lines.firstOrNull {
            it.bounds.centerX < 0.24f &&
                it.bounds.centerY in 0.60f..0.86f &&
                ResourceParser.isEndBattleText(it.text)
        }
        if (endLine != null) {
            val topLeftLines = lines.filter { it.bounds.centerX < 0.30f && it.bounds.centerY in 0.07f..0.36f }
            val topLeftText = topLeftLines.joinToString(" ") { it.text }
            val parsedLoot = ResourceParser.parseEnemyLootLines(topLeftLines)
            val isSurrenderActive = ResourceParser.normalizeForMatch(endLine.text).contains("dau hang") ||
                ResourceParser.normalizeForMatch(endLine.text).contains("surrender")
            val hasBattleTimerBanner = lines.any {
                it.bounds.centerY < 0.14f &&
                    ResourceParser.normalizeForMatch(it.text).contains("ket thuc sau")
            }
            if (!isSurrenderActive && !hasBattleTimerBanner &&
                (ResourceParser.isScoutBannerText(topLeftText) || (parsedLoot.gold > 0L && parsedLoot.elixir > 0L))
            ) {
                return ScreenAnalysis(
                    state = GameScreenState.BATTLE_SCOUT,
                    suggestedTapPoint = calibration.nextBaseButton,
                    detailText = "Scout(Loot=${parsedLoot.gold}/${parsedLoot.elixir})",
                    preParsedLoot = parsedLoot.takeIf { it.isValid },
                    fullLines = lines,
                )
            }
            return ScreenAnalysis(GameScreenState.BATTLE_ACTIVE, endLine.center, endLine.text, fullLines = lines)
        }

        // 5. 2026 Battle Hub ("Tìm Trận đấu" on left side) & Army Confirmation ("Tấn công!" bottom-right)
        val attackHubLine = lines.firstOrNull { line ->
            val inLeftHub = line.bounds.centerX in 0.05f..0.44f && line.bounds.centerY in 0.58f..0.88f
            val inArmyConfirm = line.bounds.centerX > 0.66f && line.bounds.centerY > 0.80f
            (inLeftHub || inArmyConfirm) && ResourceParser.isAttackMenuText(line.text)
        }
        if (attackHubLine != null) {
            return ScreenAnalysis(GameScreenState.ATTACK_MENU, attackHubLine.center, attackHubLine.text, fullLines = lines)
        }

        // 6. Home Village via bottom-right Builder badge ("0/3", "3/3") or bottom-left "CHIẾN ĐẤU"
        val builderLine = lines
            .filter { it.bounds.centerX > 0.72f && it.bounds.centerY > 0.64f }
            .sortedBy { it.bounds.left }
            .firstOrNull { ResourceParser.parseBuilderStatus(it.text) != null }
        if (builderLine != null) {
            val b = ResourceParser.parseBuilderStatus(builderLine.text)!!
            return ScreenAnalysis(GameScreenState.HOME_VILLAGE, detailText = "${b.freeBuilders}/${b.totalBuilders}", fullLines = lines)
        }

        val homeAttackLine = lines.firstOrNull {
            it.bounds.centerX < 0.24f && it.bounds.centerY > 0.78f && ResourceParser.isHomeAttackButtonText(it.text)
        }
        if (homeAttackLine != null) {
            return ScreenAnalysis(GameScreenState.HOME_VILLAGE, detailText = homeAttackLine.text, fullLines = lines)
        }

        return null
    }

    suspend fun readHomeResources(frame: Bitmap, calibration: CalibrationProfile): HomeResources {
        // Fast single combined crop covering Gold + Elixir + Dark Elixir in 1 OCR call instead of 3!
        val combinedRoi = NormRect(
            left = minOf(calibration.homeGoldRoi.left, calibration.homeElixirRoi.left).coerceIn(0.66f, 0.85f),
            top = (calibration.homeGoldRoi.top - 0.008f).coerceIn(0.005f, 0.10f),
            right = maxOf(calibration.homeGoldRoi.right, calibration.homeElixirRoi.right).coerceIn(0.88f, 0.99f),
            bottom = (calibration.homeDarkElixirRoi.bottom + 0.010f).coerceIn(0.18f, 0.30f),
        ).normalized()

        val combinedOcr = ocrEngine.recognizeRoi(frame, combinedRoi, enhance = true)
        val nums = combinedOcr.lines
            .filter { line ->
                val norm = ResourceParser.normalizeForMatch(line.text)
                !norm.contains("ph") && !norm.contains("gio") && !norm.contains("gi") && !norm.contains("ng")
            }
            .sortedBy { it.bounds.centerY }
            .mapNotNull { line -> ResourceParser.parseResourceNumber(line.text)?.let { line.bounds.centerY to it } }

        val goldY = calibration.homeGoldRoi.centerY
        val elixirY = calibration.homeElixirRoi.centerY
        val darkY = calibration.homeDarkElixirRoi.centerY

        val gold = nums.minByOrNull { kotlin.math.abs(it.first - goldY) }
            ?.takeIf { kotlin.math.abs(it.first - goldY) <= 0.045f }?.second ?: 0L
        val elixir = nums.minByOrNull { kotlin.math.abs(it.first - elixirY) }
            ?.takeIf { kotlin.math.abs(it.first - elixirY) <= 0.045f }?.second ?: 0L
        val dark = nums.minByOrNull { kotlin.math.abs(it.first - darkY) }
            ?.takeIf { kotlin.math.abs(it.first - darkY) <= 0.045f }?.second ?: 0L

        if (gold > 0L || elixir > 0L) {
            return HomeResources(gold = gold, elixir = elixir, darkElixir = dark)
        }

        // Parallel fallback if combined crop missed
        return coroutineScope {
            val gDef = async { ocrEngine.recognizeRoi(frame, calibration.homeGoldRoi, enhance = true) }
            val eDef = async { ocrEngine.recognizeRoi(frame, calibration.homeElixirRoi, enhance = true) }
            val dDef = async { ocrEngine.recognizeRoi(frame, calibration.homeDarkElixirRoi, enhance = true) }
            HomeResources(
                gold = ResourceParser.parseResourceNumber(gDef.await().fullText) ?: 0L,
                elixir = ResourceParser.parseResourceNumber(eDef.await().fullText) ?: 0L,
                darkElixir = ResourceParser.parseResourceNumber(dDef.await().fullText) ?: 0L,
            )
        }
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

        val btn = calibration.builderBadgeButton
        val fallbackRoi = NormRect(
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
        val labRoi = NormRect(
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
