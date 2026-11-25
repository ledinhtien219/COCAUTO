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

            // 2. Smart OUTWARD cleanup passes along the far outer green grass edges so any leftover troops
            // (e.g. if a base was very wide or only partially zoomed out) ALWAYS land on deployable grass!
            val cleanupPointSets = listOf(
                outerGrassPerimeterPoints(),
                planDropPoints(
                    strategy = cfg.deployStrategy,
                    calibration = cal,
                    focusedEdges = cfg.focusedEdges,
                    pointsPerEdge = 5,
                    insetTowardCenter = 0f,
                ),
            )
            for ((passIdx, sweepPoints) in cleanupPointSets.withIndex()) {
                ctx.ensureRunning()
                ctx.sleep(240L)
                val remaining = scanAvailableCards(ctx).filter { !it.isVisualFallback || passIdx == 0 }
                if (remaining.isEmpty()) break
                BotStateStore.setAction("Thả nốt ${remaining.size} ô quân còn lại (viền cỏ xanh ${passIdx + 1})")
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
            ctx.sleep(160L)

            val desiredUnits = ((card.count + 2) / safeDiv).coerceIn(2, 60)
            if (desiredUnits >= 8 && !card.isVisualFallback) {
                // Fast press-and-hold deployment across spread outer-grass points (~5-8 troops per hold!)
                val holdSpots = (desiredUnits / 5).coerceIn(4, minOf(12, dropPoints.size))
                val step = (dropPoints.size.toFloat() / holdSpots).coerceAtLeast(1f)
                for (s in 0 until holdSpots) {
                    ctx.ensureRunning()
                    val pt = dropPoints[(s * step).toInt().coerceIn(0, dropPoints.lastIndex)]
                    ctx.tap(pt, delayAfter = false, holdMsOverride = 620L)
                    ctx.sleep(45L)
                }
            } else {
                val totalTaps = desiredUnits.coerceIn(2, 16)
                for (t in 0 until totalTaps) {
                    ctx.ensureRunning()
                    val pt = dropPoints[(t * 2) % dropPoints.size]
                    ctx.tap(pt, delayAfter = false, holdMsOverride = if (desiredUnits >= 4) 160L else null)
                    ctx.sleep(55L)
                }
            }
            ctx.sleep(100L)
        }
    }

    /**
     * Safe far-outer green grass anchors hugging the screen perimeter outside the red unplaceable zone,
     * staying well clear of top-left loot text, top-right resources, bottom-left Surrender, bottom-right Next,
     * and the bottom troop dock.
     */
    private fun outerGrassPerimeterPoints(): List<NormPoint> = listOf(
        // Far-right open grass & top-right grass corner
        NormPoint(0.92f, 0.30f),
        NormPoint(0.93f, 0.44f),
        NormPoint(0.92f, 0.56f),
        NormPoint(0.82f, 0.22f),
        NormPoint(0.74f, 0.14f),
        // Far-left open grass & bottom-left grass corner
        NormPoint(0.06f, 0.36f),
        NormPoint(0.06f, 0.48f),
        NormPoint(0.07f, 0.60f),
        NormPoint(0.18f, 0.63f),
        // Upper-center & lower-center edges
        NormPoint(0.36f, 0.12f),
        NormPoint(0.64f, 0.12f),
        NormPoint(0.35f, 0.76f),
        NormPoint(0.65f, 0.76f),
    )

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
