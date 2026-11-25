package com.cocauto.helper.bridge

import android.content.Context
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.ControlBackendMode

/**
 * Coordinates the active ADB / Legacy control bridge based on user config.
 */
class DeviceBridgeManager(context: Context) {
    private val appContext = context.applicationContext

    val shizukuBridge = ShizukuAdbBridge(appContext)
    val adbTcpBridge = AdbTcpBridge(
        context = appContext,
        hostProvider = { BotStateStore.config.value.bridge.adbTcpHost },
        portProvider = { BotStateStore.config.value.bridge.adbTcpPort },
    )
    val pcHttpBridge = PcHttpAdbBridge(
        baseUrlProvider = { BotStateStore.config.value.bridge.pcBridgeUrl },
    )

    fun currentMode(): ControlBackendMode = BotStateStore.config.value.bridge.mode

    fun activeAdbBridge(): DeviceBridge? = when (currentMode()) {
        ControlBackendMode.SHIZUKU_ADB -> shizukuBridge
        ControlBackendMode.LOCAL_ADB_TCP -> adbTcpBridge
        ControlBackendMode.PC_HTTP_ADB -> pcHttpBridge
        ControlBackendMode.ACCESSIBILITY_LEGACY -> null
    }

    suspend fun testActiveBridge(): Result<String> {
        val bridge = activeAdbBridge()
        if (bridge == null) {
            val connected = BotStateStore.runtime.value.accessibilityConnected
            val msg = if (connected) "Chế độ Trợ năng đang bật." else "Chưa bật Dịch vụ Trợ năng."
            BotStateStore.updateRuntime { it.copy(bridgeConnected = connected, bridgeStatusText = msg) }
            return if (connected) Result.success(msg) else Result.failure(IllegalStateException(msg))
        }
        val res = bridge.testConnection()
        res.fold(
            onSuccess = { msg ->
                BotStateStore.updateRuntime { it.copy(bridgeConnected = true, bridgeStatusText = msg) }
            },
            onFailure = { err ->
                val msg = err.message ?: "Không kết nối được ADB cầu nối"
                BotStateStore.updateRuntime { it.copy(bridgeConnected = false, bridgeStatusText = msg) }
            },
        )
        return res
    }

    fun disconnectAll() {
        shizukuBridge.disconnect()
        adbTcpBridge.disconnect()
        pcHttpBridge.disconnect()
        BotStateStore.updateRuntime { it.copy(bridgeConnected = false) }
    }
}
