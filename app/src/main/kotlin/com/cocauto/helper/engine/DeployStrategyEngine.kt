package com.cocauto.helper.engine

import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.DeployEdge
import com.cocauto.helper.model.DeployStrategyType
import com.cocauto.helper.model.HomeFarmConfig
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.vision.AutoTroopBarDetector
import com.cocauto.helper.vision.DetectedTroopCard
import com.cocauto.helper.vision.ImageAnalyzer

/**
 * Computes and executes multi-strategy troop, hero, and spell deployment in Clash of Clans:
 * - Automatically scans the live bottom troop bar via [AutoTroopBarDetector] to detect every
 *   available troop/spell/hero card ("x30", "x12", "x4"...) and its real screen position!
 * - Deploys all available units using the chosen strategy (4-sides, 1-2 focused edges, or paced waves)
 *   and performs automatic re-scan cleanup passes until remaining units are deployed.
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
        insetTowardCenter: Float = 0f,
    ): List<NormPoint> {
        return when (strategy) {
            DeployStrategyType.FOUR_SIDES_SURROUND -> {
                DeployEdge.entries.flatMap { edge ->
                    calibration.edgePoints(edge, pointsPerEdge.coerceAtLeast(3), insetTowardCenter)
                }
            }

            DeployStrategyType.FOCUSED_DIRECTION -> {
                val chosen = focusedEdges.ifEmpty { setOf(DeployEdge.NW, DeployEdge.NE) }
                chosen.flatMap { edge ->
                    calibration.edgePoints(edge, (pointsPerEdge + 2).coerceAtLeast(4), insetTowardCenter)
                }
            }

            DeployStrategyType.PACED_WAVES -> {
                val chosen = focusedEdges.ifEmpty { DeployEdge.entries.toSet() }
                chosen.flatMap { edge ->
                    calibration.edgePoints(edge, pointsPerEdge.coerceAtLeast(3), insetTowardCenter)
                }
            }
        }
    }

    suspend fun executeHomeAttack(ctx: BotExecutionContext) {
        val cfg: HomeFarmConfig = ctx.config.homeFarm
        val cal: CalibrationProfile = ctx.calibration

        BotStateStore.setAction("Zoom bản đồ & quét quân có sẵn (${strategyLabel(cfg.deployStrategy)})")
        ctx.zoomOutCamera()
        ctx.sleep(280L)

        // 1. Auto-detect all currently available troop/spell/hero cards on the live bottom troop dock!
        val detectedCards = scanAvailableCards(ctx)

        if (detectedCards.isNotEmpty()) {
            val summary = detectedCards.joinToString(", ") {
                if (it.isVisualFallback) "Thẻ" else it.rawLabel.ifBlank { "x${it.count}" }
            }
            BotStateStore.log("Phát hiện ${detectedCards.size} ô quân có sẵn ($summary) — bắt đầu thả quân!", LogLevel.SUCCESS)

            when (cfg.deployStrategy) {
                DeployStrategyType.PACED_WAVES -> {
                    val waves = cfg.deployWaveCount.coerceIn(1, 5)
                    val dropPoints = planDropPoints(cfg.deployStrategy, cal, cfg.focusedEdges, pointsPerEdge = 5)
                    for (wave in 1..waves) {
                        ctx.ensureRunning()
                        BotStateStore.setAction("Thả quân có sẵn đợt $wave/$waves")
                        val liveCards = if (wave == 1) detectedCards else scanAvailableCards(ctx)
                        if (liveCards.isEmpty()) break
                        deployDetectedCards(ctx, liveCards, dropPoints, divisor = (waves - wave + 1))
                        if (wave < waves) {
                            ctx.sleep(cfg.waveDelayMs.coerceIn(500L, 12_000L))
                        }
                    }
                }

                else -> {
                    val primaryPoints = planDropPoints(cfg.deployStrategy, cal, cfg.focusedEdges, pointsPerEdge = 6)
                    deployDetectedCards(ctx, detectedCards, primaryPoints, divisor = 1)
                }
            }

            // 2. Smart re-scan cleanup passes (up to 2 sweeps with slight inset variations) so any
            // leftover troops (e.g. if a drop point hit a red unplaceable obstacle) still get deployed!
            val cleanupInsets = listOf(0.05f, 0.12f)
            for ((passIdx, inset) in cleanupInsets.withIndex()) {
                ctx.ensureRunning()
                ctx.sleep(260L)
                val remaining = scanAvailableCards(ctx).filter { !it.isVisualFallback || passIdx == 0 }
                if (remaining.isEmpty()) break
                BotStateStore.setAction("Thả nốt ${remaining.size} ô quân còn lại (đợt vét ${passIdx + 1})")
                val sweepPoints = planDropPoints(
                    strategy = cfg.deployStrategy,
                    calibration = cal,
                    focusedEdges = cfg.focusedEdges,
                    pointsPerEdge = 6,
                    insetTowardCenter = inset,
                )
                deployDetectedCards(ctx, remaining, sweepPoints, divisor = 1)
            }

            // 3. Trigger Hero Abilities on visual hero cards (if any) after configured delay
            val heroLikeCards = detectedCards.filter { it.isVisualFallback }
            if (heroLikeCards.isNotEmpty() && cfg.triggerHeroAbilityAfterSec > 0) {
                BotStateStore.setAction("Chờ ${cfg.triggerHeroAbilityAfterSec}s để bật kỹ năng Tướng")
                ctx.sleep(cfg.triggerHeroAbilityAfterSec * 1_000L)
                for (hero in heroLikeCards) {
                    ctx.tap(hero.tapPoint, delayAfter = false)
                    ctx.sleep(180L)
                }
            }
            return
        }

        // Fallback: if OCR + visual card scan found 0 cards, fall back to manual configured slots
        BotStateStore.log("Không quét thấy thẻ xN trên thanh quân — dùng chế độ thả theo ô cấu hình.", LogLevel.WARN)
        executeManualSlotFallback(ctx)
    }

    private suspend fun deployDetectedCards(
        ctx: BotExecutionContext,
        cards: List<DetectedTroopCard>,
        dropPoints: List<NormPoint>,
        divisor: Int,
    ) {
        if (dropPoints.isEmpty()) return
        val safeDiv = divisor.coerceAtLeast(1)

        for ((idx, card) in cards.withIndex()) {
            ctx.ensureRunning()
            BotStateStore.setAction("Đang thả ô quân ${idx + 1}/${cards.size} (${card.rawLabel.ifBlank { "x${card.count}" }})")

            // Select this exact troop card on the bottom bar
            ctx.tap(card.tapPoint, delayAfter = false)
            ctx.sleep(150L)

            // Distribute drops proportional to real available count (+ small margin for missed/red tiles)
            val totalTaps = ((card.count + 2) / safeDiv).coerceIn(3, 55)
            for (t in 0 until totalTaps) {
                ctx.ensureRunning()
                val pt = dropPoints[t % dropPoints.size]
                ctx.tap(pt, delayAfter = false)
                ctx.sleep(58L)
            }
            ctx.sleep(110L)
        }
    }

    private suspend fun scanAvailableCards(ctx: BotExecutionContext): List<DetectedTroopCard> {
        return ctx.withFrame { frame ->
            AutoTroopBarDetector.detectAvailableCards(frame, ctx.calibration, ctx.ocrEngine)
        }.orEmpty()
    }

    private suspend fun executeManualSlotFallback(ctx: BotExecutionContext) {
        val cfg = ctx.config.homeFarm
        val cal = ctx.calibration
        val troopSlots = (1..cfg.troopSlotCount.coerceIn(1, 8)).toList()
        val totalBarSlots = maxOf(
            8,
            cfg.troopSlotCount,
            cfg.heroSlotIndices.maxOrNull() ?: 0,
            cfg.spellSlotIndices.maxOrNull() ?: 0,
        ).coerceAtMost(13)

        val dropPoints = planDropPoints(cfg.deployStrategy, cal, cfg.focusedEdges, pointsPerEdge = 5)
        for (slot in troopSlots) {
            ctx.ensureRunning()
            ctx.tap(cal.troopSlotPoint(slot, totalBarSlots), delayAfter = false)
            ctx.sleep(140L)
            for (point in dropPoints) {
                repeat(cfg.tapsPerPoint.coerceIn(1, 6)) {
                    ctx.tap(point, delayAfter = false)
                    ctx.sleep(65L)
                }
            }
        }
    }

    suspend fun areAllTroopSlotsEmpty(ctx: BotExecutionContext): Boolean {
        val cards = scanAvailableCards(ctx)
        if (cards.isNotEmpty()) return false
        val cfg = ctx.config.homeFarm
        val totalBarSlots = maxOf(10, cfg.troopSlotCount).coerceAtMost(13)
        return ctx.withFrame { frame ->
            (1..cfg.troopSlotCount.coerceIn(1, 8)).none { slot ->
                ImageAnalyzer.isTroopSlotAvailable(frame, ctx.calibration.troopSlotRect(slot, totalBarSlots))
            }
        } ?: false
    }

    private fun strategyLabel(strategy: DeployStrategyType): String = when (strategy) {
        DeployStrategyType.FOUR_SIDES_SURROUND -> "Rải 4 cạnh"
        DeployStrategyType.FOCUSED_DIRECTION -> "Dồn hướng"
        DeployStrategyType.PACED_WAVES -> "Theo nhịp"
    }
}
