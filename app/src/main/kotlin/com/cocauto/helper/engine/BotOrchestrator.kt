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

                // Step 1: Ensure Clash of Clans is in foreground
                val fgPkg = BotStateStore.runtime.value.foregroundPackage
                if (fgPkg != ScreenStateDetector.COC_PACKAGE) {
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
                    BotStateStore.setAction("Chưa chụp được màn hình — hãy tắt rồi bật lại Trợ năng CoC Auto")
                    ctx.sleep(1_200L)
                    continue
                }

                when (analysis.state) {
                    GameScreenState.LOADING, GameScreenState.SEARCHING_CLOUDS -> {
                        BotStateStore.setAction("Đang chờ game tải...", analysis.state)
                        ctx.sleep(1_500L)
                        continue
                    }

                    GameScreenState.DISCONNECTED_DIALOG -> {
                        if (ctx.config.safety.autoReloadOnDisconnect) {
                            BotStateStore.log("Phát hiện mất kết nối — tự động bấm Tải lại (Reload).", LogLevel.WARN)
                            BotStateStore.setAction("Khôi phục kết nối game...", GameScreenState.DISCONNECTED_DIALOG)
                            ctx.tap(analysis.suggestedTapPoint ?: NormPoint(0.50f, 0.62f))
                            BotStateStore.updateStats { it.copy(reconnectRecoveries = it.reconnectRecoveries + 1) }
                            ctx.sleep(6_000L)
                        } else {
                            BotStateStore.setAction("Mất kết nối (tắt tự Reload)", GameScreenState.DISCONNECTED_DIALOG)
                            ctx.sleep(3_000L)
                        }
                        continue
                    }

                    GameScreenState.PERSONAL_BREAK -> {
                        val pauseMins = ctx.config.safety.personalBreakPauseMinutes.coerceIn(1, 60)
                        BotStateStore.log("Phát hiện nghỉ bắt buộc / thiết bị khác — tạm nghỉ $pauseMins phút.", LogLevel.WARN)
                        BotStateStore.setAction("Nghỉ an toàn $pauseMins phút...", GameScreenState.PERSONAL_BREAK)
                        ctx.sleep(pauseMins * 60_000L)
                        ctx.tap(NormPoint(0.50f, 0.62f))
                        ctx.sleep(4_000L)
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
                        BotStateStore.setAction("Bấm Về nhà sau trận", GameScreenState.BATTLE_SUMMARY)
                        ctx.tap(analysis.suggestedTapPoint ?: ctx.calibration.returnHomeButton)
                        ctx.sleep(2_000L)
                        continue
                    }

                    GameScreenState.POPUP_DIALOG -> {
                        BotStateStore.setAction("Đóng hộp thoại thông báo", GameScreenState.POPUP_DIALOG)
                        ctx.tap(analysis.suggestedTapPoint ?: NormPoint(0.50f, 0.72f))
                        ctx.sleep(800L)
                        continue
                    }

                    else -> Unit
                }

                // Step 2.5: Automatic UI self-calibration once per session so user never has to drag boxes manually
                if (!autoCalibratedHomeThisSession && ctx.config.safety.autoCalibrateScreen) {
                    runSilentAutoCalibration(ctx)
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

                ctx.sleep(1_200L)
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
        ctx.accessibility?.setHudTemporarilyHidden(true)
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
            ctx.accessibility?.setHudTemporarilyHidden(false)
        }
    }

    private suspend fun executeHomeCycle(ctx: BotExecutionContext) {
        // 1. Collect mines, collectors, drills & Loot Cart
        homeFarmEngine.collectHomeResources(ctx)

        // 2. Check & run upgrades before attack (spend overflow resources first)
        upgradeEngine.runUpgradeCycle(ctx)

        // 3. Check if Home Village storages are full (after trying to spend on upgrades/walls!)
        val storageSnapshot = ctx.withFrame { frame ->
            val res = ctx.screenDetector.readHomeResources(frame, ctx.calibration)
            val fill = ctx.screenDetector.readStorageFillLevels(frame, ctx.calibration)
            res to fill
        }
        if (storageSnapshot != null) {
            val (homeRes, fill) = storageSnapshot
            BotStateStore.updateStats {
                it.copy(
                    lastHomeResources = homeRes,
                    lastStorageFill = fill,
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

        // 4. Find target base & attack
        homeFarmEngine.runAttackCycle(ctx)

        // 5. Re-check upgrades right after loot comes in
        upgradeEngine.runUpgradeCycle(ctx)
    }
}
