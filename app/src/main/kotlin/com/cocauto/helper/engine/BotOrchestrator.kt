package com.cocauto.helper.engine

import com.cocauto.helper.data.ConfigStorage
import com.cocauto.helper.model.BotRunMode
import com.cocauto.helper.model.BotRunStatus
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.FullStorageAction
import com.cocauto.helper.model.GameScreenState
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.vision.AutoCalibrationEngine
import com.cocauto.helper.vision.ResourceParser
import com.cocauto.helper.vision.ScreenStateDetector
import kotlinx.coroutines.CancellationException

/**
 * Top-level automation state machine combining:
 * - Automatic first-frame screen UI self-calibration (zero manual dragging needed!)
 * - Session Watchdog (auto-relaunch crashed game, auto-reload disconnect popups, safe break wait)
 * - Home Village Farming + Auto Upgrades (Walls, Buildings, Lab)
 * - Builder Base Farming (standalone or alternating with Home Village)
 */
class BotOrchestrator(
    private val homeFarmEngine: HomeFarmEngine = HomeFarmEngine(),
    private val upgradeEngine: AutoUpgradeEngine = AutoUpgradeEngine(),
    private val builderBaseEngine: BuilderBaseFarmEngine = BuilderBaseFarmEngine(),
) {
    private var completedHomeCycles = 0
    private var autoCalibratedHomeThisSession = false

    suspend fun runMainLoop(ctx: BotExecutionContext) {
        completedHomeCycles = 0
        autoCalibratedHomeThisSession = false
        BotStateStore.log("Khởi động vòng lặp Auto Clash of Clans (${ctx.config.runMode.name}).", LogLevel.SUCCESS)

        while (true) {
            try {
                ctx.ensureRunning()

                // Step 1: Ensure Clash of Clans is in foreground (via ADB dumpsys or Accessibility events)
                val fgPkg = ctx.readForegroundPackage()
                if (fgPkg.isNotBlank() && fgPkg != ScreenStateDetector.COC_PACKAGE) {
                    if (ctx.config.safety.autoRelaunchGame) {
                        BotStateStore.updateRuntime {
                            it.copy(
                                status = BotRunStatus.WAITING_GAME,
                                screenState = GameScreenState.NOT_IN_GAME,
                                currentAction = "Đang mở lại Clash of Clans...",
                            )
                        }
                        val launched = ctx.launchClashOfClans()
                        ctx.sleep(if (launched) 6_000L else 5_000L)
                        if (BotStateStore.runtime.value.status == BotRunStatus.WAITING_GAME) {
                            BotStateStore.updateRuntime { it.copy(status = BotRunStatus.RUNNING) }
                        }
                        continue
                    } else {
                        BotStateStore.setAction("Hãy mở Clash of Clans để auto tiếp tục", GameScreenState.NOT_IN_GAME)
                        ctx.sleep(2_000L)
                        continue
                    }
                }

                // Step 2: Classify current screen state and handle recovery dialogs first
                val analysis = ctx.withFrame { frame ->
                    ctx.screenDetector.analyzeScreen(frame, ctx.calibration, fgPkg)
                }

                if (analysis == null) {
                    BotStateStore.setAction("Chưa chụp được ảnh qua ${ctx.config.bridge.mode.label} — kiểm tra kết nối ADB")
                    ctx.sleep(1_200L)
                    continue
                }

                when (analysis.state) {
                    GameScreenState.LOADING, GameScreenState.SEARCHING_CLOUDS -> {
                        BotStateStore.setAction("Đang chờ game tải...", analysis.state)
                        ctx.sleep(700L)
                        continue
                    }

                    GameScreenState.DISCONNECTED_DIALOG -> {
                        if (ctx.config.safety.autoReloadOnDisconnect) {
                            BotStateStore.log("Phát hiện mất kết nối — tự động bấm Tải lại (Reload).", LogLevel.WARN)
                            BotStateStore.setAction("Khôi phục kết nối game...", GameScreenState.DISCONNECTED_DIALOG)
                            ctx.tap(analysis.suggestedTapPoint ?: NormPoint(0.50f, 0.62f), delayAfter = false)
                            BotStateStore.updateStats { it.copy(reconnectRecoveries = it.reconnectRecoveries + 1) }
                            ctx.sleep(4_500L)
                        } else {
                            BotStateStore.setAction("Mất kết nối (tắt tự Reload)", GameScreenState.DISCONNECTED_DIALOG)
                            ctx.sleep(2_000L)
                        }
                        continue
                    }

                    GameScreenState.PERSONAL_BREAK -> {
                        val pauseMins = ctx.config.safety.personalBreakPauseMinutes.coerceIn(1, 60)
                        BotStateStore.log("Phát hiện nghỉ bắt buộc / thiết bị khác — tạm nghỉ $pauseMins phút.", LogLevel.WARN)
                        BotStateStore.setAction("Nghỉ an toàn $pauseMins phút...", GameScreenState.PERSONAL_BREAK)
                        ctx.sleep(pauseMins * 60_000L)
                        ctx.tap(NormPoint(0.50f, 0.62f), delayAfter = false)
                        ctx.sleep(3_000L)
                        continue
                    }

                    GameScreenState.BATTLE_SCOUT -> {
                        BotStateStore.setAction("Đang ở màn hình soi nhà — kiểm tra & tự đánh!", GameScreenState.BATTLE_SCOUT)
                        homeFarmEngine.handleCurrentScoutAndAttack(ctx)
                        continue
                    }

                    GameScreenState.ATTACK_MENU -> {
                        BotStateStore.setAction("Đang ở menu Chiến đấu — tìm trận ngay!", GameScreenState.ATTACK_MENU)
                        homeFarmEngine.runAttackCycle(ctx, alreadyInAttackMenu = true)
                        continue
                    }

                    GameScreenState.BATTLE_SUMMARY -> {
                        val earned = ResourceParser.parseBattleSummaryLoot(analysis.fullLines)
                        BotStateStore.updateStats { s ->
                            s.copy(
                                attacksCompleted = s.attacksCompleted + 1,
                                estimatedGoldLooted = s.estimatedGoldLooted + earned.gold,
                                estimatedElixirLooted = s.estimatedElixirLooted + earned.elixir,
                                estimatedDarkElixirLooted = s.estimatedDarkElixirLooted + earned.darkElixir,
                            )
                        }
                        BotStateStore.setAction("Bấm Về làng sau trận", GameScreenState.BATTLE_SUMMARY)
                        ctx.tap(analysis.suggestedTapPoint ?: ctx.calibration.returnHomeButton, delayAfter = false)
                        ctx.sleep(900L)
                        continue
                    }

                    GameScreenState.POPUP_DIALOG -> {
                        BotStateStore.setAction("Đóng hộp thoại thông báo", GameScreenState.POPUP_DIALOG)
                        ctx.tap(analysis.suggestedTapPoint ?: NormPoint(0.50f, 0.72f), delayAfter = false)
                        ctx.sleep(420L)
                        continue
                    }

                    else -> Unit
                }

                // Step 2.5: Instant zero-extra-capture UI auto-calibration directly from Step 2 OCR lines!
                if (!autoCalibratedHomeThisSession && ctx.config.safety.autoCalibrateScreen) {
                    if (analysis.fullLines.isNotEmpty()) {
                        val (sw, sh) = ctx.bridgeManager.activeAdbBridge()?.lastCapturedPhysicalSize() ?: (2400 to 1080)
                        val quickCal = AutoCalibrationEngine.applyDetectedOcr(ctx.calibration, analysis.fullLines, sw, sh)
                        if (quickCal.hasDetections) {
                            autoCalibratedHomeThisSession = true
                            BotStateStore.updateCalibration(quickCal.profile)
                            ConfigStorage(ctx.appContext).saveCalibration(quickCal.profile)
                            BotStateStore.updateRuntime { it.copy(lastOcrTestSummary = quickCal.summaryText()) }
                            BotStateStore.log(quickCal.summaryText(), LogLevel.SUCCESS)
                        } else {
                            runSilentAutoCalibration(ctx)
                        }
                    } else {
                        runSilentAutoCalibration(ctx)
                    }
                }

                // Step 3: Run mode-specific automation cycle
                when (ctx.config.runMode) {
                    BotRunMode.HOME_ONLY -> {
                        executeHomeCycle(ctx)
                    }

                    BotRunMode.BUILDER_BASE_ONLY -> {
                        builderBaseEngine.runBuilderBaseCycle(ctx, travelFromHome = false)
                    }

                    BotRunMode.ALTERNATE_BOTH -> {
                        executeHomeCycle(ctx)
                        completedHomeCycles++
                        val interval = ctx.config.safety.homeCyclesBeforeBuilderBase.coerceAtLeast(1)
                        if (ctx.config.builderBase.enabled && completedHomeCycles % interval == 0) {
                            builderBaseEngine.runBuilderBaseCycle(ctx, travelFromHome = true)
                        }
                    }
                }

                ctx.sleep(350L)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                BotStateStore.log("Lỗi tạm thời trong vòng lặp auto: ${e.message}", LogLevel.ERROR)
                ctx.sleep(2_500L)
            }
        }
    }

    private suspend fun runSilentAutoCalibration(ctx: BotExecutionContext) {
        BotStateStore.setAction("Đang tự nhận diện vị trí nút & tài nguyên...")
        ctx.setHudTemporarilyHidden(true)
        try {
            ctx.sleep(140L)
            val result = ctx.withFrame { frame ->
                AutoCalibrationEngine.detectFromFrame(frame, ctx.calibration, ctx.ocrEngine)
            }
            if (result != null && result.hasDetections) {
                autoCalibratedHomeThisSession = true
                BotStateStore.updateCalibration(result.profile)
                ConfigStorage(ctx.appContext).saveCalibration(result.profile)
                BotStateStore.updateRuntime { it.copy(lastOcrTestSummary = result.summaryText()) }
                BotStateStore.log(result.summaryText(), LogLevel.SUCCESS)
            }
        } finally {
            ctx.setHudTemporarilyHidden(false)
        }
    }

    private suspend fun executeHomeCycle(ctx: BotExecutionContext) {
        // 1. Fast batched collector harvest (runs 1st cycle and every 3rd cycle)
        homeFarmEngine.collectHomeResources(ctx)

        // 2. Single-frame parallel snapshot (Resources + Fill% + Builders + Lab) shared across Upgrade & Full-Storage checks!
        val snapshot = upgradeEngine.readFullHomeSnapshot(ctx)
        val afterUpgrade = upgradeEngine.runUpgradeCycle(ctx, preSnapshot = snapshot) ?: snapshot

        // 3. Always update live Home Village stats (resources, storage %, builders) + check full-storage gate
        if (afterUpgrade != null) {
            val homeRes = afterUpgrade.resources
            val fill = afterUpgrade.storageFill
            val builders = afterUpgrade.builders
            BotStateStore.updateStats {
                it.copy(
                    lastHomeResources = if (homeRes.gold > 0L || homeRes.elixir > 0L) homeRes else it.lastHomeResources,
                    lastStorageFill = if (fill.goldPercent > 0 || fill.elixirPercent > 0) fill else it.lastStorageFill,
                    lastBuilderStatus = if (builders.isValid) builders else it.lastBuilderStatus,
                )
            }
            val homeCfg = ctx.config.homeFarm
            if (ResourceParser.isStorageFull(homeRes, fill, homeCfg)) {
                val fillInfo = "Vàng ${fill.goldPercent}% | Dầu ${fill.elixirPercent}%"
                when (homeCfg.fullStorageAction) {
                    FullStorageAction.STOP_BOT -> {
                        val msg = "Kho tài nguyên đã đầy ($fillInfo) — Đã tự dừng Auto Farm!"
                        BotStateStore.log(msg, LogLevel.SUCCESS)
                        BotStateStore.updateRuntime {
                            it.copy(status = BotRunStatus.STOPPED, currentAction = msg)
                        }
                        throw CancellationException("Storages full — auto stopped")
                    }

                    FullStorageAction.PAUSE_WAIT_BUILDER -> {
                        val waitMins = homeCfg.fullStorageRecheckMinutes.coerceIn(1, 60)
                        val msg = "Kho đầy ($fillInfo), tạm nghỉ ${waitMins}p chờ Thợ xây rảnh nâng cấp..."
                        BotStateStore.log(msg, LogLevel.WARN)
                        BotStateStore.setAction(msg, GameScreenState.HOME_VILLAGE)
                        ctx.sleep(waitMins * 60_000L)
                        return
                    }
                }
            }
        }

        // 4. Find target base & attack rapidly
        homeFarmEngine.runAttackCycle(ctx)

        // 5. Refresh post-battle resources & builders immediately so dashboard stats are always live!
        val postBattleSnap = upgradeEngine.readFullHomeSnapshot(ctx)
        if (postBattleSnap != null) {
            BotStateStore.updateStats {
                it.copy(
                    lastHomeResources = if (postBattleSnap.resources.gold > 0L || postBattleSnap.resources.elixir > 0L) {
                        postBattleSnap.resources
                    } else {
                        it.lastHomeResources
                    },
                    lastStorageFill = if (postBattleSnap.storageFill.goldPercent > 0 || postBattleSnap.storageFill.elixirPercent > 0) {
                        postBattleSnap.storageFill
                    } else {
                        it.lastStorageFill
                    },
                    lastBuilderStatus = if (postBattleSnap.builders.isValid) postBattleSnap.builders else it.lastBuilderStatus,
                )
            }
            if (!postBattleSnap.builders.isValid || postBattleSnap.builders.freeBuilders > 0) {
                upgradeEngine.runUpgradeCycle(ctx, preSnapshot = postBattleSnap)
            }
        }
    }
}
