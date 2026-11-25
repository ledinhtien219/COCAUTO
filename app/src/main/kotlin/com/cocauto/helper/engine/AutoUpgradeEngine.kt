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
 * 1. Strictly reads free/total Builders ("0/3", "1/5") — NEVER opens Builder menu when 0 builders are free
 * 2. First upgrades/builds affordable buildings with SHORTEST duration first, then remaining affordable
 *    buildings fitting current Gold/Elixir (never spending Gems or tapping red unaffordable costs!)
 * 3. LAST (if free builders still remain after buildings), upgrades affordable Walls ("Tường")
 *    fitting current Gold/Elixir
 * 4. Auto Laboratory troop/spell research upgrades (independent of Builders!)
 */
class AutoUpgradeEngine {
    private var lastLabCheckAtMs: Long = 0L

    private val sidebarCloseButton = NormPoint(0.940f, 0.045f)
    private val bottomActionStripRoi = NormRect(0.18f, 0.74f, 0.86f, 0.97f)
    private val upgradeModalConfirmRoi = NormRect(0.24f, 0.62f, 0.80f, 0.90f)

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
        var homeRes = snapshot.resources
        var builders = snapshot.builders
        BotStateStore.updateStats {
            it.copy(
                lastHomeResources = homeRes,
                lastStorageFill = snapshot.storageFill,
                lastBuilderStatus = builders,
            )
        }

        // Strict Builder-Free gate before doing ANY Building / Wall action
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
                    "Nhận diện ${builders.freeBuilders}/${builders.totalBuilders} Thợ xây rảnh — tìm nhà thời gian ngắn trước, rồi nhà còn lại, cuối cùng mới nâng Tường!",
                    LogLevel.SUCCESS,
                )

                // 1. FIRST: Shortest-time buildings -> remaining affordable buildings (fitting Gold/Elixir)
                if (upgradeCfg.autoUpgradeBuildings || upgradeCfg.autoBuildNewBuildings) {
                    val usableBuilders = ResourceParser.usableBuildingBuilders(
                        builders,
                        upgradeCfg.reserveBuildersForWalls,
                    )
                    if (usableBuilders > 0) {
                        val updated = runBuildAndUpgradeBuildings(ctx, homeRes, builders, usableBuilders)
                        homeRes = updated.first
                        builders = updated.second
                    } else if (builders.freeBuilders > 0 && upgradeCfg.reserveBuildersForWalls > 0) {
                        BotStateStore.log(
                            "Đang giữ ${builders.freeBuilders} thợ xây dành riêng cho nâng Tường.",
                            LogLevel.INFO,
                        )
                    }
                }

                // 2. LAST: Upgrade Walls ONLY if free builder(s) STILL remain & Gold/Elixir fits
                if (upgradeCfg.autoUpgradeWalls && builders.freeBuilders >= 1) {
                    val updated = runWallUpgrades(ctx, homeRes, builders)
                    homeRes = updated.first
                    builders = updated.second
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
        return snapshot.copy(resources = homeRes, builders = builders)
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

    private suspend fun runBuildAndUpgradeBuildings(
        ctx: BotExecutionContext,
        initialHome: HomeResources,
        initialBuilders: BuilderStatus,
        usableBuilders: Int,
    ): Pair<HomeResources, BuilderStatus> {
        val cfg = ctx.config.upgrade
        val cal = ctx.calibration
        var currentHome = initialHome
        var currentBuilders = initialBuilders

        for (slot in 1..usableBuilders.coerceAtMost(5)) {
            BotStateStore.setAction(
                "Thợ rảnh #$slot — quét nhà thời gian ngắn & vừa Vàng/Dầu",
                GameScreenState.BUILDER_SUGGESTIONS_OPEN,
            )
            ctx.tap(cal.builderBadgeButton, delayAfter = false)
            ctx.sleep(620L)

            val bestSelection = scanMultiPageBestBuilding(ctx, currentHome)
            if (bestSelection == null) {
                BotStateStore.log(
                    "Không còn công trình phù hợp lượng Vàng/Dầu hiện có — chuyển sang kiểm tra nâng Tường.",
                    LogLevel.INFO,
                )
                closeBuilderSidebar(ctx)
                break
            }

            val (chosen, targetPage, currentPage) = bestSelection
            val finalTarget = if (targetPage == currentPage) {
                chosen
            } else {
                // Re-open sidebar from top & scroll to targetPage so tap coordinates match live card position
                closeBuilderSidebar(ctx)
                ctx.tap(cal.builderBadgeButton, delayAfter = false)
                ctx.sleep(580L)
                for (p in 0 until targetPage) {
                    scrollBuilderSidebarDown(ctx)
                }
                val pageCandidates = scanUpgradeDrawer(ctx, cal.builderSuggestionsRoi)
                val targetNorm = ResourceParser.normalizeForMatch(chosen.name)
                pageCandidates.firstOrNull {
                    !it.isWall &&
                        !it.isUnaffordableRed &&
                        it.currency != CurrencyType.GEMS &&
                        ResourceParser.normalizeForMatch(it.name) == targetNorm
                } ?: ResourceParser.selectBestBuildingUpgrade(pageCandidates, currentHome, cfg)
            }

            if (finalTarget == null) {
                closeBuilderSidebar(ctx)
                break
            }

            val durLabel = ResourceParser.formatDurationShort(finalTarget.durationSeconds)
            if (finalTarget.isNewBuilding) {
                BotStateStore.log(
                    "Thợ rảnh -> XÂY MỚI (${durLabel}): ${finalTarget.name} (giá ${finalTarget.cost}, ${finalTarget.currency.name})",
                    LogLevel.INFO,
                )
                ctx.tap(finalTarget.tapPoint, delayAfter = false)
                ctx.sleep(850L)

                val checkmarkPt = ctx.withFrame { frame ->
                    ImageAnalyzer.findGreenCheckmarkButton(frame)
                }
                if (checkmarkPt != null) {
                    ctx.tap(checkmarkPt, delayAfter = false)
                    ctx.sleep(750L)
                    BotStateStore.updateStats { it.copy(buildingsBuilt = it.buildingsBuilt + 1) }
                    BotStateStore.log("Đã đặt xây mới ${finalTarget.name} ($durLabel)!", LogLevel.SUCCESS)
                } else {
                    BotStateStore.log("Không thấy nút tích xanh xác nhận xây mới — hủy chọn.", LogLevel.WARN)
                    ctx.tap(cal.dismissAreaPoint, delayAfter = false)
                    ctx.sleep(420L)
                }
            } else {
                BotStateStore.log(
                    "Thợ rảnh -> NÂNG CẤP (${durLabel}): ${finalTarget.name} (giá ${finalTarget.cost}, ${finalTarget.currency.name})",
                    LogLevel.INFO,
                )
                ctx.tap(finalTarget.tapPoint, delayAfter = false)
                ctx.sleep(750L)

                val confirmed = tapUpgradeActionAndConfirm(ctx, preferredCurrency = finalTarget.currency)
                if (confirmed) {
                    BotStateStore.updateStats { it.copy(buildingsUpgraded = it.buildingsUpgraded + 1) }
                    BotStateStore.log("Đã gửi lệnh nâng cấp ${finalTarget.name} ($durLabel)!", LogLevel.SUCCESS)
                } else {
                    BotStateStore.log("Hủy nâng ${finalTarget.name} vì giá không hợp lệ hoặc không đủ tài nguyên.", LogLevel.WARN)
                }
                ctx.tap(cal.dismissAreaPoint, delayAfter = false)
                ctx.sleep(450L)
            }

            // Re-verify remaining resources & free builders immediately
            val refreshed = readHomeSnapshot(ctx) ?: break
            currentHome = refreshed.first
            currentBuilders = refreshed.second
            BotStateStore.updateStats {
                it.copy(lastHomeResources = currentHome, lastBuilderStatus = currentBuilders)
            }
            if (!currentBuilders.isValid ||
                ResourceParser.usableBuildingBuilders(currentBuilders, cfg.reserveBuildersForWalls) <= 0
            ) {
                BotStateStore.log(
                    "Thợ xây đã nhận việc (${currentBuilders.freeBuilders}/${currentBuilders.totalBuilders}) — dừng tìm công trình.",
                    LogLevel.INFO,
                )
                break
            }
        }
        return currentHome to currentBuilders
    }

    /**
     * Scans up to 3 pages of the Builder sidebar so SHORTEST_TIME_FIRST truly picks the shortest-duration
     * affordable building across the entire list (not just the top 3 visible cards).
     * Returns Triple(bestCandidate, targetPageIndex, currentPageIndexAtEndOfScan).
     */
    private suspend fun scanMultiPageBestBuilding(
        ctx: BotExecutionContext,
        homeRes: HomeResources,
    ): Triple<com.cocauto.helper.model.UpgradeCandidate, Int, Int>? {
        val cfg = ctx.config.upgrade
        val maxPages = 3
        val allPagesCandidates = mutableListOf<Pair<Int, com.cocauto.helper.model.UpgradeCandidate>>()
        var lastPageSignature = ""
        var currentPage = 0

        for (page in 0 until maxPages) {
            currentPage = page
            val pageItems = scanUpgradeDrawer(ctx, ctx.calibration.builderSuggestionsRoi)
            val signature = pageItems.joinToString("|") { "${it.name}:${it.cost}" }
            if (page > 0 && (pageItems.isEmpty() || signature == lastPageSignature)) {
                break
            }
            lastPageSignature = signature
            for (item in pageItems) {
                allPagesCandidates.add(page to item)
            }
            if (page < maxPages - 1) {
                scrollBuilderSidebarDown(ctx)
            }
        }

        if (allPagesCandidates.isEmpty()) return null

        // Deduplicate by normalized building name keeping first page seen, then pick global best!
        val seenNames = mutableSetOf<String>()
        val deduped = allPagesCandidates.filter { (_, item) ->
            val key = "${ResourceParser.normalizeForMatch(item.name)}#${item.cost}"
            seenNames.add(key)
        }
        val best = ResourceParser.selectBestBuildingUpgrade(
            candidates = deduped.map { it.second },
            home = homeRes,
            config = cfg,
        ) ?: return null

        val bestPage = deduped.firstOrNull { it.second === best }?.first ?: currentPage
        return Triple(best, bestPage, currentPage)
    }

    private suspend fun runWallUpgrades(
        ctx: BotExecutionContext,
        initialHome: HomeResources,
        initialBuilders: BuilderStatus,
    ): Pair<HomeResources, BuilderStatus> {
        val cfg = ctx.config.upgrade
        val cal = ctx.calibration
        var currentHome = initialHome
        var currentBuilders = initialBuilders

        val maxLoops = cfg.maxWallsPerCycle.coerceIn(1, 20)
        for (i in 1..maxLoops) {
            if (currentBuilders.freeBuilders < 1) break
            // Quick pre-check before opening sidebar
            if (ResourceParser.chooseWallUpgradeCurrency(currentHome, currentBuilders, cfg) == null) {
                break
            }

            BotStateStore.setAction("Cuối cùng: kiểm tra nâng Tường #$i", GameScreenState.BUILDER_SUGGESTIONS_OPEN)
            ctx.tap(cal.builderBadgeButton, delayAfter = false)
            ctx.sleep(620L)

            // Walls are often further down in the 2026 sidebar -> scan up to 3 pages
            var wallSelection: Pair<com.cocauto.helper.model.UpgradeCandidate, CurrencyType>? = null
            var lastSig = ""
            for (page in 0..2) {
                val candidates = scanUpgradeDrawer(ctx, cal.builderSuggestionsRoi)
                val sig = candidates.joinToString("|") { "${it.name}:${it.cost}" }
                wallSelection = ResourceParser.selectBestWallCandidate(candidates, currentHome, currentBuilders, cfg)
                if (wallSelection != null) break
                if (page > 0 && (candidates.isEmpty() || sig == lastSig)) break
                lastSig = sig
                if (page < 2) {
                    scrollBuilderSidebarDown(ctx)
                }
            }

            if (wallSelection == null) {
                BotStateStore.log("Không còn Tường phù hợp lượng Vàng/Dầu đang có.", LogLevel.INFO)
                closeBuilderSidebar(ctx)
                break
            }

            val (wallCandidate, currency) = wallSelection
            BotStateStore.log(
                "Cuối cùng -> Chọn nâng Tường (giá ${wallCandidate.cost}, ${if (currency == CurrencyType.ELIXIR) "Dầu" else "Vàng"})",
                LogLevel.INFO,
            )
            ctx.tap(wallCandidate.tapPoint, delayAfter = false)
            ctx.sleep(700L)

            val confirmed = tapUpgradeActionAndConfirm(ctx, preferredCurrency = currency, isWall = true)
            if (confirmed) {
                BotStateStore.updateStats { it.copy(wallsUpgraded = it.wallsUpgraded + 1) }
                BotStateStore.log(
                    "Đã nâng 1 ô Tường bằng ${if (currency == CurrencyType.ELIXIR) "Dầu" else "Vàng"}!",
                    LogLevel.SUCCESS,
                )
            } else {
                BotStateStore.log("Dừng nâng Tường vì không đủ Vàng/Dầu hoặc không thấy nút xác nhận.", LogLevel.INFO)
                ctx.tap(cal.dismissAreaPoint, delayAfter = false)
                ctx.sleep(400L)
                break
            }

            ctx.tap(cal.dismissAreaPoint, delayAfter = false)
            ctx.sleep(420L)
            val refreshed = readHomeSnapshot(ctx) ?: break
            currentHome = refreshed.first
            currentBuilders = refreshed.second
            BotStateStore.updateStats {
                it.copy(lastHomeResources = currentHome, lastBuilderStatus = currentBuilders)
            }
        }
        return currentHome to currentBuilders
    }

    /**
     * Locates the bottom "Nâng cấp" / "Upgrade" button via OCR (respecting Gold vs Elixir for Walls),
     * then verifies and taps the confirmation button in the modal — aborting if cost is red or Gems!
     */
    private suspend fun tapUpgradeActionAndConfirm(
        ctx: BotExecutionContext,
        preferredCurrency: CurrencyType,
        isWall: Boolean = false,
    ): Boolean {
        val cal = ctx.calibration
        val actionTap = ctx.withFrame { frame ->
            val ocr = ctx.ocrEngine.recognizeRoi(frame, bottomActionStripRoi, enhance = true)
            val upgradeButtons = ocr.lines.filter { line ->
                val norm = ResourceParser.normalizeForMatch(line.text)
                norm.contains("nang cap") || norm.contains("upgrade")
            }
            if (upgradeButtons.isEmpty()) {
                null
            } else if (upgradeButtons.size == 1) {
                upgradeButtons.first().center
            } else {
                // Multiple Upgrade buttons (e.g. Wall Gold + Wall Elixir): match by currency icon color above label
                upgradeButtons.firstOrNull { btn ->
                    val probeRect = NormRect(
                        left = (btn.bounds.left - 0.01f).coerceIn(0f, 0.94f),
                        top = (btn.bounds.top - 0.06f).coerceIn(0.68f, 0.92f),
                        right = (btn.bounds.right + 0.02f).coerceIn(0.06f, 0.99f),
                        bottom = btn.bounds.top.coerceAtLeast(0.70f),
                    ).normalized()
                    ImageAnalyzer.detectCurrencyForCost(frame, probeRect) == preferredCurrency
                }?.center ?: if (isWall && preferredCurrency == CurrencyType.ELIXIR) {
                    upgradeButtons.maxByOrNull { it.bounds.centerX }?.center
                } else {
                    upgradeButtons.minByOrNull { it.bounds.centerX }?.center
                }
            }
        } ?: if (isWall && preferredCurrency == CurrencyType.ELIXIR) {
            cal.upgradeWallElixirButton
        } else {
            cal.upgradeActionButton
        }

        ctx.tap(actionTap, delayAfter = false)
        ctx.sleep(620L)

        // Inspect confirmation modal before clicking so we NEVER click red unaffordable or Gem buttons!
        val confirmCheck = ctx.withFrame { frame ->
            val ocr = ctx.ocrEngine.recognizeRoi(frame, upgradeModalConfirmRoi, enhance = true)
            val costLines = ocr.lines.filter { line ->
                val n = ResourceParser.parseResourceNumber(line.text)
                n != null && n >= 100L && line.text.count { it.isLetter() } <= 2
            }
            if (costLines.isEmpty()) {
                // Fallback: look for explicit confirm text ("Xác nhận" / "OK" / "Nâng cấp")
                val textBtn = ocr.lines.firstOrNull { line ->
                    val norm = ResourceParser.normalizeForMatch(line.text)
                    norm.contains("nang cap") || norm.contains("upgrade") || ResourceParser.isConfirmDialogButtonText(line.text)
                }
                return@withFrame Pair(true, textBtn?.center ?: cal.upgradeConfirmButton)
            }
            val safeCostBtn = costLines.firstOrNull { line ->
                val cur = ImageAnalyzer.detectCurrencyForCost(frame, line.bounds)
                val isRed = ImageAnalyzer.isCostTextRedUnaffordable(frame, line.bounds)
                cur != CurrencyType.GEMS && !isRed
            }
            if (safeCostBtn != null) {
                Pair(true, safeCostBtn.center)
            } else {
                // Every visible price button in modal was red or Gems -> abort!
                Pair(false, null)
            }
        } ?: Pair(true, cal.upgradeConfirmButton)

        if (!confirmCheck.first || confirmCheck.second == null) {
            return false
        }
        ctx.tap(confirmCheck.second!!, delayAfter = false)
        ctx.sleep(700L)
        return true
    }

    private suspend fun scrollBuilderSidebarDown(ctx: BotExecutionContext) {
        val roi = ctx.calibration.builderSuggestionsRoi
        ctx.swipe(
            from = NormPoint(roi.centerX, (roi.bottom - 0.10f).coerceIn(0.50f, 0.84f)),
            to = NormPoint(roi.centerX, (roi.top + 0.14f).coerceIn(0.14f, 0.40f)),
            durationMs = 340L,
        )
        ctx.sleep(360L)
    }

    private suspend fun closeBuilderSidebar(ctx: BotExecutionContext) {
        ctx.tap(sidebarCloseButton, delayAfter = false)
        ctx.sleep(280L)
        ctx.tap(ctx.calibration.dismissAreaPoint, delayAfter = false)
        ctx.sleep(220L)
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

        ctx.tap(cal.labShortcutPoint, delayAfter = false)
        ctx.sleep(750L)

        val candidates = scanUpgradeDrawer(ctx, cal.labMenuRoi)
        val best = ResourceParser.selectBestLabUpgrade(candidates, homeRes, cfg)
        if (best == null) {
            BotStateStore.log("Lab đang bận hoặc chưa có lính/phép đủ tài nguyên theo bộ lọc.", LogLevel.INFO)
            closeBuilderSidebar(ctx)
            return
        }

        BotStateStore.log("Bắt đầu nâng Lab: ${best.name} (giá ~${best.cost})", LogLevel.INFO)
        ctx.tap(best.tapPoint, delayAfter = false)
        ctx.sleep(700L)
        ctx.tap(cal.labStartButton, delayAfter = false)
        ctx.sleep(650L)
        ctx.tap(cal.dismissAreaPoint, delayAfter = false)
        ctx.sleep(400L)

        BotStateStore.updateStats { it.copy(labUpgradesStarted = it.labUpgradesStarted + 1) }
        BotStateStore.log("Đã gửi lệnh nghiên cứu ${best.name} trong Lab!", LogLevel.SUCCESS)
    }

    private suspend fun scanUpgradeDrawer(
        ctx: BotExecutionContext,
        roi: NormRect,
    ) = ctx.withFrame { frame ->
        val ocr = ctx.ocrEngine.recognizeRoi(frame, roi, enhance = true)
        ResourceParser.parseUpgradeCandidates(
            lines = ocr.lines,
            currencyResolver = { costLine -> ImageAnalyzer.detectCurrencyForCost(frame, costLine.bounds) },
            redCostResolver = { costLine -> ImageAnalyzer.isCostTextRedUnaffordable(frame, costLine.bounds) },
        )
    }.orEmpty()

    private suspend fun readHomeSnapshot(ctx: BotExecutionContext): Pair<HomeResources, BuilderStatus>? {
        return ctx.withFrame { frame ->
            val res = ctx.screenDetector.readHomeResources(frame, ctx.calibration)
            val builders = ctx.screenDetector.readBuilderStatus(frame, ctx.calibration)
            res to builders
        }
    }
}
