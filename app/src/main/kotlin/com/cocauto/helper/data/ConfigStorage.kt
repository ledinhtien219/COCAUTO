package com.cocauto.helper.data

import android.content.Context
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.SessionStats
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
    private val statsFile = File(appContext.filesDir, "coc_session_stats.json")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun loadConfig(): BotConfig {
        return runCatching {
            if (configFile.exists()) {
                val raw = configFile.readText()
                var loaded = json.decodeFromString<BotConfig>(raw)
                var changed = false
                if (loaded.update.updateSourceUrl.isBlank()) {
                    loaded = loaded.copy(
                        update = loaded.update.copy(
                            updateSourceUrl = com.cocauto.helper.model.UpdateConfig.DEFAULT_UPDATE_SOURCE,
                        ),
                    )
                    changed = true
                }
                // One-time migration from v2.0.x -> v2.1.0: switch old default to AUTO_ADB (Wi-Fi & 4G)
                if (!raw.contains("\"autoConnectOnLaunch\"") &&
                    loaded.bridge.mode != com.cocauto.helper.model.ControlBackendMode.ACCESSIBILITY_LEGACY
                ) {
                    loaded = loaded.copy(
                        bridge = loaded.bridge.copy(
                            mode = com.cocauto.helper.model.ControlBackendMode.AUTO_ADB,
                            autoConnectOnLaunch = true,
                            autoReconnectOnNetworkChange = true,
                        ),
                    )
                    changed = true
                }
                // One-time migration from v2.2.x -> v2.3.0:
                // 1) Farm: attack immediately when army is ready (bypass strict loot/storage gates)
                // 2) Upgrade: shortest-time buildings first -> remaining buildings -> walls last fitting Gold/Elixir
                if (!raw.contains("\"attackWhenArmyReady\"")) {
                    val mergedSkipKeywords = (loaded.upgrade.skipBuildingKeywords + listOf("Lều Thợ xây", "Builder's Hut"))
                        .distinct()
                    loaded = loaded.copy(
                        homeFarm = loaded.homeFarm.copy(
                            attackWhenArmyReady = true,
                            stopWhenStorageFull = false,
                            postBattleCooldownSec = 0,
                        ),
                        upgrade = loaded.upgrade.copy(
                            upgradePriority = com.cocauto.helper.model.UpgradePriorityOrder.SHORTEST_TIME_FIRST,
                            prioritizeNewBuildingsFirst = false,
                            reserveBuildersForWalls = 0,
                            minKeepGold = minOf(loaded.upgrade.minKeepGold, 10_000L),
                            minKeepElixir = minOf(loaded.upgrade.minKeepElixir, 10_000L),
                            wallGoldThreshold = minOf(loaded.upgrade.wallGoldThreshold, 50_000L),
                            wallElixirThreshold = minOf(loaded.upgrade.wallElixirThreshold, 50_000L),
                            skipBuildingKeywords = mergedSkipKeywords,
                        ),
                    )
                    changed = true
                }
                if (changed) saveConfig(loaded)
                loaded
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

    fun loadStats(): SessionStats {
        return runCatching {
            if (statsFile.exists()) {
                json.decodeFromString<SessionStats>(statsFile.readText())
            } else {
                SessionStats()
            }
        }.getOrDefault(SessionStats())
    }

    fun saveStats(stats: SessionStats) {
        runCatching {
            statsFile.writeText(json.encodeToString(stats))
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
