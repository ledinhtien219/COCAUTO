package com.cocauto.helper.engine

import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.CurrencyType
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.HomeResources
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import com.cocauto.helper.vision.ImageAnalyzer
import com.cocauto.helper.vision.ResourceParser

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class HomeStatusSnapshot(
    val resources: HomeResources,
    val storageFill: com.cocauto.helper.model.StorageFillLevels,
    val builders: BuilderStatus,
    val lab: BuilderStatus,
)

/**
 * Handles all Home Village automatic construction & upgrades with strict Builder-Free detection:
 * 1. Strictly reads free/total Builders ("0/2", "1/5") — NEVER opens Builder menu when 0 builders are free
 * 2. Auto Wall upgrades when Gold/Elixir exceeds threshold (can use dedicated reserved wall builder)
 * 3. Auto New Building construction (places blueprint & taps green checkmark ✓) + Building Upgrades
 * 4. Auto Laboratory troop/spell research upgrades (independent of Builders!)
 */
class AutoUpgradeEngine {
    private var lastLabCheckAtMs: Long = 0L

    suspend fun runUpgradeCycle(
        ctx: BotExecutionContext,
        preSnapshot: HomeStatusSnapshot? = null,
    ): HomeStatusSnapshot? {
        val upgradeCfg = ctx.config.upgrade
        val wantsBuilderWork = upgradeCfg.autoUpgradeWalls ||
            upgradeCfg.autoUpgradeBuildings ||
            upgradeCfg.autoBuildNewBuildings

        if (!wantsBuilderWork && !upgradeCfg.autoUpgradeLab) {
            return preSnapshot
        }

        // Reuse pre-read Home snapshot (0 extra captures!) or read resources+builders+lab in 1 parallel frame
        var snapshot = preSnapshot ?: readFullHomeSnapshot(ctx) ?: return null
        if (!snapshot.builders.isValid) {
            ctx.sleep(200L)
            snapshot = readFullHomeSnapshot(ctx) ?: snapshot
        }
        val homeRes = snapshot.resources
        val builders = snapshot.builders
        BotStateStore.updateStats {
            it.copy(
                lastHomeResources = homeRes,
                lastStorageFill = snapshot.storageFill,
                lastBuilderStatus = builders,
            )
        }

        // Strict Builder-Free gate before doing ANY Wall / Build / Upgrade action
        if (wantsBuilderWork) {
            if (!builders.isValid && upgradeCfg.onlyActWhenBuilderFree) {
                BotStateStore.log(
                    "Chưa nhận diện rõ số Thợ xây — tạm bỏ qua nâng/xây để tránh bấm nhầm.",
                    LogLevel.WARN,
                )
            } else if (builders.freeBuilders <= 0 && upgradeCfg.onlyActWhenBuilderFree) {
                BotStateStore.log(
                    "Thợ xây đang bận (${builders.freeBuilders}/${builders.totalBuilders}) — chỉ nâng & xây khi có Thợ rảnh.",
                    LogLevel.INFO,
                )
            } else {
                BotStateStore.log(
                    "Nhận diện ${builders.freeBuilders}/${builders.totalBuilders} Thợ xây rảnh — kiểm tra nâng & xây mới!",
                    LogLevel.SUCCESS,
                )

                // 1. Auto Wall Upgrades (can use reserved wall builder)
                if (upgradeCfg.autoUpgradeWalls && builders.freeBuilders >= 1) {
                    runWallUpgrades(ctx, homeRes, builders)
                }

                // 2. Auto New Building Construction & Building Upgrades (uses builders above reserveBuildersForWalls)
                if (upgradeCfg.autoUpgradeBuildings || upgradeCfg.autoBuildNewBuildings) {
                    val fresh = readHomeSnapshot(ctx)
                    val curHome = fresh?.first ?: homeRes
                    val curBuilders = fresh?.second ?: builders
                    val usableBuilders = ResourceParser.usableBuildingBuilders(
                        curBuilders,
                        upgradeCfg.reserveBuildersForWalls,
                    )
                    if (usableBuilders > 0) {
                        runBuildAndUpgradeBuildings(ctx, curHome, usableBuilders)
                    } else if (curBuilders.freeBuilders > 0 && upgradeCfg.reserveBuildersForWalls > 0) {
                        BotStateStore.log(
                            "Đang giữ ${curBuilders.freeBuilders} thợ xây dành riêng cho nâng Tường.",
                            LogLevel.INFO,
                        )
                    }
                }
            }
        }

        // 3. Auto Laboratory Upgrade (runs on interval — Lab does NOT require a free Builder)
        if (upgradeCfg.autoUpgradeLab) {
            val intervalMs = upgradeCfg.labCheckIntervalMinutes.coerceAtLeast(1) * 60_000L
            val now = System.currentTimeMillis()
            if (now - lastLabCheckAtMs >= intervalMs) {
                lastLabCheckAtMs = now
                if (snapshot.lab.isValid && snapshot.lab.freeBuilders <= 0) {
                    BotStateStore.log(
                        "Phòng thí nghiệm (Lab) đang bận (${snapshot.lab.freeBuilders}/${snapshot.lab.totalBuilders}) — bỏ qua.",
                        LogLevel.INFO,
                    )
                } else {
                    val freshHome = readHomeSnapshot(ctx)?.first ?: homeRes
                    runLaboratoryUpgrade(ctx, freshHome)
                }
            }
        }
        return snapshot
    }

    suspend fun readFullHomeSnapshot(ctx: BotExecutionContext): HomeStatusSnapshot? {
        return ctx.withFrame { frame ->
            coroutineScope {
                val resDef = async { ctx.screenDetector.readHomeResources(frame, ctx.calibration) }
                val bldDef = async { ctx.screenDetector.readBuilderStatus(frame, ctx.calibration) }
                val labDef = async { ctx.screenDetector.readLabStatus(frame, ctx.calibration) }
                val fill = ctx.screenDetector.readStorageFillLevels(frame, ctx.calibration)
                HomeStatusSnapshot(
                    resources = resDef.await(),
                    storageFill = fill,
                    builders = bldDef.await(),
                    lab = labDef.await(),
                )
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
            if (currentBuilders.freeBuilders < 1) break
            val currency = ResourceParser.chooseWallUpgradeCurrency(currentHome, currentBuilders, cfg)
                ?: break

            BotStateStore.setAction("Thợ rảnh — kiểm tra nâng Tường #$i", GameScreenState.BUILDER_SUGGESTIONS_OPEN)
            ctx.tap(cal.builderBadgeButton)
            ctx.sleep(650L)

            val candidates = scanUpgradeDrawer(ctx, cal.builderSuggestionsRoi)
            val wallCandidate = candidates.firstOrNull { it.isWall }
            if (wallCandidate == null) {
                BotStateStore.log("Không thấy mục Tường (Wall) trong menu gợi ý thợ xây.", LogLevel.INFO)
                ctx.tap(cal.dismissAreaPoint)
                break
            }

            if (!ResourceParser.respectsResourceReserve(currentHome, wallCandidate.cost, currency, cfg)) {
                BotStateStore.log(
                    "Bỏ qua nâng Tường (giá ${wallCandidate.cost}) để giữ tài nguyên tối thiểu.",
                    LogLevel.INFO,
                )
                ctx.tap(cal.dismissAreaPoint)
                break
            }

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

            ctx.tap(cal.dismissAreaPoint)
            ctx.sleep(450L)
            val refreshed = readHomeSnapshot(ctx) ?: break
            currentHome = refreshed.first
            currentBuilders = refreshed.second
            BotStateStore.updateStats { it.copy(lastBuilderStatus = currentBuilders) }
        }
    }

    private suspend fun runBuildAndUpgradeBuildings(
        ctx: BotExecutionContext,
        initialHome: HomeResources,
        usableBuilders: Int,
    ) {
        val cfg = ctx.config.upgrade
        val cal = ctx.calibration
        var currentHome = initialHome

        for (slot in 1..usableBuilders.coerceAtMost(5)) {
            BotStateStore.setAction("Thợ rảnh #$slot — tìm công trình xây/nâng", GameScreenState.BUILDER_SUGGESTIONS_OPEN)
            ctx.tap(cal.builderBadgeButton)
            ctx.sleep(700L)

            var chosen = findBestCandidateInDrawer(ctx, currentHome)
            if (chosen == null) {
                // Scroll Builder Suggestions down once and re-scan
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
                BotStateStore.log("Không có công trình xây mới / nâng cấp nào đạt bộ lọc & ngưỡng giữ tài nguyên.", LogLevel.INFO)
                ctx.tap(cal.dismissAreaPoint)
                break
            }

            if (chosen.isNewBuilding) {
                BotStateStore.log(
                    "Thợ rảnh -> Chọn XÂY MỚI: ${chosen.name} (giá ~${chosen.cost})",
                    LogLevel.INFO,
                )
                ctx.tap(chosen.tapPoint)
                ctx.sleep(950L)

                // Find green checkmark ✓ button on the newly placed blueprint
                val checkmarkPt = ctx.withFrame { frame ->
                    ImageAnalyzer.findGreenCheckmarkButton(frame)
                }
                if (checkmarkPt != null) {
                    ctx.tap(checkmarkPt)
                    ctx.sleep(800L)
                    BotStateStore.updateStats { it.copy(buildingsBuilt = it.buildingsBuilt + 1) }
                    BotStateStore.log("Đã đặt xây mới công trình: ${chosen.name}!", LogLevel.SUCCESS)
                } else {
                    BotStateStore.log("Không thấy nút tích xanh xác nhận xây mới — hủy chọn.", LogLevel.WARN)
                    ctx.tap(cal.dismissAreaPoint)
                    ctx.sleep(450L)
                }
            } else {
                BotStateStore.log(
                    "Thợ rảnh -> Chọn NÂNG CẤP: ${chosen.name} (giá ~${chosen.cost}, ${chosen.currency.name})",
                    LogLevel.INFO,
                )
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
            }

            // Re-verify remaining free builders immediately so we stop as soon as all builders are busy!
            val refreshed = readHomeSnapshot(ctx) ?: break
            currentHome = refreshed.first
            BotStateStore.updateStats {
                it.copy(lastHomeResources = currentHome, lastBuilderStatus = refreshed.second)
            }
            if (!refreshed.second.isValid ||
                ResourceParser.usableBuildingBuilders(refreshed.second, cfg.reserveBuildersForWalls) <= 0
            ) {
                BotStateStore.log(
                    "Thợ xây đã nhận việc (${refreshed.second.freeBuilders}/${refreshed.second.totalBuilders}) — dừng nâng/xây.",
                    LogLevel.INFO,
                )
                break
            }
        }
    }

    private suspend fun runLaboratoryUpgrade(ctx: BotExecutionContext, homeRes: HomeResources) {
        val cfg = ctx.config.upgrade
        val cal = ctx.calibration

        // Strictly check Lab badge ("0/1" busy vs "1/1" idle) BEFORE tapping Lab so we never open the sidebar unnecessarily!
        val labStatus = ctx.withFrame { frame ->
            ctx.screenDetector.readLabStatus(frame, cal)
        }
        if (labStatus != null && labStatus.isValid && labStatus.freeBuilders <= 0) {
            BotStateStore.log(
                "Phòng thí nghiệm (Lab) đang bận (${labStatus.freeBuilders}/${labStatus.totalBuilders}) — bỏ qua.",
                LogLevel.INFO,
            )
            return
        }

        BotStateStore.setAction("Kiểm tra Phòng thí nghiệm (Lab)", GameScreenState.LAB_MENU_OPEN)

        ctx.tap(cal.labShortcutPoint)
        ctx.sleep(800L)

        val candidates = scanUpgradeDrawer(ctx, cal.labMenuRoi)
        val best = ResourceParser.selectBestLabUpgrade(candidates, homeRes, cfg)
        if (best == null) {
            BotStateStore.log("Lab đang bận hoặc chưa có lính/phép đủ tài nguyên theo bộ lọc.", LogLevel.INFO)
            ctx.tap(NormPoint(0.94f, 0.045f)) // Close right-side drawer X if open
            ctx.sleep(300L)
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
        roi: NormRect,
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
