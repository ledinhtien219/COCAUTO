package com.cocauto.helper.data

import android.content.Context
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.CalibrationProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ExportBundle(
    val version: Int = 1,
    val config: BotConfig = BotConfig(),
    val calibration: CalibrationProfile = CalibrationProfile(),
)

/**
 * Persists BotConfig and CalibrationProfile locally as JSON and supports full export/import.
 */
class ConfigStorage(context: Context) {
    private val appContext = context.applicationContext
    private val configFile = File(appContext.filesDir, "coc_bot_config.json")
    private val calibrationFile = File(appContext.filesDir, "coc_calibration.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun loadConfig(): BotConfig {
        return runCatching {
            if (configFile.exists()) {
                val loaded = json.decodeFromString<BotConfig>(configFile.readText())
                if (loaded.update.updateSourceUrl.isBlank()) {
                    val migrated = loaded.copy(
                        update = loaded.update.copy(
                            updateSourceUrl = com.cocauto.helper.model.UpdateConfig.DEFAULT_UPDATE_SOURCE,
                        ),
                    )
                    saveConfig(migrated)
                    migrated
                } else {
                    loaded
                }
            } else {
                BotConfig()
            }
        }.getOrDefault(BotConfig())
    }

    fun saveConfig(config: BotConfig) {
        runCatching {
            configFile.writeText(json.encodeToString(config))
        }
    }

    fun loadCalibration(defaultWidth: Int = 2400, defaultHeight: Int = 1080): CalibrationProfile {
        return runCatching {
            if (calibrationFile.exists()) {
                val loaded = json.decodeFromString<CalibrationProfile>(calibrationFile.readText())
                if (loaded.schemaVersion < CalibrationProfile.CURRENT_SCHEMA_VERSION) {
                    val upgraded = CalibrationProfile.forScreenDimensions(defaultWidth, defaultHeight)
                    saveCalibration(upgraded)
                    upgraded
                } else {
                    loaded
                }
            } else {
                CalibrationProfile.forScreenDimensions(defaultWidth, defaultHeight)
            }
        }.getOrDefault(CalibrationProfile.forScreenDimensions(defaultWidth, defaultHeight))
    }

    fun saveCalibration(profile: CalibrationProfile) {
        runCatching {
            calibrationFile.writeText(json.encodeToString(profile))
        }
    }

    fun exportBundleJson(config: BotConfig, calibration: CalibrationProfile): String {
        return json.encodeToString(ExportBundle(config = config, calibration = calibration))
    }

    fun importBundleJson(rawJson: String): Result<ExportBundle> {
        return runCatching {
            json.decodeFromString<ExportBundle>(rawJson)
        }
    }
}
