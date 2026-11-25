package com.cocauto.helper.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cocauto.helper.MainActivity
import com.cocauto.helper.R
import com.cocauto.helper.bridge.DeviceBridgeManager
import com.cocauto.helper.data.ConfigStorage
import com.cocauto.helper.engine.BotExecutionContext
import com.cocauto.helper.engine.BotOrchestrator
import com.cocauto.helper.model.BotRunStatus
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.ControlBackendMode
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.overlay.CalibrationOverlayManager
import com.cocauto.helper.overlay.FloatingHudManager
import com.cocauto.helper.vision.AutoCalibrationEngine
import com.cocauto.helper.vision.AutoCalibrationResult
import com.cocauto.helper.vision.OcrEngine
import com.cocauto.helper.vision.ScreenStateDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service owning:
 * - ADB Bridge execution (Shizuku / Local ADB TCP / PC HTTP ADB) — ZERO AccessibilityService required!
 * - Standalone TYPE_APPLICATION_OVERLAY Floating Bubble Icon & Calibration Overlay
 * - Optional legacy MediaProjection & Accessibility fallback
 * - On-device ML Kit OCR engine & BotOrchestrator coroutine loop
 */
class BotForegroundService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var windowManager: WindowManager
    private lateinit var bridgeManager: DeviceBridgeManager
    private lateinit var captureManager: ScreenCaptureManager
    private lateinit var ocrEngine: OcrEngine
    private lateinit var screenDetector: ScreenStateDetector
    private lateinit var configStorage: ConfigStorage

    private var appOverlayHud: FloatingHudManager? = null
    private var appOverlayCalibration: CalibrationOverlayManager? = null

    private val orchestrator = BotOrchestrator()
    private var botJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configStorage = ConfigStorage(applicationContext)
        bridgeManager = DeviceBridgeManager.getInstance(applicationContext)
        captureManager = ScreenCaptureManager(applicationContext)
        ocrEngine = OcrEngine()
        screenDetector = ScreenStateDetector(ocrEngine)

        BotStateStore.updateConfig(configStorage.loadConfig())
        BotStateStore.updateCalibration(configStorage.loadCalibration())
        createNotificationChannel()
        initAppOverlays()
    }

    private fun initAppOverlays() {
        appOverlayHud = FloatingHudManager(
            context = this,
            windowManager = windowManager,
            overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            onToggleRunPause = { toggleRunOrPause(this) },
            onStopBot = { stopBot(this) },
            onToggleCalibration = { toggleCalibrationOverlayInternal(autoDetect = false) },
            onOpenApp = { openMainApp() },
        )

        appOverlayCalibration = CalibrationOverlayManager(
            context = this,
            windowManager = windowManager,
            overlayWindowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            onSaveCalibration = { updated ->
                BotStateStore.updateCalibration(updated)
                configStorage.saveCalibration(updated)
            },
            onRunAutoCalibrate = { profile, callback ->
                performAutoCalibration(profile, callback)
            },
            onRunLiveOcrTest = { profile, callback ->
                performLiveOcrDiagnostic(profile, callback)
            },
            onSetOtherOverlaysHidden = { hidden ->
                setAllHudsHidden(hidden)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_ADB_BRIDGE -> {
                val autoStartBot = intent.getBooleanExtra(EXTRA_AUTO_START_BOT, true)
                val showHud = intent.getBooleanExtra(EXTRA_SHOW_HUD, true)
                val openCalib = intent.getBooleanExtra(EXTRA_OPEN_CALIBRATION, false)
                enterForeground("CoC Auto (ADB Cầu nối: ${BotStateStore.config.value.bridge.mode.label})", useMediaProjectionType = false)
                if (showHud) showBestFloatingHud()
                if (openCalib) showBestCalibrationOverlay(autoDetectImmediately = true)
                if (autoStartBot) {
                    startBotLoop()
                }
            }

            ACTION_TOGGLE_FLOATING_HUD -> {
                enterForeground("CoC Auto — Icon nổi sẵn sàng", useMediaProjectionType = false)
                toggleBestFloatingHud()
            }

            ACTION_START_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val autoStartBot = intent.getBooleanExtra(EXTRA_AUTO_START_BOT, false)

                enterForeground("Đã sẵn sàng chụp màn hình & chờ lệnh chạy", useMediaProjectionType = true)

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    val started = captureManager.start(resultCode, resultData)
                    if (started || canUseAccessibilityCapture()) {
                        showBestFloatingHud()
                        if (autoStartBot) startBotLoop()
                    } else {
                        stopSelfSafely()
                    }
                } else if (canUseAccessibilityCapture()) {
                    showBestFloatingHud()
                    if (autoStartBot) startBotLoop()
                } else {
                    BotStateStore.log("Quyền chụp màn hình không hợp lệ.", LogLevel.ERROR)
                    stopSelfSafely()
                }
            }

            ACTION_START_ACCESSIBILITY_ENGINE -> {
                val autoStartBot = intent.getBooleanExtra(EXTRA_AUTO_START_BOT, true)
                enterForeground("CoC Auto đang chạy (Trợ năng Android 11+)", useMediaProjectionType = false)
                showBestFloatingHud()
                if (autoStartBot) startBotLoop()
            }

            ACTION_TOGGLE_RUN_PAUSE -> {
                when (BotStateStore.runtime.value.status) {
                    BotRunStatus.RUNNING, BotRunStatus.WAITING_GAME -> {
                        BotStateStore.updateRuntime {
                            it.copy(status = BotRunStatus.PAUSED, currentAction = "Đã tạm dừng")
                        }
                        BotStateStore.log("Đã tạm dừng auto.", LogLevel.WARN)
                        updateNotification("Auto đang tạm dừng")
                    }

                    BotRunStatus.PAUSED -> {
                        BotStateStore.updateRuntime {
                            it.copy(status = BotRunStatus.RUNNING, currentAction = "Đang tiếp tục...")
                        }
                        BotStateStore.log("Tiếp tục chạy auto.", LogLevel.INFO)
                        updateNotification("Auto Clash of Clans đang chạy")
                    }

                    BotRunStatus.STOPPED, BotRunStatus.CALIBRATING -> {
                        startBotLoop()
                    }
                }
            }

            ACTION_STOP_BOT -> {
                stopBotLoop()
            }

            ACTION_SHUTDOWN_SERVICE -> {
                stopSelfSafely()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopBotLoop()
        bridgeManager.disconnectAll()
        captureManager.stop()
        ocrEngine.close()
        releaseWakeLock()
        mainHandler.post {
            appOverlayCalibration?.hide()
            appOverlayHud?.hide()
        }
        CocAccessibilityService.instance?.hideFloatingHud()
        CocAccessibilityService.instance?.hideCalibrationOverlay()
        serviceScope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun canDrawAppOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun showBestFloatingHud() {
        mainHandler.post {
            if (canDrawAppOverlay()) {
                appOverlayHud?.show(startExpanded = false)
            } else {
                CocAccessibilityService.instance?.showFloatingHud(startExpanded = false)
            }
        }
    }

    private fun toggleBestFloatingHud() {
        mainHandler.post {
            if (canDrawAppOverlay()) {
                val hud = appOverlayHud ?: return@post
                if (hud.isShowing) hud.hide() else hud.show(startExpanded = false)
            } else {
                CocAccessibilityService.instance?.toggleFloatingHud()
            }
        }
    }

    private fun showBestCalibrationOverlay(autoDetectImmediately: Boolean) {
        mainHandler.post {
            if (canDrawAppOverlay()) {
                appOverlayCalibration?.show(BotStateStore.calibration.value, autoDetectImmediately)
            } else {
                CocAccessibilityService.instance?.showCalibrationOverlay(autoDetectImmediately)
            }
        }
    }

    private fun toggleCalibrationOverlayInternal(autoDetect: Boolean) {
        mainHandler.post {
            if (canDrawAppOverlay()) {
                val cal = appOverlayCalibration ?: return@post
                if (cal.isShowing) cal.hide() else cal.show(BotStateStore.calibration.value, autoDetect)
            } else {
                CocAccessibilityService.instance?.toggleCalibrationOverlay()
            }
        }
    }

    private fun setAllHudsHidden(hidden: Boolean) {
        mainHandler.post {
            appOverlayHud?.setTemporarilyHidden(hidden)
            CocAccessibilityService.instance?.setHudTemporarilyHidden(hidden)
        }
    }

    private fun openMainApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    private fun canUseAccessibilityCapture(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && CocAccessibilityService.instance != null
    }

    private fun startBotLoop() {
        val backendMode = BotStateStore.config.value.bridge.mode
        if (backendMode == ControlBackendMode.ACCESSIBILITY_LEGACY) {
            if (CocAccessibilityService.instance == null) {
                BotStateStore.log("Chế độ Trợ năng: Chưa bật Dịch vụ Trợ năng.", LogLevel.ERROR)
                return
            }
            if (!captureManager.isReady && !canUseAccessibilityCapture()) {
                BotStateStore.log("Chưa cấp quyền chụp màn hình.", LogLevel.ERROR)
                return
            }
        }
        if (botJob?.isActive == true) return

        acquireWakeLockIfNeeded()
        showBestFloatingHud()
        BotStateStore.resetStats()
        BotStateStore.updateRuntime {
            it.copy(status = BotRunStatus.RUNNING, currentAction = "Đang kết nối ${backendMode.label}...")
        }
        updateNotification("CoC Auto đang chạy (${backendMode.label})")

        val execCtx = BotExecutionContext(
            appContext = applicationContext,
            bridgeManager = bridgeManager,
            captureManager = captureManager,
            ocrEngine = ocrEngine,
            screenDetector = screenDetector,
            onSetHudHidden = { hidden -> setAllHudsHidden(hidden) },
        )

        botJob = serviceScope.launch {
            try {
                if (backendMode != ControlBackendMode.ACCESSIBILITY_LEGACY) {
                    val check = bridgeManager.autoConnect(reason = "bắt đầu auto", forceReconnect = false)
                    if (check.isFailure) {
                        BotStateStore.log(
                            "Không kết nối được ${backendMode.label}: ${check.exceptionOrNull()?.message}",
                            LogLevel.ERROR,
                        )
                        return@launch
                    }
                }
                orchestrator.runMainLoop(execCtx)
            } catch (_: CancellationException) {
                BotStateStore.log("Đã dừng vòng lặp auto.", LogLevel.INFO)
            } finally {
                releaseWakeLock()
                BotStateStore.updateRuntime {
                    it.copy(status = BotRunStatus.STOPPED, currentAction = "Đã dừng")
                }
                updateNotification("CoC Auto đang chờ lệnh")
            }
        }
    }

    private fun stopBotLoop() {
        botJob?.cancel()
        botJob = null
        releaseWakeLock()
        BotStateStore.updateRuntime {
            it.copy(status = BotRunStatus.STOPPED, currentAction = "Đã dừng")
        }
        updateNotification("CoC Auto đã dừng")
    }

    private fun stopSelfSafely() {
        stopBotLoop()
        bridgeManager.disconnectAll()
        captureManager.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun captureDiagnosticFrame(): Bitmap? {
        val adbBridge = bridgeManager.ensureConnected()
        if (adbBridge != null) {
            adbBridge.captureScreen()?.let { return it }
        }
        if (captureManager.isReady) {
            captureManager.captureFrame(maxRetries = 3)?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            CocAccessibilityService.instance?.takeScreenshotBitmap()?.let { return it }
        }
        return null
    }

    private fun performAutoCalibration(
        currentProfile: CalibrationProfile,
        onResult: (AutoCalibrationResult?) -> Unit,
    ) {
        serviceScope.launch {
            val frame = captureDiagnosticFrame()
            if (frame == null) {
                onResult(null)
                return@launch
            }
            try {
                val result = AutoCalibrationEngine.detectFromFrame(frame, currentProfile, ocrEngine)
                if (result.hasDetections) {
                    BotStateStore.updateCalibration(result.profile)
                    configStorage.saveCalibration(result.profile)
                    BotStateStore.updateRuntime { it.copy(lastOcrTestSummary = result.summaryText()) }
                    BotStateStore.log(result.summaryText(), LogLevel.SUCCESS)
                } else {
                    BotStateStore.log(result.summaryText(), LogLevel.WARN)
                }
                onResult(result)
            } finally {
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }

    private fun performLiveOcrDiagnostic(
        profile: CalibrationProfile,
        onResult: (String) -> Unit,
    ) {
        serviceScope.launch {
            val frame = captureDiagnosticFrame()
            if (frame == null) {
                onResult("Không chụp được ảnh từ ${BotStateStore.config.value.bridge.mode.label}. Kiểm tra kết nối ADB!")
                return@launch
            }
            try {
                val fg = bridgeManager.activeAdbBridge()?.getForegroundPackage().orEmpty()
                    .ifBlank { BotStateStore.runtime.value.foregroundPackage }
                val state = screenDetector.analyzeScreen(frame, profile, fg)
                val homeRes = screenDetector.readHomeResources(frame, profile)
                val fill = screenDetector.readStorageFillLevels(frame, profile)
                val builders = screenDetector.readBuilderStatus(frame, profile)
                val loot = screenDetector.readEnemyLoot(frame, profile)
                val summary = buildString {
                    append("Ảnh ${frame.width}x${frame.height} | ")
                    append("Màn: ${state.state.label} | ")
                    append("Thợ: ${builders.freeBuilders}/${builders.totalBuilders} | ")
                    append("Nhà(V/D): ${homeRes.gold}(${fill.goldPercent}%)/${homeRes.elixir}(${fill.elixirPercent}%) | ")
                    append("Loot: ${loot.gold}/${loot.elixir}/${loot.darkElixir}")
                }
                BotStateStore.updateRuntime { it.copy(lastOcrTestSummary = summary) }
                BotStateStore.log("OCR Test: $summary", LogLevel.INFO)
                onResult(summary)
            } finally {
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }

    private fun acquireWakeLockIfNeeded() {
        if (!BotStateStore.config.value.safety.keepScreenAwake) return
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CoCAuto::EngineWakeLock").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) runCatching { it.release() }
        }
        wakeLock = null
    }

    private fun enterForeground(statusText: String, useMediaProjectionType: Boolean) {
        val notification = buildNotification(statusText)
        val type = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                if (useMediaProjectionType) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                if (useMediaProjectionType) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            }

            else -> 0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun updateNotification(statusText: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(statusText))
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BotForegroundService::class.java).setAction(ACTION_TOGGLE_RUN_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, BotForegroundService::class.java).setAction(ACTION_STOP_BOT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CoC Auto Helper (ADB)")
            .setContentText(statusText)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Chạy / Tạm dừng", toggleIntent)
            .addAction(0, "Dừng hẳn", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CoC Auto Service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Dịch vụ chạy tự động Clash of Clans qua ADB cầu nối"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "coc_auto_engine_channel"
        private const val NOTIF_ID = 4201

        const val ACTION_START_ADB_BRIDGE = "com.cocauto.helper.ACTION_START_ADB_BRIDGE"
        const val ACTION_TOGGLE_FLOATING_HUD = "com.cocauto.helper.ACTION_TOGGLE_FLOATING_HUD"
        const val ACTION_START_PROJECTION = "com.cocauto.helper.ACTION_START_PROJECTION"
        const val ACTION_START_ACCESSIBILITY_ENGINE = "com.cocauto.helper.ACTION_START_ACCESSIBILITY_ENGINE"
        const val ACTION_TOGGLE_RUN_PAUSE = "com.cocauto.helper.ACTION_TOGGLE_RUN_PAUSE"
        const val ACTION_STOP_BOT = "com.cocauto.helper.ACTION_STOP_BOT"
        const val ACTION_SHUTDOWN_SERVICE = "com.cocauto.helper.ACTION_SHUTDOWN_SERVICE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_AUTO_START_BOT = "extra_auto_start_bot"
        const val EXTRA_SHOW_HUD = "extra_show_hud"
        const val EXTRA_OPEN_CALIBRATION = "extra_open_calibration"

        @Volatile
        private var instance: BotForegroundService? = null

        fun startAdbBridge(
            context: Context,
            autoStartBot: Boolean = true,
            showHud: Boolean = true,
            openCalibration: Boolean = false,
        ) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_START_ADB_BRIDGE
                putExtra(EXTRA_AUTO_START_BOT, autoStartBot)
                putExtra(EXTRA_SHOW_HUD, showHud)
                putExtra(EXTRA_OPEN_CALIBRATION, openCalibration)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun toggleFloatingHud(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_TOGGLE_FLOATING_HUD
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startWithProjection(
            context: Context,
            resultCode: Int,
            data: Intent,
            autoStartBot: Boolean,
        ) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_AUTO_START_BOT, autoStartBot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startWithAccessibilityCapture(
            context: Context,
            autoStartBot: Boolean,
        ) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_START_ACCESSIBILITY_ENGINE
                putExtra(EXTRA_AUTO_START_BOT, autoStartBot)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun toggleRunOrPause(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_TOGGLE_RUN_PAUSE
            }
            context.startService(intent)
        }

        fun stopBot(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_STOP_BOT
            }
            context.startService(intent)
        }

        fun shutdown(context: Context) {
            val intent = Intent(context, BotForegroundService::class.java).apply {
                action = ACTION_SHUTDOWN_SERVICE
            }
            context.startService(intent)
        }

        fun runAutoCalibration(
            context: Context,
            currentProfile: CalibrationProfile,
            onResult: (AutoCalibrationResult?) -> Unit,
        ) {
            val svc = instance
            if (svc != null) {
                svc.performAutoCalibration(currentProfile, onResult)
                return
            }
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.Default).launch {
                val sharedBridge = DeviceBridgeManager.getInstance(appCtx)
                val frame = sharedBridge.ensureConnected()?.captureScreen()
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        CocAccessibilityService.instance?.takeScreenshotBitmap()
                    } else {
                        null
                    }
                if (frame == null) {
                    onResult(null)
                    return@launch
                }
                val ocr = OcrEngine()
                try {
                    val result = AutoCalibrationEngine.detectFromFrame(frame, currentProfile, ocr)
                    if (result.hasDetections) {
                        BotStateStore.updateCalibration(result.profile)
                        ConfigStorage(appCtx).saveCalibration(result.profile)
                        BotStateStore.updateRuntime { it.copy(lastOcrTestSummary = result.summaryText()) }
                        BotStateStore.log(result.summaryText(), LogLevel.SUCCESS)
                    }
                    onResult(result)
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    ocr.close()
                }
            }
        }

        fun runLiveOcrTest(context: Context, profile: CalibrationProfile, onResult: (String) -> Unit) {
            val svc = instance
            if (svc != null) {
                svc.performLiveOcrDiagnostic(profile, onResult)
                return
            }
            val appCtx = context.applicationContext
            CoroutineScope(Dispatchers.Default).launch {
                val sharedBridge = DeviceBridgeManager.getInstance(appCtx)
                val activeBridge = sharedBridge.ensureConnected()
                val frame = activeBridge?.captureScreen()
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        CocAccessibilityService.instance?.takeScreenshotBitmap()
                    } else {
                        null
                    }
                if (frame == null) {
                    onResult("Không chụp được ảnh. Hãy kiểm tra kết nối ADB (127.0.0.1:5555 / Shizuku / PC Bridge)!")
                    return@launch
                }
                val ocr = OcrEngine()
                val detector = ScreenStateDetector(ocr)
                try {
                    val fg = activeBridge?.getForegroundPackage().orEmpty()
                        .ifBlank { BotStateStore.runtime.value.foregroundPackage }
                    val state = detector.analyzeScreen(frame, profile, fg)
                    val homeRes = detector.readHomeResources(frame, profile)
                    val fill = detector.readStorageFillLevels(frame, profile)
                    val builders = detector.readBuilderStatus(frame, profile)
                    val loot = detector.readEnemyLoot(frame, profile)
                    val summary = "Ảnh ${frame.width}x${frame.height} | Màn: ${state.state.label} | Thợ: ${builders.freeBuilders}/${builders.totalBuilders} | Nhà(V/D): ${homeRes.gold}(${fill.goldPercent}%)/${homeRes.elixir}(${fill.elixirPercent}%) | Loot: ${loot.gold}/${loot.elixir}/${loot.darkElixir}"
                    BotStateStore.updateRuntime { it.copy(lastOcrTestSummary = summary) }
                    BotStateStore.log("OCR Test: $summary", LogLevel.INFO)
                    onResult(summary)
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                    ocr.close()
                }
            }
        }
    }
}
