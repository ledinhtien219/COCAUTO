package com.cocauto.helper.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BotRunStatus(val label: String) {
    STOPPED("Đã dừng"),
    RUNNING("Đang chạy"),
    PAUSED("Tạm dừng"),
    WAITING_GAME("Đang chờ mở CoC"),
    CALIBRATING("Đang hiệu chỉnh"),
}

enum class GameScreenState(val label: String) {
    NOT_IN_GAME("Ngoài game"),
    LOADING("Đang tải"),
    DISCONNECTED_DIALOG("Mất kết nối / Tải lại"),
    PERSONAL_BREAK("Giới hạn chơi / Nghỉ"),
    HOME_VILLAGE("Nhà Chính"),
    BUILDER_BASE("Nhà Thợ Xây"),
    ATTACK_MENU("Menu Đánh"),
    SEARCHING_CLOUDS("Đang tìm nhà (Mây)"),
    BATTLE_SCOUT("Đang soi nhà địch"),
    BATTLE_ACTIVE("Đang trong trận"),
    BATTLE_SUMMARY("Tổng kết trận"),
    BUILDER_SUGGESTIONS_OPEN("Gợi ý Thợ xây"),
    LAB_MENU_OPEN("Phòng thí nghiệm"),
    POPUP_DIALOG("Hộp thoại thông báo"),
    UNKNOWN("Chưa xác định"),
}

enum class CurrencyType {
    GOLD,
    ELIXIR,
    DARK_ELIXIR,
    UNKNOWN,
}

enum class LogLevel {
    INFO,
    SUCCESS,
    WARN,
    ERROR,
}

data class LootReading(
    val gold: Long = 0L,
    val elixir: Long = 0L,
    val darkElixir: Long = 0L,
    val rawText: String = "",
) {
    val combinedGoldElixir: Long get() = gold + elixir
    val isValid: Boolean get() = gold > 0L || elixir > 0L || darkElixir > 0L
}

data class HomeResources(
    val gold: Long = 0L,
    val elixir: Long = 0L,
    val darkElixir: Long = 0L,
)

data class StorageFillLevels(
    val goldPercent: Int = 0,
    val elixirPercent: Int = 0,
    val darkElixirPercent: Int = 0,
)

data class BuilderStatus(
    val freeBuilders: Int = 0,
    val totalBuilders: Int = 0,
) {
    val isValid: Boolean get() = totalBuilders in 1..7 && freeBuilders in 0..totalBuilders
}

data class UpgradeCandidate(
    val name: String,
    val cost: Long,
    val currency: CurrencyType,
    val tapPoint: NormPoint,
    val isWall: Boolean = false,
    val isNewBuilding: Boolean = false,
)

data class BotLogEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val level: LogLevel = LogLevel.INFO,
    val message: String,
) {
    fun formattedTime(): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return formatter.format(Date(timestampMs))
    }
}

data class SessionStats(
    val startedAtMs: Long = 0L,
    val attacksCompleted: Int = 0,
    val builderBaseAttacks: Int = 0,
    val basesSkipped: Int = 0,
    val estimatedGoldLooted: Long = 0L,
    val estimatedElixirLooted: Long = 0L,
    val estimatedDarkElixirLooted: Long = 0L,
    val buildingsBuilt: Int = 0,
    val buildingsUpgraded: Int = 0,
    val wallsUpgraded: Int = 0,
    val labUpgradesStarted: Int = 0,
    val reconnectRecoveries: Int = 0,
    val lastHomeResources: HomeResources = HomeResources(),
    val lastStorageFill: StorageFillLevels = StorageFillLevels(),
    val lastBuilderStatus: BuilderStatus = BuilderStatus(),
    val lastScannedLoot: LootReading = LootReading(),
)

data class BotRuntimeSnapshot(
    val status: BotRunStatus = BotRunStatus.STOPPED,
    val screenState: GameScreenState = GameScreenState.UNKNOWN,
    val currentAction: String = "Sẵn sàng",
    val foregroundPackage: String = "",
    val bridgeConnected: Boolean = false,
    val bridgeStatusText: String = "Đang chờ tự động kết nối ADB...",
    val activeBridgeLabel: String = "",
    val networkTypeLabel: String = "",
    val accessibilityConnected: Boolean = false,
    val mediaProjectionReady: Boolean = false,
    val calibrationOverlayVisible: Boolean = false,
    val floatingHudVisible: Boolean = false,
    val stats: SessionStats = SessionStats(),
    val lastOcrTestSummary: String = "",
)

/**
 * Process-wide reactive state store coordinating UI, Foreground Service, Accessibility Service,
 * Floating HUD, and Bot Engines without fragile Intent broadcast polling.
 */
object BotStateStore {
    private const val MAX_LOGS = 250

    private val _runtime = MutableStateFlow(BotRuntimeSnapshot())
    val runtime: StateFlow<BotRuntimeSnapshot> = _runtime.asStateFlow()

    private val _config = MutableStateFlow(BotConfig())
    val config: StateFlow<BotConfig> = _config.asStateFlow()

    private val _calibration = MutableStateFlow(CalibrationProfile())
    val calibration: StateFlow<CalibrationProfile> = _calibration.asStateFlow()

    private val _logs = MutableStateFlow<List<BotLogEntry>>(emptyList())
    val logs: StateFlow<List<BotLogEntry>> = _logs.asStateFlow()

    fun updateConfig(newConfig: BotConfig) {
        _config.value = newConfig
    }

    fun updateCalibration(newCalibration: CalibrationProfile) {
        _calibration.value = newCalibration
    }

    fun updateRuntime(transform: (BotRuntimeSnapshot) -> BotRuntimeSnapshot) {
        _runtime.update(transform)
    }

    fun updateStats(transform: (SessionStats) -> SessionStats) {
        _runtime.update { it.copy(stats = transform(it.stats)) }
    }

    fun resetStats() {
        _runtime.update {
            it.copy(stats = SessionStats(startedAtMs = System.currentTimeMillis()))
        }
    }

    fun setAction(action: String, screenState: GameScreenState? = null) {
        _runtime.update { current ->
            current.copy(
                currentAction = action,
                screenState = screenState ?: current.screenState,
            )
        }
    }

    fun log(message: String, level: LogLevel = LogLevel.INFO) {
        val entry = BotLogEntry(
            timestampMs = System.currentTimeMillis(),
            level = level,
            message = message,
        )
        _logs.update { current ->
            (listOf(entry) + current).take(MAX_LOGS)
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
