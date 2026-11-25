package com.cocauto.helper.model

import kotlinx.serialization.Serializable

@Serializable
enum class BotRunMode {
    HOME_ONLY,
    BUILDER_BASE_ONLY,
    ALTERNATE_BOTH,
}

@Serializable
enum class LootMatchMode {
    ALL_MATCH,
    ANY_MATCH,
    COMBINED_SUM,
}

@Serializable
enum class DeployStrategyType {
    FOUR_SIDES_SURROUND,
    FOCUSED_DIRECTION,
    PACED_WAVES,
}

@Serializable
enum class DeployEdge {
    NW,
    NE,
    SW,
    SE,
}

@Serializable
enum class WallCurrencyMode {
    GOLD_ONLY,
    ELIXIR_ONLY,
    BOTH,
}

@Serializable
enum class UpgradePriorityOrder {
    CHEAPEST_FIRST,
    MOST_EXPENSIVE_FIRST,
    TOP_OF_LIST,
}

@Serializable
enum class FullStorageCondition {
    GOLD_AND_ELIXIR,
    GOLD_OR_ELIXIR,
    ALL_INCLUDING_DE,
}

@Serializable
enum class FullStorageAction {
    PAUSE_WAIT_BUILDER,
    STOP_BOT,
}

@Serializable
data class HomeFarmConfig(
    val autoCollectResources: Boolean = true,
    val collectLootCart: Boolean = true,
    val autoAttack: Boolean = true,
    val minGold: Long = 100_000L,
    val minElixir: Long = 100_000L,
    val minDarkElixir: Long = 0L,
    val minCombinedGoldElixir: Long = 200_000L,
    val lootMatchMode: LootMatchMode = LootMatchMode.COMBINED_SUM,
    val maxNextCount: Int = 25,
    val attackWhenMaxNextReached: Boolean = false,
    val deployStrategy: DeployStrategyType = DeployStrategyType.FOUR_SIDES_SURROUND,
    val focusedEdges: Set<DeployEdge> = setOf(DeployEdge.NW, DeployEdge.NE),
    val troopSlotCount: Int = 4,
    val heroSlotIndices: Set<Int> = setOf(5, 6, 7, 8),
    val spellSlotIndices: Set<Int> = setOf(9, 10),
    val triggerHeroAbilityAfterSec: Int = 8,
    val skipEmptySlots: Boolean = true,
    val deployWaveCount: Int = 3,
    val waveDelayMs: Long = 2_200L,
    val tapsPerPoint: Int = 3,
    val maxBattleDurationSec: Int = 115,
    val earlySurrenderWhenTroopsEmpty: Boolean = false,
    val earlySurrenderWaitSec: Int = 25,
    val postBattleCooldownSec: Int = 3,
    val stopWhenStorageFull: Boolean = true,
    val fullStorageCondition: FullStorageCondition = FullStorageCondition.GOLD_AND_ELIXIR,
    val fullStorageAction: FullStorageAction = FullStorageAction.PAUSE_WAIT_BUILDER,
    val fullStorageFillPercent: Int = 95,
    val fullStorageGoldCap: Long = 0L,
    val fullStorageElixirCap: Long = 0L,
    val fullStorageDarkElixirCap: Long = 0L,
    val fullStorageRecheckMinutes: Int = 5,
)

@Serializable
data class BuilderBaseConfig(
    val enabled: Boolean = false,
    val collectResources: Boolean = true,
    val activateClockTower: Boolean = true,
    val attacksPerCycle: Int = 3,
    val fastSurrenderMode: Boolean = true,
    val fastSurrenderDelaySec: Int = 12,
    val heroAbilitySpamIntervalSec: Int = 4,
    val deployEdge: DeployEdge = DeployEdge.NW,
    val troopSlotCount: Int = 6,
    val maxBattleDurationSec: Int = 75,
)

@Serializable
data class AutoUpgradeConfig(
    val minKeepGold: Long = 250_000L,
    val minKeepElixir: Long = 250_000L,
    val minKeepDarkElixir: Long = 5_000L,
    val onlyActWhenBuilderFree: Boolean = true,
    val autoBuildNewBuildings: Boolean = true,
    val prioritizeNewBuildingsFirst: Boolean = true,
    val autoUpgradeBuildings: Boolean = true,
    val reserveBuildersForWalls: Int = 1,
    val upgradePriority: UpgradePriorityOrder = UpgradePriorityOrder.CHEAPEST_FIRST,
    val skipBuildingKeywords: List<String> = listOf(
        "Town Hall",
        "Nhà Chính",
        "Wall",
        "Tường",
    ),
    val autoUpgradeWalls: Boolean = true,
    val wallGoldThreshold: Long = 4_000_000L,
    val wallElixirThreshold: Long = 4_000_000L,
    val wallCurrencyMode: WallCurrencyMode = WallCurrencyMode.BOTH,
    val maxWallsPerCycle: Int = 5,
    val autoUpgradeLab: Boolean = true,
    val labCheckIntervalMinutes: Int = 20,
    val labPreferDarkElixir: Boolean = false,
    val skipLabKeywords: List<String> = emptyList(),
)

@Serializable
data class SessionSafetyConfig(
    val autoCalibrateScreen: Boolean = true,
    val autoRelaunchGame: Boolean = true,
    val autoReloadOnDisconnect: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val humanizeJitterPx: Int = 8,
    val actionDelayMinMs: Long = 160L,
    val actionDelayMaxMs: Long = 340L,
    val personalBreakPauseMinutes: Int = 15,
    val homeCyclesBeforeBuilderBase: Int = 2,
)

@Serializable
enum class ControlBackendMode(val label: String) {
    SHIZUKU_ADB("Shizuku (ADB trên máy)"),
    LOCAL_ADB_TCP("ADB TCP (127.0.0.1:5555)"),
    PC_HTTP_ADB("ADB Cầu nối PC"),
    ACCESSIBILITY_LEGACY("Trợ năng (Cũ)"),
}

@Serializable
data class BridgeConfig(
    val mode: ControlBackendMode = ControlBackendMode.SHIZUKU_ADB,
    val adbTcpHost: String = "127.0.0.1",
    val adbTcpPort: Int = 5555,
    val pcBridgeUrl: String = "http://127.0.0.1:8085",
)

@Serializable
data class UpdateConfig(
    val autoCheckOnStartup: Boolean = true,
    val autoDownloadWhenAvailable: Boolean = false,
    val updateSourceUrl: String = DEFAULT_UPDATE_SOURCE,
) {
    companion object {
        const val DEFAULT_UPDATE_SOURCE = "ledinhtien219/COCAUTO"
    }
}

@Serializable
data class BotConfig(
    val runMode: BotRunMode = BotRunMode.HOME_ONLY,
    val bridge: BridgeConfig = BridgeConfig(),
    val homeFarm: HomeFarmConfig = HomeFarmConfig(),
    val builderBase: BuilderBaseConfig = BuilderBaseConfig(),
    val upgrade: AutoUpgradeConfig = AutoUpgradeConfig(),
    val safety: SessionSafetyConfig = SessionSafetyConfig(),
    val update: UpdateConfig = UpdateConfig(),
)
