package com.cocauto.helper.bridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.ControlBackendMode
import com.cocauto.helper.model.LogLevel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pure Kotlin planner deciding which ADB backends to try in order.
 * Both LOCAL_ADB_TCP (`127.0.0.1:5555` kernel loopback) and SHIZUKU_ADB (local Binder IPC)
 * work identically on 4G/5G without Wi-Fi, on Wi-Fi, and even offline.
 */
object BridgeAutoConnectPlanner {
    fun planCandidates(
        mode: ControlBackendMode,
        lastWorkingMode: ControlBackendMode? = null,
        shizukuRunningAndGranted: Boolean = false,
    ): List<ControlBackendMode> {
        return when (mode) {
            ControlBackendMode.ACCESSIBILITY_LEGACY -> emptyList()
            ControlBackendMode.LOCAL_ADB_TCP -> listOf(ControlBackendMode.LOCAL_ADB_TCP)
            ControlBackendMode.SHIZUKU_ADB -> listOf(ControlBackendMode.SHIZUKU_ADB)
            ControlBackendMode.PC_HTTP_ADB -> listOf(ControlBackendMode.PC_HTTP_ADB)
            ControlBackendMode.AUTO_ADB -> {
                val base = if (shizukuRunningAndGranted && lastWorkingMode == ControlBackendMode.SHIZUKU_ADB) {
                    listOf(
                        ControlBackendMode.SHIZUKU_ADB,
                        ControlBackendMode.LOCAL_ADB_TCP,
                        ControlBackendMode.PC_HTTP_ADB,
                    )
                } else {
                    listOf(
                        ControlBackendMode.LOCAL_ADB_TCP,
                        ControlBackendMode.SHIZUKU_ADB,
                        ControlBackendMode.PC_HTTP_ADB,
                    )
                }
                if (lastWorkingMode != null && lastWorkingMode in base) {
                    listOf(lastWorkingMode) + base.filter { it != lastWorkingMode }
                } else {
                    base
                }
            }
        }
    }
}

/**
 * Process-wide coordinator for the active ADB / Legacy control bridge.
 * Shared across MainActivity, MainViewModel, BotForegroundService, OCR tests, and Auto-Calibration
 * so opening the app connects once (over Wi-Fi OR 4G-only) and stays connected across screens.
 */
class DeviceBridgeManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()

    val shizukuBridge = ShizukuAdbBridge(appContext)
    val adbTcpBridge = AdbTcpBridge(
        context = appContext,
        hostProvider = { BotStateStore.config.value.bridge.adbTcpHost },
        portProvider = { BotStateStore.config.value.bridge.adbTcpPort },
    )
    val pcHttpBridge = PcHttpAdbBridge(
        baseUrlProvider = { BotStateStore.config.value.bridge.pcBridgeUrl },
    )

    @Volatile
    private var resolvedAutoMode: ControlBackendMode? = null

    @Volatile
    private var lastSuccessLogKey: String = ""

    @Volatile
    private var lastConnectedAtMs: Long = 0L

    fun currentMode(): ControlBackendMode = BotStateStore.config.value.bridge.mode

    fun resolvedMode(): ControlBackendMode {
        val cfgMode = currentMode()
        return if (cfgMode == ControlBackendMode.AUTO_ADB) {
            resolvedAutoMode ?: ControlBackendMode.LOCAL_ADB_TCP
        } else {
            cfgMode
        }
    }

    fun bridgeFor(mode: ControlBackendMode): DeviceBridge? = when (mode) {
        ControlBackendMode.AUTO_ADB -> resolvedAutoMode?.let { bridgeFor(it) } ?: adbTcpBridge
        ControlBackendMode.SHIZUKU_ADB -> shizukuBridge
        ControlBackendMode.LOCAL_ADB_TCP -> adbTcpBridge
        ControlBackendMode.PC_HTTP_ADB -> pcHttpBridge
        ControlBackendMode.ACCESSIBILITY_LEGACY -> null
    }

    fun activeAdbBridge(): DeviceBridge? = when (currentMode()) {
        ControlBackendMode.AUTO_ADB -> resolvedAutoMode?.let { bridgeFor(it) }
        ControlBackendMode.SHIZUKU_ADB -> shizukuBridge
        ControlBackendMode.LOCAL_ADB_TCP -> adbTcpBridge
        ControlBackendMode.PC_HTTP_ADB -> pcHttpBridge
        ControlBackendMode.ACCESSIBILITY_LEGACY -> null
    }

    fun detectNetworkLabel(): String {
        return runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Không rõ mạng"
            val active = cm.activeNetwork ?: return "Không mạng (Offline)"
            val caps = cm.getNetworkCapabilities(active) ?: return "Không mạng (Offline)"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "4G/5G"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Mạng cục bộ"
            }
        }.getOrDefault("Mạng cục bộ")
    }

    /**
     * Automatically connects to the best available ADB bridge on app startup, resume,
     * or Wi-Fi <-> 4G network switch. Works seamlessly without Wi-Fi (4G/5G & Offline).
     */
    suspend fun autoConnect(
        reason: String = "",
        forceReconnect: Boolean = false,
        logOnSuccess: Boolean = true,
    ): Result<String> = connectMutex.withLock {
        val cfgMode = currentMode()
        val netLabel = detectNetworkLabel()

        if (cfgMode == ControlBackendMode.ACCESSIBILITY_LEGACY) {
            val connected = BotStateStore.runtime.value.accessibilityConnected
            val msg = if (connected) "Chế độ Trợ năng đang bật [$netLabel]" else "Chưa bật Dịch vụ Trợ năng [$netLabel]"
            BotStateStore.updateRuntime {
                it.copy(
                    bridgeConnected = connected,
                    bridgeStatusText = msg,
                    activeBridgeLabel = ControlBackendMode.ACCESSIBILITY_LEGACY.label,
                    networkTypeLabel = netLabel,
                )
            }
            return@withLock if (connected) Result.success(msg) else Result.failure(IllegalStateException(msg))
        }

        // Fast-path: reuse already-healthy active bridge if not forced and verified very recently
        val now = System.currentTimeMillis()
        if (!forceReconnect && (now - lastConnectedAtMs) < 4_000L) {
            val existing = activeAdbBridge()
            if (existing != null && existing.isConnected()) {
                val cur = BotStateStore.runtime.value
                if (cur.bridgeConnected && cur.networkTypeLabel == netLabel) {
                    return@withLock Result.success(cur.bridgeStatusText)
                }
            }
        }

        val candidates = BridgeAutoConnectPlanner.planCandidates(
            mode = cfgMode,
            lastWorkingMode = resolvedAutoMode,
            shizukuRunningAndGranted = shizukuBridge.hasShizukuPermission(),
        )

        var shizukuNeedsPermission = false
        var lastErrorMsg = ""

        for (candidate in candidates) {
            val bridge = bridgeFor(candidate) ?: continue

            // In AUTO_ADB mode, skip Shizuku immediately (<1ms) if its binder isn't running or permission not granted yet
            if (cfgMode == ControlBackendMode.AUTO_ADB && candidate == ControlBackendMode.SHIZUKU_ADB) {
                if (!shizukuBridge.isShizukuBinderAlive()) {
                    lastErrorMsg = "Shizuku chưa chạy"
                    continue
                }
                if (!shizukuBridge.hasShizukuPermission()) {
                    shizukuNeedsPermission = true
                    lastErrorMsg = "Shizuku chưa cấp quyền"
                    continue
                }
            }

            val res = bridge.testConnection()
            if (res.isSuccess) {
                val detail = res.getOrThrow()
                if (cfgMode == ControlBackendMode.AUTO_ADB) {
                    resolvedAutoMode = candidate
                }
                lastConnectedAtMs = System.currentTimeMillis()
                val fullMsg = "Đã kết nối $detail [$netLabel]"
                BotStateStore.updateRuntime {
                    it.copy(
                        bridgeConnected = true,
                        bridgeStatusText = fullMsg,
                        activeBridgeLabel = candidate.label,
                        networkTypeLabel = netLabel,
                    )
                }
                val logKey = "${candidate.name}|$netLabel"
                if (logOnSuccess && (logKey != lastSuccessLogKey || forceReconnect)) {
                    lastSuccessLogKey = logKey
                    val prefix = if (reason.isNotBlank()) "Tự động ADB ($reason): " else ""
                    BotStateStore.log("$prefix$fullMsg", LogLevel.SUCCESS)
                }
                return@withLock Result.success(fullMsg)
            } else {
                lastErrorMsg = res.exceptionOrNull()?.message ?: "Lỗi kết nối ${candidate.label}"
            }
        }

        if (cfgMode == ControlBackendMode.AUTO_ADB) {
            resolvedAutoMode = null
        }
        val failureMsg = when {
            cfgMode == ControlBackendMode.AUTO_ADB && shizukuNeedsPermission -> {
                "Shizuku đang chạy — bấm 'Cấp quyền Shizuku' (hoặc chạy 1 lần 'adb tcpip 5555' để dùng cả 4G) [$netLabel]"
            }

            cfgMode == ControlBackendMode.AUTO_ADB -> {
                "Chưa thấy ADB 127.0.0.1:5555 hay Shizuku [$netLabel]. Bật 'adb tcpip 5555' 1 lần để tự nối cả Wi-Fi & 4G, hoặc mở Shizuku."
            }

            else -> {
                "$lastErrorMsg [$netLabel]"
            }
        }

        BotStateStore.updateRuntime {
            it.copy(
                bridgeConnected = false,
                bridgeStatusText = failureMsg,
                activeBridgeLabel = "",
                networkTypeLabel = netLabel,
            )
        }
        Result.failure(IllegalStateException(failureMsg))
    }

    suspend fun testActiveBridge(): Result<String> = autoConnect(
        reason = "kiểm tra thủ công",
        forceReconnect = true,
        logOnSuccess = true,
    )

    /**
     * Ensures an active ADB bridge is connected before capturing frames or dispatching touches.
     * Transparently fails over (e.g. Shizuku -> 127.0.0.1:5555) if Wi-Fi drops to 4G mid-farm!
     */
    suspend fun ensureConnected(): DeviceBridge? {
        if (currentMode() == ControlBackendMode.ACCESSIBILITY_LEGACY) return null
        activeAdbBridge()?.let { bridge ->
            if (bridge.isConnected()) return bridge
        }
        autoConnect(reason = "tự nối lại", forceReconnect = true, logOnSuccess = true)
        return activeAdbBridge()
    }

    fun disconnectAll() {
        resolvedAutoMode = null
        lastConnectedAtMs = 0L
        lastSuccessLogKey = ""
        shizukuBridge.disconnect()
        adbTcpBridge.disconnect()
        pcHttpBridge.disconnect()
        BotStateStore.updateRuntime {
            it.copy(bridgeConnected = false, activeBridgeLabel = "")
        }
    }

    companion object {
        @Volatile
        private var singleton: DeviceBridgeManager? = null

        fun getInstance(context: Context): DeviceBridgeManager {
            return singleton ?: synchronized(this) {
                singleton ?: DeviceBridgeManager(context.applicationContext).also { singleton = it }
            }
        }
    }
}
