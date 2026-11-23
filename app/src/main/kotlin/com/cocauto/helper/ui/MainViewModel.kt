package com.cocauto.helper.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cocauto.helper.data.ConfigStorage
import com.cocauto.helper.model.AspectPreset
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.BotLogEntry
import com.cocauto.helper.model.BotRuntimeSnapshot
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.updater.AppUpdater
import com.cocauto.helper.updater.UpdateReleaseInfo
import com.cocauto.helper.updater.UpdateUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val storage = ConfigStorage(application)
    private val updater = AppUpdater(application)

    val runtime: StateFlow<BotRuntimeSnapshot> = BotStateStore.runtime
    val config: StateFlow<BotConfig> = BotStateStore.config
    val calibration: StateFlow<CalibrationProfile> = BotStateStore.calibration
    val logs: StateFlow<List<BotLogEntry>> = BotStateStore.logs
    val updateState: StateFlow<UpdateUiState> = updater.state

    val currentVersionLabel: String
        get() = "v${updater.currentVersionName} (Build ${updater.currentVersionCode})"

    init {
        val dm = application.resources.displayMetrics
        val loadedConfig = storage.loadConfig()
        BotStateStore.updateConfig(loadedConfig)
        BotStateStore.updateCalibration(storage.loadCalibration(dm.widthPixels, dm.heightPixels))

        // Auto-check for updates silently on app startup if configured
        if (loadedConfig.update.autoCheckOnStartup && loadedConfig.update.updateSourceUrl.isNotBlank()) {
            viewModelScope.launch {
                updater.checkForUpdates(
                    rawSourceUrl = loadedConfig.update.updateSourceUrl,
                    autoDownload = loadedConfig.update.autoDownloadWhenAvailable,
                    silentIfUnconfigured = true,
                )
            }
        }
    }

    fun updateConfig(transform: (BotConfig) -> BotConfig) {
        val updated = transform(BotStateStore.config.value)
        BotStateStore.updateConfig(updated)
        storage.saveConfig(updated)
    }

    fun applyAspectPreset(preset: AspectPreset) {
        val dm = getApplication<Application>().resources.displayMetrics
        val profile = if (preset == AspectPreset.AUTO) {
            CalibrationProfile.forScreenDimensions(dm.widthPixels, dm.heightPixels)
        } else {
            CalibrationProfile.forPreset(preset)
        }
        BotStateStore.updateCalibration(profile)
        storage.saveCalibration(profile)
        BotStateStore.log("Đã áp dụng bộ tọa độ mẫu: ${preset.label}", LogLevel.INFO)
    }

    fun checkForUpdatesNow() {
        val upCfg = BotStateStore.config.value.update
        viewModelScope.launch {
            updater.checkForUpdates(
                rawSourceUrl = upCfg.updateSourceUrl,
                autoDownload = upCfg.autoDownloadWhenAvailable,
                silentIfUnconfigured = false,
            )
        }
    }

    fun downloadUpdate(release: UpdateReleaseInfo, onReadyToInstall: ((File) -> Unit)? = null) {
        viewModelScope.launch {
            updater.downloadUpdate(release)
            val current = updater.state.value
            if (current is UpdateUiState.ReadyToInstall) {
                onReadyToInstall?.invoke(current.apkFile)
            }
        }
    }

    fun canInstallPackages(): Boolean = updater.canRequestPackageInstalls()

    fun buildInstallPermissionIntent(): Intent = updater.buildUnknownSourcesSettingsIntent()

    fun installApk(activityContext: Context, apkFile: File): Boolean {
        return updater.launchApkInstaller(activityContext, apkFile)
    }

    fun exportJson(): String {
        return storage.exportBundleJson(BotStateStore.config.value, BotStateStore.calibration.value)
    }

    fun importJson(raw: String): Boolean {
        val result = storage.importBundleJson(raw)
        return result.fold(
            onSuccess = { bundle ->
                BotStateStore.updateConfig(bundle.config)
                BotStateStore.updateCalibration(bundle.calibration)
                storage.saveConfig(bundle.config)
                storage.saveCalibration(bundle.calibration)
                BotStateStore.log("Đã nhập cấu hình & tọa độ từ JSON thành công!", LogLevel.SUCCESS)
                true
            },
            onFailure = { err ->
                BotStateStore.log("Lỗi nhập JSON: ${err.message}", LogLevel.ERROR)
                false
            },
        )
    }

    fun clearLogs() {
        BotStateStore.clearLogs()
    }
}
