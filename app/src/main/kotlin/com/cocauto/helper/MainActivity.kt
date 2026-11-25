package com.cocauto.helper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.service.BotForegroundService
import com.cocauto.helper.service.CocAccessibilityService
import com.cocauto.helper.ui.MainViewModel
import com.cocauto.helper.ui.screens.CalibrationAndLogsTab
import com.cocauto.helper.ui.screens.DashboardTab
import com.cocauto.helper.ui.screens.FarmTab
import com.cocauto.helper.ui.screens.PermissionSummary
import com.cocauto.helper.ui.screens.UpgradeTab
import com.cocauto.helper.ui.theme.ClashGold
import com.cocauto.helper.ui.theme.CoCAutoTheme
import com.cocauto.helper.ui.theme.SlateSurface
import com.cocauto.helper.updater.UpdateReleaseInfo
import com.cocauto.helper.vision.ScreenStateDetector
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private var pendingAutoStartAfterProjection = false
    private var notificationsGranted by mutableStateOf(true)
    private var batteryUnrestricted by mutableStateOf(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val shouldAutoStart = pendingAutoStartAfterProjection
            pendingAutoStartAfterProjection = false
            BotForegroundService.startWithProjection(
                context = this,
                resultCode = result.resultCode,
                data = data,
                autoStartBot = shouldAutoStart,
            )
            if (shouldAutoStart) {
                launchClashOfClansIfInstalled()
            }
        } else {
            pendingAutoStartAfterProjection = false
            BotStateStore.log("Người dùng đã từ chối quyền chụp màn hình.", LogLevel.WARN)
            Toast.makeText(this, "Cần cấp quyền chụp màn hình để chạy OCR!", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshSystemPermissionFlags()

        setContent {
            CoCAutoTheme {
                CoCAutoAppShell()
            }
        }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_REQUEST_PROJECTION_AND_START, false) == true) {
            intent.removeExtra(EXTRA_REQUEST_PROJECTION_AND_START)
            if (!BotStateStore.runtime.value.mediaProjectionReady) {
                requestMediaProjection(autoStart = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSystemPermissionFlags()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun CoCAutoAppShell() {
        val runtime by viewModel.runtime.collectAsStateWithLifecycle()
        val config by viewModel.config.collectAsStateWithLifecycle()
        val calibration by viewModel.calibration.collectAsStateWithLifecycle()
        val logs by viewModel.logs.collectAsStateWithLifecycle()
        val updateState by viewModel.updateState.collectAsStateWithLifecycle()

        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        val accessibilityReady = runtime.accessibilityConnected || isAccessibilityServiceEnabled()
        val captureReady = runtime.mediaProjectionReady ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runtime.accessibilityConnected)

        val permSummary = PermissionSummary(
            accessibilityEnabled = accessibilityReady,
            mediaProjectionReady = captureReady,
            notificationsGranted = notificationsGranted,
            batteryUnrestricted = batteryUnrestricted,
        )

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "CoC Auto Helper",
                            fontWeight = FontWeight.Bold,
                            color = ClashGold,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateSurface),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = SlateSurface) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                        label = { Text("Tổng quan") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
                        label = { Text("Auto Farm") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.Build, contentDescription = null) },
                        label = { Text("Nâng cấp") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                        label = { Text("Hiệu chỉnh") },
                    )
                }
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                when (selectedTab) {
                    0 -> DashboardTab(
                        runtime = runtime,
                        config = config,
                        permissions = permSummary,
                        updateState = updateState,
                        onRunModeChange = { mode ->
                            viewModel.updateConfig { it.copy(runMode = mode) }
                        },
                        onStartOrToggle = { handleStartOrToggle(permSummary) },
                        onStop = { BotForegroundService.stopBot(this@MainActivity) },
                        onToggleFloatingIcon = { toggleFloatingIconOverlay() },
                        onOpenCalibrationInGame = { openCalibrationOverGame() },
                        onRequestAccessibility = { openAccessibilitySettings() },
                        onRequestMediaProjection = { requestMediaProjection(autoStart = false) },
                        onRequestNotifications = { requestNotificationPermission() },
                        onRequestBatteryExemption = { requestIgnoreBatteryOptimizations() },
                        onDownloadUpdate = { release -> handleDownloadAndInstall(release) },
                        onInstallUpdate = { file -> handleInstallApk(file) },
                    )

                    1 -> FarmTab(
                        config = config,
                        onUpdateConfig = { viewModel.updateConfig(it) },
                    )

                    2 -> UpgradeTab(
                        config = config,
                        builderStatus = runtime.stats.lastBuilderStatus,
                        onUpdateConfig = { viewModel.updateConfig(it) },
                    )

                    3 -> CalibrationAndLogsTab(
                        runtime = runtime,
                        config = config,
                        calibration = calibration,
                        logs = logs,
                        currentVersionLabel = viewModel.currentVersionLabel,
                        updateState = updateState,
                        onUpdateConfig = { viewModel.updateConfig(it) },
                        onCheckUpdatesNow = { viewModel.checkForUpdatesNow() },
                        onDownloadUpdate = { release -> handleDownloadAndInstall(release) },
                        onInstallUpdate = { file -> handleInstallApk(file) },
                        onSelectPreset = { viewModel.applyAspectPreset(it) },
                        onOpenCalibrationOverlay = { openCalibrationOverGame() },
                        onRunOcrTestNow = {
                            BotForegroundService.runLiveOcrTest(this@MainActivity, calibration) { summary ->
                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onExportJson = { viewModel.exportJson() },
                        onImportJson = { viewModel.importJson(it) },
                        onClearLogs = { viewModel.clearLogs() },
                    )
                }
            }
        }
    }

    private fun handleStartOrToggle(perms: PermissionSummary) {
        if (!perms.accessibilityEnabled) {
            Toast.makeText(this, "Hãy bật Dịch vụ Trợ năng (CoC Auto) trước!", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        val runtime = BotStateStore.runtime.value
        val canUseAccessibilityScreenshot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runtime.accessibilityConnected
        if (!runtime.mediaProjectionReady && !canUseAccessibilityScreenshot) {
            requestMediaProjection(autoStart = true)
            return
        }
        if (!runtime.mediaProjectionReady && canUseAccessibilityScreenshot) {
            BotForegroundService.startWithAccessibilityCapture(this, autoStartBot = true)
            launchClashOfClansIfInstalled()
            return
        }
        BotForegroundService.toggleRunOrPause(this)
        if (BotStateStore.runtime.value.status != com.cocauto.helper.model.BotRunStatus.PAUSED) {
            launchClashOfClansIfInstalled()
        }
    }

    private fun handleDownloadAndInstall(release: UpdateReleaseInfo) {
        viewModel.downloadUpdate(release) { apkFile ->
            runOnUiThread {
                handleInstallApk(apkFile)
            }
        }
    }

    private fun handleInstallApk(apkFile: File) {
        if (!viewModel.canInstallPackages()) {
            Toast.makeText(
                this,
                "Hãy bật quyền 'Cho phép cài đặt từ nguồn này' cho CoC Auto rồi bấm Cài đặt lại!",
                Toast.LENGTH_LONG,
            ).show()
            runCatching { startActivity(viewModel.buildInstallPermissionIntent()) }
            return
        }
        viewModel.installApk(this, apkFile)
    }

    private fun toggleFloatingIconOverlay() {
        val acc = CocAccessibilityService.instance
        if (acc == null) {
            Toast.makeText(this, "Hãy bật Dịch vụ Trợ năng trước để hiện icon nổi!", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        acc.toggleFloatingHud()
    }

    private fun openCalibrationOverGame() {
        val acc = CocAccessibilityService.instance
        if (acc == null) {
            Toast.makeText(this, "Hãy bật Dịch vụ Trợ năng trước khi nhận diện màn hình!", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        launchClashOfClansIfInstalled()
        acc.showFloatingHud(startExpanded = false)
        acc.showCalibrationOverlay(autoDetectImmediately = true)
    }

    private fun requestMediaProjection(autoStart: Boolean) {
        pendingAutoStartAfterProjection = autoStart
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mgr.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mgr.createScreenCaptureIntent()
        }
        mediaProjectionLauncher.launch(captureIntent)
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationsGranted = true
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun launchClashOfClansIfInstalled() {
        val intent = packageManager.getLaunchIntentForPackage(ScreenStateDetector.COC_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        if (intent != null) {
            runCatching { startActivity(intent) }
        } else {
            Toast.makeText(this, "Chưa thấy Clash of Clans cài trên máy — hãy mở game thủ công.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSystemPermissionFlags() {
        notificationsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains("$packageName/${CocAccessibilityService::class.java.name}") ||
            enabled.contains("$packageName/.service.CocAccessibilityService")
    }

    companion object {
        const val EXTRA_REQUEST_PROJECTION_AND_START = "extra_request_projection_and_start"
    }
}
