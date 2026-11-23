package com.cocauto.helper.engine

import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.vision.ImageAnalyzer

/**
 * Automates Builder Base (Nhà Thợ Xây) farming:
 * - Optional boat travel from Home Village -> Builder Base and back
 * - Resource collection (Gold Mine / Elixir Collector) & Clock Tower boost
 * - Battle matchmaking, troop + Battle Machine/Copter deployment & ability cycling
 * - Fast surrender mode (high Elixir/Gold per hour cycling) or full battle completion
 */
class BuilderBaseFarmEngine {

    suspend fun switchToBuilderBase(ctx: BotExecutionContext) {
        BotStateStore.setAction("Di chuyển sang Nhà Thợ Xây...", GameScreenState.BUILDER_BASE)
        ctx.zoomOutCamera()
        ctx.sleep(250L)
        ctx.tap(ctx.calibration.boatToBuilderBase)
        ctx.sleep(2_400L)
        ctx.zoomOutCamera()
        ctx.sleep(400L)
    }

    suspend fun switchBackToHomeVillage(ctx: BotExecutionContext) {
        BotStateStore.setAction("Quay lại Nhà Chính...", GameScreenState.HOME_VILLAGE)
        ctx.zoomOutCamera()
        ctx.sleep(250L)
        ctx.tap(ctx.calibration.bbBoatReturnHome)
        ctx.sleep(2_400L)
    }

    suspend fun runBuilderBaseCycle(ctx: BotExecutionContext, travelFromHome: Boolean) {
        val cfg = ctx.config.builderBase
        if (travelFromHome) {
            switchToBuilderBase(ctx)
        }

        if (cfg.collectResources) {
            collectBuilderResources(ctx)
        }
        if (cfg.activateClockTower) {
            tryActivateClockTower(ctx)
        }

        val rounds = cfg.attacksPerCycle.coerceIn(1, 30)
        for (round in 1..rounds) {
            ctx.ensureRunning()
            BotStateStore.setAction("Nhà Thợ Xây: trận $round/$rounds", GameScreenState.BUILDER_BASE)
            executeSingleBuilderAttack(ctx)
            ctx.sleep(1_400L)
        }

        if (travelFromHome) {
            switchBackToHomeVillage(ctx)
        }
    }

    private suspend fun collectBuilderResources(ctx: BotExecutionContext) {
        BotStateStore.setAction("Thu hoạch tài nguyên Nhà Thợ Xây", GameScreenState.BUILDER_BASE)
        val bubbles = ctx.withFrame { frame ->
            ImageAnalyzer.findCollectorBubblePoints(frame, maxPoints = 8)
        }.orEmpty()
        for (pt in bubbles) {
            ctx.tap(pt, delayAfter = false)
            ctx.sleep(160L)
        }
        ctx.tap(ctx.calibration.dismissAreaPoint)
    }

    private suspend fun tryActivateClockTower(ctx: BotExecutionContext) {
        val cal = ctx.calibration
        BotStateStore.setAction("Kiểm tra tăng tốc Tháp Đồng Hồ", GameScreenState.BUILDER_BASE)
        ctx.tap(cal.bbClockTowerPoint)
        ctx.sleep(500L)
        ctx.tap(cal.bbClockBoostButton)
        ctx.sleep(500L)
        ctx.tap(cal.dismissAreaPoint)
        ctx.sleep(300L)
    }

    private suspend fun executeSingleBuilderAttack(ctx: BotExecutionContext) {
        val cfg = ctx.config.builderBase
        val cal = ctx.calibration

        ctx.tap(cal.bbAttackButton)
        ctx.sleep(800L)
        ctx.tap(cal.bbFindNowButton)
        ctx.sleep(1_500L)

        // Wait until clouds clear into Builder Base battle
        val waitDeadline = System.currentTimeMillis() + 25_000L
        while (System.currentTimeMillis() < waitDeadline) {
            ctx.ensureRunning()
            val analysis = ctx.withFrame { frame ->
                ctx.screenDetector.analyzeScreen(frame, cal, BotStateStore.runtime.value.foregroundPackage)
            }
            if (analysis?.state == GameScreenState.BATTLE_ACTIVE || analysis?.state == GameScreenState.BATTLE_SCOUT) {
                break
            }
            ctx.sleep(700L)
        }

        ctx.zoomOutCamera()
        ctx.sleep(250L)

        // Deploy Battle Machine / Copter (slot 1) and Builder Base troops along configured edge
        val totalSlots = (cfg.troopSlotCount + 1).coerceIn(4, 10)
        val dropPoints = cal.edgePoints(cfg.deployEdge, count = 6)

        BotStateStore.setAction("Thả quân Nhà Thợ Xây", GameScreenState.BATTLE_ACTIVE)
        for (slot in 1..totalSlots) {
            ctx.ensureRunning()
            ctx.tap(cal.troopSlotPoint(slot, totalSlots), delayAfter = false)
            ctx.sleep(120L)
            val targetPt = dropPoints[(slot - 1) % dropPoints.size]
            repeat(if (slot == 1) 1 else 2) {
                ctx.tap(targetPt, delayAfter = false)
                ctx.sleep(90L)
            }
        }

        val battleStartMs = System.currentTimeMillis()
        val limitMs = if (cfg.fastSurrenderMode) {
            cfg.fastSurrenderDelaySec.coerceIn(5, 45) * 1_000L
        } else {
            cfg.maxBattleDurationSec.coerceIn(20, 150) * 1_000L
        }

        val heroSpamIntervalMs = cfg.heroAbilitySpamIntervalSec.coerceIn(2, 15) * 1_000L
        var lastAbilityTapMs = battleStartMs

        while (System.currentTimeMillis() - battleStartMs < limitMs) {
            ctx.ensureRunning()
            val now = System.currentTimeMillis()
            if (now - lastAbilityTapMs >= heroSpamIntervalMs) {
                // Tap slot 1 (Battle Machine/Copter) + active troop abilities
                ctx.tap(cal.troopSlotPoint(1, totalSlots), delayAfter = false)
                lastAbilityTapMs = now
            }

            val analysis = ctx.withFrame { frame ->
                ctx.screenDetector.analyzeScreen(frame, cal, BotStateStore.runtime.value.foregroundPackage)
            }
            if (analysis?.state == GameScreenState.BATTLE_SUMMARY) {
                ctx.tap(analysis.suggestedTapPoint ?: cal.returnHomeButton)
                ctx.sleep(1_600L)
                BotStateStore.updateStats { it.copy(builderBaseAttacks = it.builderBaseAttacks + 1) }
                BotStateStore.log("Hoàn thành 1 trận Nhà Thợ Xây.", LogLevel.SUCCESS)
                return
            }
            ctx.sleep(1_100L)
        }

        // Fast surrender or timeout -> clean exit back to Builder Base
        ctx.tap(cal.endBattleButton)
        ctx.sleep(650L)
        ctx.tap(cal.surrenderConfirmButton)
        ctx.sleep(1_200L)
        ctx.tap(cal.returnHomeButton)
        ctx.sleep(1_700L)
        BotStateStore.updateStats { it.copy(builderBaseAttacks = it.builderBaseAttacks + 1) }
        BotStateStore.log(
            if (cfg.fastSurrenderMode) "Xong vòng farm nhanh Nhà Thợ Xây." else "Kết thúc trận Nhà Thợ Xây.",
            LogLevel.SUCCESS,
        )
    }
}
