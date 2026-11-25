package com.cocauto.helper.engine

import com.cocauto.helper.data.ConfigStorage
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.LootReading
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.vision.AutoCalibrationEngine
import com.cocauto.helper.vision.AutoTroopBarDetector
import com.cocauto.helper.vision.ImageAnalyzer
import com.cocauto.helper.vision.ResourceParser

/**
 * Automates Home Village farming:
 * - Camera zoom-out standardization
 * - Smart collector bubble & Loot Cart harvesting
 * - Matchmaking (Clash Anytime compatible), automatic Battle UI self-calibration, ML Kit OCR enemy loot filtering
 * - Multi-strategy troop/hero/spell deployment & battle completion monitoring
 */
class HomeFarmEngine {
    private var autoCalibratedBattleThisSession = false
    private var zoomedHomeThisSession = false
    private var collectCycleCounter = 0

    suspend fun collectHomeResources(ctx: BotExecutionContext) {
        val cfg = ctx.config.homeFarm
        if (!cfg.autoCollectResources && !cfg.collectLootCart) return

        // Harvest on 1st cycle and every 3rd cycle thereafter (mines don't refill in 60s -> saves ~5s/battle!)
        val shouldCollectThisCycle = (collectCycleCounter % 3 == 0)
        collectCycleCounter++
        if (!shouldCollectThisCycle) return

        BotStateStore.setAction("Thu hoạch nhanh mỏ tài nguyên", GameScreenState.HOME_VILLAGE)
        if (!zoomedHomeThisSession) {
            ctx.zoomOutCamera()
            zoomedHomeThisSession = true
            ctx.sleep(120L)
        }

        if (cfg.autoCollectResources) {
            val bubblePoints = ctx.withFrame { frame ->
                ImageAnalyzer.findCollectorBubblePoints(frame, maxPoints = 8)
            }.orEmpty()

            if (bubblePoints.isNotEmpty()) {
                BotStateStore.log("Thu hoạch nhanh ${bubblePoints.size} bong bóng tài nguyên...", LogLevel.INFO)
                val steps = bubblePoints.map { NormTouchStep(it, holdMs = 35L, pauseAfterMs = 55L) }
                ctx.executeNormalizedBatch(steps)
            }
        }

        if (cfg.collectLootCart) {
            val lootSteps = listOf(
                NormTouchStep(ctx.calibration.lootCartPoint, holdMs = 40L, pauseAfterMs = 260L),
                NormTouchStep(NormPoint(0.50f, 0.72f), holdMs = 40L, pauseAfterMs = 150L),
                NormTouchStep(ctx.calibration.dismissAreaPoint, holdMs = 35L, pauseAfterMs = 50L),
            )
            ctx.executeNormalizedBatch(lootSteps)
        }
    }

    suspend fun runAttackCycle(ctx: BotExecutionContext, alreadyInAttackMenu: Boolean = false) {
        val cfg = ctx.config.homeFarm
        if (!cfg.autoAttack) return

        if (!alreadyInAttackMenu) {
            BotStateStore.setAction("Mở menu Chiến đấu", GameScreenState.HOME_VILLAGE)
            ctx.tap(ctx.calibration.homeAttackButton, delayAfter = false)
            ctx.sleep(420L)
        }

        // Single-pass OCR navigation: Battle Hub ("Tìm Trận đấu") -> Army Confirm ("Tấn công!") -> Shield dialog -> Clouds
        for (step in 1..4) {
            ctx.ensureRunning()
            val menuResult = ctx.withFrame { frame ->
                val analysis = ctx.screenDetector.analyzeScreen(
                    frame,
                    ctx.calibration,
                    BotStateStore.runtime.value.foregroundPackage,
                )
                if (analysis.state == GameScreenState.SEARCHING_CLOUDS || analysis.state == GameScreenState.BATTLE_SCOUT) {
                    return@withFrame Triple(analysis.state, "", null as NormPoint?)
                }

                // Reuse lines already recognized by single-pass analyzeScreen (ZERO second OCR pass!)
                val lines = analysis.fullLines
                val fullText = lines.joinToString(" ") { it.text }

                // 1. Handle Shield Warning confirmation dialog ("Khiên / Shield" -> tap "OK" / "Đồng ý")
                val centerDialogText = lines
                    .filter { it.bounds.centerX in 0.18f..0.82f && it.bounds.centerY in 0.20f..0.80f }
                    .joinToString(" ") { it.text }
                if (ResourceParser.isShieldWarningDialogText(centerDialogText)) {
                    val confirmBtn = lines
                        .filter { it.bounds.centerY > 0.48f }
                        .firstOrNull {
                            ResourceParser.isConfirmDialogButtonText(it.text) ||
                                ResourceParser.normalizeForMatch(it.text).contains("tan cong") ||
                                ResourceParser.normalizeForMatch(it.text).contains("attack")
                        }
                    return@withFrame Triple(analysis.state, "Xác nhận tắt Khiên", confirmBtn?.center ?: NormPoint(0.585f, 0.635f))
                }

                // 2. Find "Tìm Trận đấu" / "Find a Match" or "Tấn công!" directly from fullLines
                val lowerLines = lines.filter { it.bounds.centerY > 0.44f }
                val findLine = lowerLines.firstOrNull { line ->
                    val n = ResourceParser.normalizeForMatch(line.text)
                    n.contains("tim tran") || n.contains("find a match") || n.contains("tim doi thu") || n.contains("find now")
                } ?: lowerLines.firstOrNull { line ->
                    ResourceParser.isAttackMenuText(line.text)
                } ?: lines.firstOrNull { line ->
                    ResourceParser.isAttackMenuText(line.text)
                }

                // On Army Confirm ("Quân đội của tôi") screen, abort ONLY if army is literally empty (0/N)!
                val isArmyConfirmStep = findLine != null && (
                    ResourceParser.normalizeForMatch(findLine.text).contains("tan cong") ||
                        ResourceParser.normalizeForMatch(findLine.text).startsWith("attack")
                    )
                if (isArmyConfirmStep) {
                    val armyCap = ResourceParser.parseArmyCampCapacity(lines)
                    if (armyCap != null && armyCap.first <= 0) {
                        return@withFrame Triple(analysis.state, "EMPTY_ARMY:${armyCap.first}/${armyCap.second}", null as NormPoint?)
                    }
                }

                Triple(analysis.state, findLine?.text?.trim().orEmpty(), findLine?.center)
            }

            if (menuResult == null) break
            val (state, label, tapPt) = menuResult
            if (label.startsWith("EMPTY_ARMY:")) {
                val capStr = label.removePrefix("EMPTY_ARMY:")
                BotStateStore.log("Trại lính đang trống ($capStr) — tạm chờ có quân rồi chiến!", LogLevel.WARN)
                BotStateStore.setAction("Chưa có quân ($capStr) — chờ đủ quân", GameScreenState.ATTACK_MENU)
                ctx.tap(NormPoint(0.945f, 0.065f), delayAfter = false)
                ctx.sleep(500L)
                return
            }
            if (state == GameScreenState.SEARCHING_CLOUDS || state == GameScreenState.BATTLE_SCOUT) {
                break
            }

            if (tapPt != null) {
                BotStateStore.setAction("Bấm '$label' (bước $step)", GameScreenState.ATTACK_MENU)
                BotStateStore.log("Menu Chiến đấu -> bấm '$label'", LogLevel.INFO)
                ctx.tap(tapPt, delayAfter = false)
                val normLabel = ResourceParser.normalizeForMatch(label)
                if (normLabel.contains("tan cong") || normLabel == "attack!") {
                    // Tapping final "Tấn công!" enters matchmaking immediately — don't waste another menu loop!
                    ctx.sleep(650L)
                    break
                }
                ctx.sleep(480L)
            } else if (step == 1) {
                BotStateStore.setAction("Bấm Tìm trận (tọa độ chuẩn)", GameScreenState.ATTACK_MENU)
                ctx.tap(ctx.calibration.findMatchButton, delayAfter = false)
                ctx.sleep(480L)
            } else {
                break
            }
        }

        handleCurrentScoutAndAttack(ctx)
    }

    /**
     * Evaluates enemy base(s) starting from Battle Scout / Clouds, deploys available troops once
     * a base matches loot thresholds, and monitors battle completion until Return Home.
     */
    suspend fun handleCurrentScoutAndAttack(ctx: BotExecutionContext) {
        val cfg = ctx.config.homeFarm
        val matchedLoot = searchForTargetBase(ctx) ?: return

        BotStateStore.setAction("Đang đánh nhà mục tiêu!", GameScreenState.BATTLE_ACTIVE)
        val attackReason = if (cfg.attackWhenArmyReady) {
            "Đủ quân — chiến ngay! (Vàng ${formatK(matchedLoot.gold)} | Dầu ${formatK(matchedLoot.elixir)} | DE ${matchedLoot.darkElixir})"
        } else {
            "Tìm thấy nhà đạt chuẩn: Vàng ${formatK(matchedLoot.gold)} | Dầu ${formatK(matchedLoot.elixir)} | DE ${matchedLoot.darkElixir} — tự đánh!"
        }
        BotStateStore.log(attackReason, LogLevel.SUCCESS)
        DeployStrategyEngine.executeHomeAttack(ctx)

        monitorAndFinishBattle(ctx, matchedLoot)

        if (cfg.postBattleCooldownSec > 0) {
            ctx.sleep(cfg.postBattleCooldownSec * 1_000L)
        }
    }

    private suspend fun searchForTargetBase(ctx: BotExecutionContext): LootReading? {
        val cfg = ctx.config.homeFarm
        val maxNexts = cfg.maxNextCount.coerceIn(1, 200)

        for (attempt in 1..maxNexts) {
            ctx.ensureRunning()
            BotStateStore.setAction("Đang tìm nhà #$attempt/$maxNexts...", GameScreenState.SEARCHING_CLOUDS)

            val scoutAnalysis = waitUntilScoutReady(ctx, timeoutMs = 28_000L)
            if (scoutAnalysis == null) {
                BotStateStore.log("Quá thời gian chờ mây / không vào được màn hình soi nhà.", LogLevel.WARN)
                return null
            }

            // Zero-extra-capture Battle Auto-Calibration from the OCR lines we ALREADY have!
            if (!autoCalibratedBattleThisSession && ctx.config.safety.autoCalibrateScreen && scoutAnalysis.fullLines.isNotEmpty()) {
                val (sw, sh) = ctx.bridgeManager.activeAdbBridge()?.lastCapturedPhysicalSize() ?: (2400 to 1080)
                val calResult = AutoCalibrationEngine.applyDetectedOcr(ctx.calibration, scoutAnalysis.fullLines, sw, sh)
                if (calResult.hasDetections) {
                    autoCalibratedBattleThisSession = true
                    BotStateStore.updateCalibration(calResult.profile)
                    ConfigStorage(ctx.appContext).saveCalibration(calResult.profile)
                    BotStateStore.log("Tự nhận diện màn đánh: ${calResult.detectedItems.joinToString(", ")}", LogLevel.SUCCESS)
                }
            }

            val latestCal = ctx.calibration

            // FAST MODE ("Đủ quân là chiến ngay"): skip all loot/storage/Next conditions and attack Base #1 immediately!
            if (cfg.attackWhenArmyReady) {
                val quickLoot = scoutAnalysis.preParsedLoot ?: LootReading()
                val liveCards = ctx.withFrame { frame ->
                    AutoTroopBarDetector.detectAvailableCards(frame, latestCal, ctx.ocrEngine)
                }.orEmpty()
                if (liveCards.isEmpty()) {
                    BotStateStore.log("Không thấy quân trên thanh thả lính — quay về làng chờ có quân.", LogLevel.WARN)
                    ctx.tap(latestCal.endBattleButton, delayAfter = false)
                    ctx.sleep(900L)
                    return null
                }
                BotStateStore.updateStats { it.copy(lastScannedLoot = quickLoot) }
                BotStateStore.setAction(
                    "Đủ quân (${liveCards.size} ô) — chiến ngay nhà #$attempt!",
                    GameScreenState.BATTLE_SCOUT,
                )
                return quickLoot
            }

            // Instant zero-extra-capture loot check when pre-parsed from scout frame; fallback OCR only if missing!
            val loot = scoutAnalysis.preParsedLoot?.takeIf { it.isValid } ?: ctx.withFrame { frame ->
                val primary = ctx.screenDetector.readEnemyLoot(frame, latestCal)
                if (primary.isValid) {
                    primary
                } else {
                    val fallbackOcr = ctx.ocrEngine.recognizeRoi(
                        frame = frame,
                        roi = com.cocauto.helper.model.NormRect(0.01f, 0.095f, 0.28f, 0.36f),
                        enhance = true,
                    )
                    ResourceParser.parseEnemyLootLines(fallbackOcr.lines)
                }
            } ?: LootReading()

            BotStateStore.updateStats { it.copy(lastScannedLoot = loot) }
            BotStateStore.setAction(
                "Nhà #$attempt: V=${formatK(loot.gold)} D=${formatK(loot.elixir)} DE=${loot.darkElixir}",
                GameScreenState.BATTLE_SCOUT,
            )
            BotStateStore.log(
                "Soi nhà #$attempt: Vàng ${formatK(loot.gold)} | Dầu ${formatK(loot.elixir)} | DE ${loot.darkElixir}",
                LogLevel.INFO,
            )

            val matched = ResourceParser.matchesLootFilter(loot, cfg)
            if (matched) {
                return loot
            }

            if (attempt == maxNexts) {
                return if (cfg.attackWhenMaxNextReached) {
                    BotStateStore.log("Đạt giới hạn $maxNexts lượt Next — tự động đánh luôn nhà hiện tại.", LogLevel.WARN)
                    loot
                } else {
                    BotStateStore.log("Đạt $maxNexts lượt Next không đạt ngưỡng — quay về nhà chính.", LogLevel.INFO)
                    ctx.tap(latestCal.endBattleButton, delayAfter = false)
                    ctx.sleep(900L)
                    null
                }
            }

            BotStateStore.updateStats { it.copy(basesSkipped = it.basesSkipped + 1) }
            val nextTap = scoutAnalysis.suggestedTapPoint ?: latestCal.nextBaseButton
            ctx.tap(nextTap, delayAfter = false)
            ctx.sleep(550L)
        }
        return null
    }

    private suspend fun waitUntilScoutReady(
        ctx: BotExecutionContext,
        timeoutMs: Long,
    ): com.cocauto.helper.vision.ScreenAnalysis? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ctx.ensureRunning()
            val analysis = ctx.withFrame { frame ->
                ctx.screenDetector.analyzeScreen(frame, ctx.calibration, BotStateStore.runtime.value.foregroundPackage)
            }
            when (analysis?.state) {
                GameScreenState.BATTLE_SCOUT, GameScreenState.BATTLE_ACTIVE -> return analysis
                GameScreenState.HOME_VILLAGE -> return null
                GameScreenState.DISCONNECTED_DIALOG -> {
                    analysis.suggestedTapPoint?.let { ctx.tap(it, delayAfter = false) }
                    return null
                }
                else -> ctx.sleep(260L)
            }
        }
        return null
    }

    private suspend fun monitorAndFinishBattle(ctx: BotExecutionContext, targetLoot: LootReading) {
        val cfg = ctx.config.homeFarm
        val cal = ctx.calibration
        val startMs = System.currentTimeMillis()
        val maxDurationMs = cfg.maxBattleDurationSec.coerceIn(20, 210) * 1_000L
        var emptyTroopsSinceMs: Long? = null

        while (System.currentTimeMillis() - startMs < maxDurationMs) {
            ctx.ensureRunning()
            val elapsedSec = ((System.currentTimeMillis() - startMs) / 1000L).toInt()
            BotStateStore.setAction("Đang theo dõi trận đánh (${elapsedSec}s/${cfg.maxBattleDurationSec}s)", GameScreenState.BATTLE_ACTIVE)

            val analysis = ctx.withFrame { frame ->
                ctx.screenDetector.analyzeScreen(frame, cal, BotStateStore.runtime.value.foregroundPackage)
            }

            when (analysis?.state) {
                GameScreenState.BATTLE_SUMMARY -> {
                    BotStateStore.setAction("Đánh xong — quay về làng ngay!", GameScreenState.BATTLE_SUMMARY)
                    BotStateStore.log("Trận đánh kết thúc — bấm Về làng ngay để chạy chu kỳ tiếp theo!", LogLevel.SUCCESS)
                    returnHomeAndConfirmVillage(ctx, analysis.suggestedTapPoint ?: cal.returnHomeButton)
                    recordAttackSuccess(targetLoot)
                    return
                }

                GameScreenState.HOME_VILLAGE -> {
                    recordAttackSuccess(targetLoot)
                    return
                }

                GameScreenState.POPUP_DIALOG -> {
                    // Reward chest / Star bonus popped up immediately at end of battle
                    ctx.tap(analysis.suggestedTapPoint ?: NormPoint(0.50f, 0.88f), delayAfter = false)
                    ctx.sleep(450L)
                }

                GameScreenState.DISCONNECTED_DIALOG -> {
                    analysis.suggestedTapPoint?.let { ctx.tap(it, delayAfter = false) }
                    ctx.sleep(2_500L)
                    return
                }

                else -> {
                    // Optional early surrender once all troop slots turned grayscale
                    if (cfg.earlySurrenderWhenTroopsEmpty && elapsedSec >= 8) {
                        val allEmpty = DeployStrategyEngine.areAllTroopSlotsEmpty(ctx)
                        if (allEmpty) {
                            val firstEmpty = emptyTroopsSinceMs ?: System.currentTimeMillis().also { emptyTroopsSinceMs = it }
                            if (System.currentTimeMillis() - firstEmpty >= cfg.earlySurrenderWaitSec * 1_000L) {
                                BotStateStore.log("Đã hết quân sau ${cfg.earlySurrenderWaitSec}s — kết thúc sớm đánh trận mới.", LogLevel.INFO)
                                triggerSurrenderAndReturn(ctx)
                                recordAttackSuccess(targetLoot)
                                return
                            }
                        } else {
                            emptyTroopsSinceMs = null
                        }
                    }
                    ctx.sleep(700L)
                }
            }
        }

        // Max battle time reached — surrender cleanly and return home
        BotStateStore.log("Đạt thời gian tối đa trận (${cfg.maxBattleDurationSec}s) — quay về nhà chính.", LogLevel.INFO)
        triggerSurrenderAndReturn(ctx)
        recordAttackSuccess(targetLoot)
    }

    private suspend fun triggerSurrenderAndReturn(ctx: BotExecutionContext) {
        val cal = ctx.calibration
        ctx.tap(cal.endBattleButton, delayAfter = false)
        ctx.sleep(500L)

        // Dynamically locate "Okay" / "Đồng ý" / "OK" button in surrender confirmation dialog via OCR
        val confirmTap = ctx.withFrame { frame ->
            val dialogOcr = ctx.ocrEngine.recognizeRoi(frame, cal.dialogCenterRoi, enhance = true)
            dialogOcr.lines.firstOrNull { ResourceParser.isConfirmDialogButtonText(it.text) }?.center
        } ?: cal.surrenderConfirmButton

        ctx.tap(confirmTap, delayAfter = false)
        ctx.sleep(850L)

        val returnTap = ctx.withFrame { frame ->
            val sumOcr = ctx.ocrEngine.recognizeRoi(frame, cal.returnHomeRoi, enhance = true)
            sumOcr.lines.firstOrNull { ResourceParser.isReturnHomeText(it.text) }?.center
        } ?: cal.returnHomeButton

        returnHomeAndConfirmVillage(ctx, returnTap)
    }

    /**
     * Taps "Về làng" ("Return Home") immediately, retries if the summary screen is still animating,
     * auto-dismisses any post-battle Star Bonus / Treasure Chest ("Tiếp tục" / "OK") popup,
     * and returns the instant Home Village is ready so the next farm cycle starts right away!
     */
    private suspend fun returnHomeAndConfirmVillage(
        ctx: BotExecutionContext,
        initialReturnPoint: NormPoint,
    ) {
        val cal = ctx.calibration
        ctx.tap(initialReturnPoint, delayAfter = false)
        ctx.sleep(650L)

        val deadline = System.currentTimeMillis() + 9_000L
        while (System.currentTimeMillis() < deadline) {
            ctx.ensureRunning()
            val analysis = ctx.withFrame { frame ->
                ctx.screenDetector.analyzeScreen(frame, cal, BotStateStore.runtime.value.foregroundPackage)
            }
            when (analysis?.state) {
                GameScreenState.HOME_VILLAGE -> {
                    BotStateStore.setAction("Đã về làng — chạy lệnh tiếp theo!", GameScreenState.HOME_VILLAGE)
                    return
                }

                GameScreenState.BATTLE_SUMMARY -> {
                    // Summary button hadn't finished fade-in on previous tap -> tap "Về làng" again immediately
                    ctx.tap(analysis.suggestedTapPoint ?: cal.returnHomeButton, delayAfter = false)
                    ctx.sleep(600L)
                }

                GameScreenState.POPUP_DIALOG -> {
                    // Dismiss Star Bonus / Chest Reward ("Tiếp tục" / "OK") immediately
                    BotStateStore.setAction("Đóng phần thưởng sau trận...", GameScreenState.POPUP_DIALOG)
                    ctx.tap(analysis.suggestedTapPoint ?: NormPoint(0.50f, 0.88f), delayAfter = false)
                    ctx.sleep(500L)
                }

                GameScreenState.LOADING -> {
                    ctx.sleep(400L)
                }

                else -> {
                    ctx.sleep(450L)
                }
            }
        }
    }

    private fun recordAttackSuccess(targetLoot: LootReading) {
        BotStateStore.updateStats { s ->
            s.copy(
                attacksCompleted = s.attacksCompleted + 1,
                estimatedGoldLooted = s.estimatedGoldLooted + targetLoot.gold,
                estimatedElixirLooted = s.estimatedElixirLooted + targetLoot.elixir,
                estimatedDarkElixirLooted = s.estimatedDarkElixirLooted + targetLoot.darkElixir,
            )
        }
    }

    private fun formatK(value: Long): String = when {
        value >= 1_000_000L -> String.format("%.2fM", value / 1_000_000.0)
        value >= 1_000L -> "${value / 1_000L}K"
        else -> value.toString()
    }
}
