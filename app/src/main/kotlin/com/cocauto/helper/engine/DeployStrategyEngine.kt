package com.cocauto.helper.engine

import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.DeployEdge
import com.cocauto.helper.model.DeployStrategyType
import com.cocauto.helper.model.HomeFarmConfig
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.vision.ImageAnalyzer

/**
 * Computes and executes multi-strategy troop, hero, and spell deployment in Clash of Clans:
 * 1. FOUR_SIDES_SURROUND — evenly distributes drops across NW, NE, SE, SW edges
 * 2. FOCUSED_DIRECTION — concentrates army along 1–2 user-selected sides + funnel spells
 * 3. PACED_WAVES — staggered multi-wave drops at discrete perimeter points (great for Sneaky Goblins)
 */
object DeployStrategyEngine {

    /**
     * Pure helper returning ordered deployment anchor points for unit testing & execution.
     */
    fun planDropPoints(
        strategy: DeployStrategyType,
        calibration: CalibrationProfile,
        focusedEdges: Set<DeployEdge>,
        pointsPerEdge: Int = 6,
    ): List<NormPoint> {
        return when (strategy) {
            DeployStrategyType.FOUR_SIDES_SURROUND -> {
                DeployEdge.entries.flatMap { edge ->
                    calibration.edgePoints(edge, pointsPerEdge.coerceAtLeast(3))
                }
            }

            DeployStrategyType.FOCUSED_DIRECTION -> {
                val chosen = focusedEdges.ifEmpty { setOf(DeployEdge.NW, DeployEdge.NE) }
                chosen.flatMap { edge ->
                    calibration.edgePoints(edge, (pointsPerEdge + 2).coerceAtLeast(4))
                }
            }

            DeployStrategyType.PACED_WAVES -> {
                val chosen = focusedEdges.ifEmpty { DeployEdge.entries.toSet() }
                chosen.flatMap { edge ->
                    calibration.edgePoints(edge, pointsPerEdge.coerceAtLeast(3))
                }
            }
        }
    }

    suspend fun executeHomeAttack(ctx: BotExecutionContext) {
        val cfg: HomeFarmConfig = ctx.config.homeFarm
        val cal: CalibrationProfile = ctx.calibration

        BotStateStore.setAction("Zoom bản đồ & chuẩn bị thả quân (${strategyLabel(cfg.deployStrategy)})")
        ctx.zoomOutCamera()
        ctx.sleep(250L)

        val troopSlots = (1..cfg.troopSlotCount.coerceIn(1, 8)).toList()
        val totalBarSlots = maxOf(
            10,
            cfg.troopSlotCount,
            cfg.heroSlotIndices.maxOrNull() ?: 0,
            cfg.spellSlotIndices.maxOrNull() ?: 0,
        ).coerceAtMost(13)

        when (cfg.deployStrategy) {
            DeployStrategyType.FOUR_SIDES_SURROUND -> {
                val dropPoints = planDropPoints(
                    strategy = DeployStrategyType.FOUR_SIDES_SURROUND,
                    calibration = cal,
                    focusedEdges = DeployEdge.entries.toSet(),
                    pointsPerEdge = 5,
                )
                deployTroopSlots(ctx, troopSlots, totalBarSlots, dropPoints, cfg.tapsPerPoint, cfg.skipEmptySlots)
            }

            DeployStrategyType.FOCUSED_DIRECTION -> {
                val dropPoints = planDropPoints(
                    strategy = DeployStrategyType.FOCUSED_DIRECTION,
                    calibration = cal,
                    focusedEdges = cfg.focusedEdges,
                    pointsPerEdge = 7,
                )
                deployTroopSlots(ctx, troopSlots, totalBarSlots, dropPoints, cfg.tapsPerPoint + 1, cfg.skipEmptySlots)
            }

            DeployStrategyType.PACED_WAVES -> {
                val waves = cfg.deployWaveCount.coerceIn(1, 6)
                val dropPoints = planDropPoints(
                    strategy = DeployStrategyType.PACED_WAVES,
                    calibration = cal,
                    focusedEdges = cfg.focusedEdges,
                    pointsPerEdge = 4,
                )
                for (wave in 1..waves) {
                    ctx.ensureRunning()
                    BotStateStore.setAction("Thả quân đợt $wave/$waves")
                    deployTroopSlots(ctx, troopSlots, totalBarSlots, dropPoints, cfg.tapsPerPoint, cfg.skipEmptySlots)
                    if (wave < waves) {
                        ctx.sleep(cfg.waveDelayMs.coerceIn(500L, 12_000L))
                    }
                }
            }
        }

        // Deploy Heroes
        val heroSlots = cfg.heroSlotIndices.sorted().filter { it in 1..13 }
        if (heroSlots.isNotEmpty()) {
            BotStateStore.setAction("Thả Tướng (${heroSlots.size} ô)")
            val heroEdge = cfg.focusedEdges.firstOrNull() ?: DeployEdge.NW
            val heroDrops = cal.edgePoints(heroEdge, heroSlots.size.coerceAtLeast(2))
            for ((index, slot) in heroSlots.withIndex()) {
                if (cfg.skipEmptySlots && !isSlotAvailable(ctx, slot, totalBarSlots)) continue
                ctx.tap(cal.troopSlotPoint(slot, totalBarSlots), delayAfter = false)
                ctx.sleep(140L)
                ctx.tap(heroDrops[index % heroDrops.size], delayAfter = false)
                ctx.sleep(220L)
            }
        }

        // Deploy Spells slightly inward along the push side
        val spellSlots = cfg.spellSlotIndices.sorted().filter { it in 1..13 }
        if (spellSlots.isNotEmpty()) {
            BotStateStore.setAction("Thả Phép hỗ trợ")
            val spellEdge = cfg.focusedEdges.firstOrNull() ?: DeployEdge.NW
            val spellPoints = cal.edgePoints(spellEdge, spellSlots.size.coerceAtLeast(3), insetTowardCenter = 0.24f)
            for ((index, slot) in spellSlots.withIndex()) {
                if (cfg.skipEmptySlots && !isSlotAvailable(ctx, slot, totalBarSlots)) continue
                ctx.tap(cal.troopSlotPoint(slot, totalBarSlots), delayAfter = false)
                ctx.sleep(150L)
                repeat(2) { rep ->
                    ctx.tap(spellPoints[(index + rep) % spellPoints.size], delayAfter = false)
                    ctx.sleep(220L)
                }
            }
        }

        // Optional second sweep of remaining troops (e.g. if army had high troop count)
        if (cfg.deployStrategy != DeployStrategyType.PACED_WAVES) {
            val remainingPoints = planDropPoints(cfg.deployStrategy, cal, cfg.focusedEdges, pointsPerEdge = 4)
            deployTroopSlots(ctx, troopSlots, totalBarSlots, remainingPoints, tapsPerPoint = 2, skipEmpty = true)
        }

        // Trigger Hero Abilities after configured delay
        if (heroSlots.isNotEmpty() && cfg.triggerHeroAbilityAfterSec > 0) {
            BotStateStore.setAction("Chờ ${cfg.triggerHeroAbilityAfterSec}s để bật kỹ năng Tướng")
            ctx.sleep(cfg.triggerHeroAbilityAfterSec * 1_000L)
            for (slot in heroSlots) {
                ctx.tap(cal.troopSlotPoint(slot, totalBarSlots), delayAfter = false)
                ctx.sleep(180L)
            }
            BotStateStore.log("Đã kích hoạt kỹ năng Tướng.", LogLevel.INFO)
        }
    }

    private suspend fun deployTroopSlots(
        ctx: BotExecutionContext,
        slots: List<Int>,
        totalBarSlots: Int,
        dropPoints: List<NormPoint>,
        tapsPerPoint: Int,
        skipEmpty: Boolean,
    ) {
        val cal = ctx.calibration
        val safeTaps = tapsPerPoint.coerceIn(1, 8)
        for (slot in slots) {
            ctx.ensureRunning()
            if (skipEmpty && !isSlotAvailable(ctx, slot, totalBarSlots)) {
                continue
            }
            ctx.tap(cal.troopSlotPoint(slot, totalBarSlots), delayAfter = false)
            ctx.sleep(130L)
            for (point in dropPoints) {
                repeat(safeTaps) {
                    ctx.tap(point, delayAfter = false)
                    ctx.sleep(75L)
                }
            }
        }
    }

    suspend fun areAllTroopSlotsEmpty(ctx: BotExecutionContext): Boolean {
        val cfg = ctx.config.homeFarm
        val totalBarSlots = maxOf(10, cfg.troopSlotCount).coerceAtMost(13)
        return ctx.withFrame { frame ->
            (1..cfg.troopSlotCount.coerceIn(1, 8)).none { slot ->
                ImageAnalyzer.isTroopSlotAvailable(frame, ctx.calibration.troopSlotRect(slot, totalBarSlots))
            }
        } ?: false
    }

    private suspend fun isSlotAvailable(ctx: BotExecutionContext, slotIndex: Int, totalBarSlots: Int): Boolean {
        val rect = ctx.calibration.troopSlotRect(slotIndex, totalBarSlots)
        return ctx.withFrame { frame ->
            ImageAnalyzer.isTroopSlotAvailable(frame, rect)
        } ?: true
    }

    private fun strategyLabel(strategy: DeployStrategyType): String = when (strategy) {
        DeployStrategyType.FOUR_SIDES_SURROUND -> "Rải 4 cạnh"
        DeployStrategyType.FOCUSED_DIRECTION -> "Dồn hướng"
        DeployStrategyType.PACED_WAVES -> "Theo nhịp"
    }
}
