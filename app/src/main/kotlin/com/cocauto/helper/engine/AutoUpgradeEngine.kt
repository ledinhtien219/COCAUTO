package com.cocauto.helper.engine

import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.CurrencyType
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.HomeResources
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.vision.ImageAnalyzer
import com.cocauto.helper.vision.ResourceParser

/**
 * Handles all Home Village automatic upgrades:
 * 1. Wall upgrades when Gold/Elixir exceeds threshold (supporting dedicated reserved wall builder)
 * 2. Building upgrades via Builder Suggested Upgrades menu (respecting avoid keywords & min reserves)
 * 3. Laboratory troop/spell research upgrades (respecting interval, avoid keywords & min reserves)
 */
class AutoUpgradeEngine {
    private var lastLabCheckAtMs: Long = 0L

    suspend fun runUpgradeCycle(ctx: BotExecutionContext) {
        val upgradeCfg = ctx.config.upgrade
        if (!upgradeCfg.autoUpgradeWalls && !upgradeCfg.autoUpgradeBuildings && !upgradeCfg.autoUpgradeLab) {
            return
        }

        // Read fresh Home Resources and Builder counts
        val (homeRes, builders) = readHomeSnapshot(ctx) ?: return
        BotStateStore.updateStats {
            it.copy(
                lastHomeResources = homeRes,
                lastBuilderStatus = builders,
            )
        }

        // 1. Auto Wall Upgrades (can use reserved wall builder)
        if (upgradeCfg.autoUpgradeWalls && builders.freeBuilders >= 1) {
            runWallUpgrades(ctx, homeRes, builders)
        }

        // 2. Auto Building Upgrades (only uses builders above reserveBuildersForWalls)
        if (upgradeCfg.autoUpgradeBuildings) {
            val freshSnapshot = readHomeSnapshot(ctx)
            val curHome = freshSnapshot?.first ?: homeRes
            val curBuilders = freshSnapshot?.second ?: builders
            val usableBuilders = ResourceParser.usableBuildingBuilders(
                curBuilders,
                upgradeCfg.reserveBuildersForWalls,
            )
            if (usableBuilders > 0) {
                runBuildingUpgrades(ctx, curHome, usableBuilders)
            } else if (curBuilders.freeBuilders > 0 && upgradeCfg.reserveBuildersForWalls > 0) {
                BotStateStore.log(
                    "Đang giữ ${curBuilders.freeBuilders} thợ xây dành riêng cho nâng Tường.",
                    LogLevel.INFO,
                )
            }
        }

        // 3. Auto Laboratory Upgrade on configured interval
        if (upgradeCfg.autoUpgradeLab) {
            val intervalMs = upgradeCfg.labCheckIntervalMinutes.coerceAtLeast(1) * 60_000L
            val now = System.currentTimeMillis()
            if (now - lastLabCheckAtMs >= intervalMs) {
                lastLabCheckAtMs = now
                val freshHome = readHomeSnapshot(ctx)?.first ?: homeRes
                runLaboratoryUpgrade(ctx, freshHome)
            }
        }
    }

    private suspend fun runWallUpgrades(
        ctx: BotExecutionContext,
        initialHome: HomeResources,
        initialBuilders: BuilderStatus,
    ) {
        val cfg = ctx.config.upgrade
        val cal = ctx.calibration
        var currentHome = initialHome
        var currentBuilders = initialBuilders

        val maxLoops = cfg.maxWallsPerCycle.coerceIn(1, 20)
        for (i in 1..maxLoops) {
            val currency = ResourceParser.chooseWallUpgradeCurrency(currentHome, currentBuilders, cfg)
                ?: break

            BotStateStore.setAction("Kiểm tra nâng Tường #$i (${currency.name})", GameScreenState.BUILDER_SUGGESTIONS_OPEN)
            ctx.tap(cal.builderBadgeButton)
            ctx.sleep(650L)

            val candidates = scanUpgradeDrawer(ctx, cal.builderSuggestionsRoi)
            val wallCandidate = candidates.firstOrNull { it.isWall }
            if (wallCandidate == null) {
                BotStateStore.log("Không thấy mục Tường (Wall) trong menu gợi ý thợ xây.", LogLevel.INFO)
                ctx.tap(cal.dismissAreaPoint)
                break
            }

            // Verify actual wall cost against min keep reserve before tapping
            if (!ResourceParser.respectsResourceReserve(currentHome, wallCandidate.cost, currency, cfg)) {
                BotStateStore.log(
                    "Bỏ qua nâng Tường (giá ${wallCandidate.cost}) để giữ tài nguyên tối thiểu.",
                    LogLevel.INFO,
                )
                ctx.tap(cal.dismissAreaPoint)
                break
            }

            // Select wall row -> camera centers on wall -> tap currency button -> confirm
            ctx.tap(wallCandidate.tapPoint)
            ctx.sleep(750L)

            val payButton = if (currency == CurrencyType.ELIXIR) {
                cal.upgradeWallElixirButton
            } else {
                cal.upgradeActionButton
            }
            ctx.tap(payButton)
            ctx.sleep(650L)
            ctx.tap(cal.upgradeConfirmButton)
            ctx.sleep(700L)

            BotStateStore.updateStats { it.copy(wallsUpgraded = it.wallsUpgraded + 1) }
            BotStateStore.log("Đã nâng 1 ô Tường bằng ${if (currency == CurrencyType.ELIXIR) "Dầu" else "Vàng"}!", LogLevel.SUCCESS)

            // Dismiss selection & refresh home resources for next loop
            ctx.tap(cal.dismissAreaPoint)
            ctx.sleep(450L)
            val refreshed = readHomeSnapshot(ctx) ?: break
            currentHome = refreshed.first
            currentBuilders = refreshed.second
        }
    }

    private suspend fun runBuildingUpgrades(
        ctx: BotExecutionContext,
        initialHome: HomeResources,
        usableBuilders: Int,
    ) {
        val cfg = ctx.config.upgrade
        val cal = ctx.calibration
        var currentHome = initialHome

        for (slot in 1..usableBuilders.coerceAtMost(4)) {
            BotStateStore.setAction("Tìm công trình nâng cấp (Thợ rảnh #$slot)", GameScreenState.BUILDER_SUGGESTIONS_OPEN)
            ctx.tap(cal.builderBadgeButton)
            ctx.sleep(650L)

            var chosen = findBestCandidateInDrawer(ctx, currentHome)
            // If nothing eligible on first page, scroll Builder Suggestions down once and re-scan
            if (chosen == null) {
                val roi = cal.builderSuggestionsRoi
                ctx.swipe(
                    from = NormPoint(roi.centerX, roi.bottom - 0.06f),
                    to = NormPoint(roi.centerX, roi.top + 0.12f),
                    durationMs = 450L,
                )
                ctx.sleep(550L)
                chosen = findBestCandidateInDrawer(ctx, currentHome)
            }

            if (chosen == null) {
                BotStateStore.log("Không có công trình phù hợp bộ lọc & ngưỡng giữ tài nguyên.", LogLevel.INFO)
                ctx.tap(cal.dismissAreaPoint)
                break
            }

            BotStateStore.log(
                "Chọn nâng cấp: ${chosen.name} (giá ~${chosen.cost}, ${chosen.currency.name})",
                LogLevel.INFO,
            )
            // Tap candidate row -> camera selects building -> tap Upgrade icon -> tap Confirm
            ctx.tap(chosen.tapPoint)
            ctx.sleep(850L)
            ctx.tap(cal.upgradeActionButton)
            ctx.sleep(700L)
            ctx.tap(cal.upgradeConfirmButton)
            ctx.sleep(800L)
            ctx.tap(cal.dismissAreaPoint)
            ctx.sleep(500L)

            BotStateStore.updateStats { it.copy(buildingsUpgraded = it.buildingsUpgraded + 1) }
            BotStateStore.log("Đã gửi lệnh nâng cấp ${chosen.name}!", LogLevel.SUCCESS)

            val refreshed = readHomeSnapshot(ctx) ?: break
            currentHome = refreshed.first
            if (ResourceParser.usableBuildingBuilders(refreshed.second, cfg.reserveBuildersForWalls) <= 0) {
                break
            }
        }
    }

    private suspend fun runLaboratoryUpgrade(ctx: BotExecutionContext, homeRes: HomeResources) {
        val cfg = ctx.config.upgrade
        val cal = ctx.calibration
        BotStateStore.setAction("Kiểm tra Phòng thí nghiệm (Lab)", GameScreenState.LAB_MENU_OPEN)

        ctx.tap(cal.labShortcutPoint)
        ctx.sleep(800L)

        val candidates = scanUpgradeDrawer(ctx, cal.labMenuRoi)
        val best = ResourceParser.selectBestLabUpgrade(candidates, homeRes, cfg)
        if (best == null) {
            BotStateStore.log("Lab đang bận hoặc chưa có lính/phép đủ tài nguyên theo bộ lọc.", LogLevel.INFO)
            ctx.tap(cal.dismissAreaPoint)
            ctx.sleep(400L)
            return
        }

        BotStateStore.log("Bắt đầu nâng Lab: ${best.name} (giá ~${best.cost})", LogLevel.INFO)
        ctx.tap(best.tapPoint)
        ctx.sleep(750L)
        ctx.tap(cal.labStartButton)
        ctx.sleep(700L)
        ctx.tap(cal.dismissAreaPoint)
        ctx.sleep(450L)

        BotStateStore.updateStats { it.copy(labUpgradesStarted = it.labUpgradesStarted + 1) }
        BotStateStore.log("Đã gửi lệnh nghiên cứu ${best.name} trong Lab!", LogLevel.SUCCESS)
    }

    private suspend fun findBestCandidateInDrawer(
        ctx: BotExecutionContext,
        homeRes: HomeResources,
    ) = ResourceParser.selectBestBuildingUpgrade(
        candidates = scanUpgradeDrawer(ctx, ctx.calibration.builderSuggestionsRoi),
        home = homeRes,
        config = ctx.config.upgrade,
    )

    private suspend fun scanUpgradeDrawer(
        ctx: BotExecutionContext,
        roi: com.cocauto.helper.model.NormRect,
    ) = ctx.withFrame { frame ->
        val ocr = ctx.ocrEngine.recognizeRoi(frame, roi, enhance = true)
        ResourceParser.parseUpgradeCandidates(ocr.lines) { costLine ->
            ImageAnalyzer.detectCurrencyForCost(frame, costLine.bounds)
        }
    }.orEmpty()

    private suspend fun readHomeSnapshot(ctx: BotExecutionContext): Pair<HomeResources, BuilderStatus>? {
        return ctx.withFrame { frame ->
            val res = ctx.screenDetector.readHomeResources(frame, ctx.calibration)
            val builders = ctx.screenDetector.readBuilderStatus(frame, ctx.calibration)
            res to builders
        }
    }
}
