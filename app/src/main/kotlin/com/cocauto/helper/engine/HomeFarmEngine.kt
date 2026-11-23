package com.cocauto.helper.engine

import com.cocauto.helper.data.ConfigStorage
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.LootReading
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.vision.AutoCalibrationEngine
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

    suspend fun collectHomeResources(ctx: BotExecutionContext) {
        val cfg = ctx.config.homeFarm
        if (!cfg.autoCollectResources && !cfg.collectLootCart) return

        BotStateStore.setAction("Thu hoạch mỏ tài nguyên nhà chính", GameScreenState.HOME_VILLAGE)
        ctx.zoomOutCamera()
        ctx.sleep(300L)

        if (cfg.autoCollectResources) {
            val bubblePoints = ctx.withFrame { frame ->
                ImageAnalyzer.findCollectorBubblePoints(frame, maxPoints = 10)
            }.orEmpty()

            if (bubblePoints.isNotEmpty()) {
                BotStateStore.log("Phát hiện ${bubblePoints.size} bong bóng tài nguyên, đang thu hoạch...", LogLevel.INFO)
                for (pt in bubblePoints) {
                    ctx.tap(pt, delayAfter = false)
                    ctx.sleep(160L)
                }
            } else {
                // Light fallback tap across common inner ring positions if no bubbles popped visually
                val fallbackPoints = listOf(
                    NormPoint(0.32f, 0.36f),
                    NormPoint(0.68f, 0.36f),
                    NormPoint(0.34f, 0.62f),
                    NormPoint(0.66f, 0.62f),
                )
                for (pt in fallbackPoints) {
                    ctx.tap(pt, delayAfter = false)
                    ctx.sleep(150L)
                }
            }
            ctx.tap(ctx.calibration.dismissAreaPoint)
        }

        if (cfg.collectLootCart) {
            BotStateStore.setAction("Kiểm tra Xe cướp tài nguyên (Loot Cart)")
            ctx.tap(ctx.calibration.lootCartPoint)
            ctx.sleep(500L)
            // Tap collect button on Loot Cart dialog if open, otherwise harmless safe area
            ctx.tap(NormPoint(0.50f, 0.72f))
            ctx.sleep(350L)
            ctx.tap(ctx.calibration.dismissAreaPoint)
        }
    }

    suspend fun runAttackCycle(ctx: BotExecutionContext) {
        val cfg = ctx.config.homeFarm
        val cal = ctx.calibration
        if (!cfg.autoAttack) return

        BotStateStore.setAction("Mở menu Chiến đấu", GameScreenState.HOME_VILLAGE)
        ctx.tap(cal.homeAttackButton)
        ctx.sleep(1_000L)

        // OCR-guided navigation through the 2025/2026 Battle Hub & Clash Anytime Army Confirmation screen:
        // Finds exact "Tìm trận" / "Find a Match" / "Tấn công" / "Attack!" text on screen (up to 3 steps)
        // so we never accidentally tap Single Player / Practice / Edit Army.
        for (step in 1..3) {
            ctx.ensureRunning()
            val curState = ctx.withFrame { frame ->
                ctx.screenDetector.analyzeScreen(frame, ctx.calibration, BotStateStore.runtime.value.foregroundPackage)
            }
            if (curState?.state == GameScreenState.SEARCHING_CLOUDS || curState?.state == GameScreenState.BATTLE_SCOUT) {
                break
            }

            val ocrButtonTap = ctx.withFrame { frame ->
                val fullMenu = ctx.ocrEngine.recognizeRoi(
                    frame = frame,
                    roi = com.cocauto.helper.model.NormRect(0.05f, 0.14f, 0.95f, 0.95f),
                    enhance = false,
                )
                // Prefer "Find a Match" / "Tìm trận" first, then "Attack!" / "Tấn công"
                val findLine = fullMenu.lines.firstOrNull { line ->
                    val n = ResourceParser.normalizeForMatch(line.text)
                    n.contains("tim tran") || n.contains("find a match") || n.contains("tim doi thu") || n.contains("find now")
                } ?: fullMenu.lines.firstOrNull { line ->
                    ResourceParser.isAttackMenuText(line.text)
                }
                findLine?.center
            }

            if (ocrButtonTap != null) {
                BotStateStore.setAction("Bấm Tìm trận / Tấn công (bước $step)", GameScreenState.ATTACK_MENU)
                ctx.tap(ocrButtonTap)
                ctx.sleep(1_200L)
            } else if (step == 1) {
                // Fallback to calibrated Multiplayer button on left side of Battle menu
                ctx.tap(cal.findMatchButton)
                ctx.sleep(1_200L)
            } else {
                break
            }
        }

        // Search & Next loop
        val matchedLoot = searchForTargetBase(ctx) ?: return

        // Execute configured deployment strategy
        BotStateStore.setAction("Đang đánh nhà mục tiêu!", GameScreenState.BATTLE_ACTIVE)
        BotStateStore.log(
            "Đánh nhà: Vàng ${formatK(matchedLoot.gold)} | Dầu ${formatK(matchedLoot.elixir)} | DE ${matchedLoot.darkElixir}",
            LogLevel.SUCCESS,
        )
        DeployStrategyEngine.executeHomeAttack(ctx)

        // Monitor battle until Return Home or timeout / early surrender
        monitorAndFinishBattle(ctx, matchedLoot)

        // Cooldown before next cycle
        if (cfg.postBattleCooldownSec > 0) {
            ctx.sleep(cfg.postBattleCooldownSec * 1_000L)
        }
    }

    private suspend fun searchForTargetBase(ctx: BotExecutionContext): LootReading? {
        val cfg = ctx.config.homeFarm
        val cal = ctx.calibration
        val maxNexts = cfg.maxNextCount.coerceIn(1, 200)

        for (attempt in 1..maxNexts) {
            ctx.ensureRunning()
            BotStateStore.setAction("Đang tìm nhà #$attempt/$maxNexts...", GameScreenState.SEARCHING_CLOUDS)

            val arrivedInScout = waitUntilScoutReady(ctx, timeoutMs = 28_000L)
            if (!arrivedInScout) {
                BotStateStore.log("Quá thời gian chờ mây / không vào được màn hình soi nhà.", LogLevel.WARN)
                return null
            }

            // Let loot text settle slightly
            ctx.sleep(500L)

            // Auto-calibrate Battle Scout buttons & Enemy Loot ROI on first battle seen
            if (!autoCalibratedBattleThisSession && ctx.config.safety.autoCalibrateScreen) {
                val calResult = ctx.withFrame { frame ->
                    AutoCalibrationEngine.detectFromFrame(frame, ctx.calibration, ctx.ocrEngine)
                }
                if (calResult != null && calResult.hasDetections) {
                    autoCalibratedBattleThisSession = true
                    BotStateStore.updateCalibration(calResult.profile)
                    ConfigStorage(ctx.appContext).saveCalibration(calResult.profile)
                    BotStateStore.log("Tự nhận diện màn hình đánh: ${calResult.detectedItems.joinToString(", ")}", LogLevel.SUCCESS)
                }
            }

            val latestCal = ctx.calibration
            val loot = ctx.withFrame { frame ->
                val primary = ctx.screenDetector.readEnemyLoot(frame, latestCal)
                if (primary.isValid) {
                    primary
                } else {
                    // Fallback wider top-left scan if enemyLootRoi was slightly off
                    val fallbackOcr = ctx.ocrEngine.recognizeRoi(
                        frame = frame,
                        roi = com.cocauto.helper.model.NormRect(0.02f, 0.08f, 0.30f, 0.38f),
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
                    ctx.tap(cal.endBattleButton)
                    ctx.sleep(1_400L)
                    null
                }
            }

            BotStateStore.updateStats { it.copy(basesSkipped = it.basesSkipped + 1) }
            ctx.tap(cal.nextBaseButton)
            ctx.sleep(1_300L)
        }
        return null
    }

    private suspend fun waitUntilScoutReady(ctx: BotExecutionContext, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ctx.ensureRunning()
            val analysis = ctx.withFrame { frame ->
                ctx.screenDetector.analyzeScreen(frame, ctx.calibration, BotStateStore.runtime.value.foregroundPackage)
            }
            when (analysis?.state) {
                GameScreenState.BATTLE_SCOUT -> return true
                GameScreenState.HOME_VILLAGE -> return false
                GameScreenState.DISCONNECTED_DIALOG -> {
                    analysis.suggestedTapPoint?.let { ctx.tap(it) }
                    return false
                }
                else -> ctx.sleep(700L)
            }
        }
        return false
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
                    ctx.tap(analysis.suggestedTapPoint ?: cal.returnHomeButton)
                    ctx.sleep(1_800L)
                    recordAttackSuccess(targetLoot)
                    dismissPostBattlePopups(ctx)
                    return
                }

                GameScreenState.HOME_VILLAGE -> {
                    recordAttackSuccess(targetLoot)
                    return
                }

                GameScreenState.DISCONNECTED_DIALOG -> {
                    analysis.suggestedTapPoint?.let { ctx.tap(it) }
                    ctx.sleep(3_000L)
                    return
                }

                else -> {
                    // Optional early surrender once all troop slots turned grayscale
                    if (cfg.earlySurrenderWhenTroopsEmpty && elapsedSec >= 10) {
                        val allEmpty = DeployStrategyEngine.areAllTroopSlotsEmpty(ctx)
                        if (allEmpty) {
                            val firstEmpty = emptyTroopsSinceMs ?: System.currentTimeMillis().also { emptyTroopsSinceMs = it }
                            if (System.currentTimeMillis() - firstEmpty >= cfg.earlySurrenderWaitSec * 1_000L) {
                                BotStateStore.log("Đã hết quân sau ${cfg.earlySurrenderWaitSec}s — kết thúc sớm để đánh trận mới.", LogLevel.INFO)
                                triggerSurrenderAndReturn(ctx)
                                recordAttackSuccess(targetLoot)
                                return
                            }
                        } else {
                            emptyTroopsSinceMs = null
                        }
                    }
                    ctx.sleep(2_000L)
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
        ctx.tap(cal.endBattleButton)
        ctx.sleep(750L)

        // Dynamically locate "Okay" / "Đồng ý" / "OK" button in surrender confirmation dialog via OCR
        val confirmTap = ctx.withFrame { frame ->
            val dialogOcr = ctx.ocrEngine.recognizeRoi(frame, cal.dialogCenterRoi, enhance = true)
            dialogOcr.lines.firstOrNull { ResourceParser.isConfirmDialogButtonText(it.text) }?.center
        } ?: cal.surrenderConfirmButton

        ctx.tap(confirmTap)
        ctx.sleep(1_400L)

        val returnTap = ctx.withFrame { frame ->
            val sumOcr = ctx.ocrEngine.recognizeRoi(frame, cal.returnHomeRoi, enhance = true)
            sumOcr.lines.firstOrNull { ResourceParser.isReturnHomeText(it.text) }?.center
        } ?: cal.returnHomeButton

        ctx.tap(returnTap)
        ctx.sleep(2_000L)
        dismissPostBattlePopups(ctx)
    }

    private suspend fun dismissPostBattlePopups(ctx: BotExecutionContext) {
        val analysis = ctx.withFrame { frame ->
            ctx.screenDetector.analyzeScreen(frame, ctx.calibration, BotStateStore.runtime.value.foregroundPackage)
        }
        if (analysis?.state == GameScreenState.POPUP_DIALOG) {
            ctx.tap(analysis.suggestedTapPoint ?: NormPoint(0.50f, 0.72f))
            ctx.sleep(600L)
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
