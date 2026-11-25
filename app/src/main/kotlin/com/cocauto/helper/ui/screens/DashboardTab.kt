package com.cocauto.helper.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.BotRunMode
import com.cocauto.helper.model.BotRunStatus
import com.cocauto.helper.model.BotRuntimeSnapshot
import com.cocauto.helper.model.ControlBackendMode
import com.cocauto.helper.ui.components.IntNumberField
import com.cocauto.helper.ui.components.SectionCard
import com.cocauto.helper.ui.components.SingleChoiceChipRow
import com.cocauto.helper.ui.components.StatTile
import com.cocauto.helper.ui.theme.ClashCrimson
import com.cocauto.helper.ui.theme.ClashElixir
import com.cocauto.helper.ui.theme.ClashEmerald
import com.cocauto.helper.ui.theme.ClashGold
import com.cocauto.helper.ui.theme.ClashSky
import com.cocauto.helper.ui.theme.TextSecondary
import com.cocauto.helper.updater.UpdateReleaseInfo
import com.cocauto.helper.updater.UpdateUiState
import java.io.File

data class PermissionSummary(
    val bridgeReady: Boolean,
    val overlayGranted: Boolean,
    val accessibilityEnabled: Boolean,
    val mediaProjectionReady: Boolean,
    val notificationsGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val shizukuRunning: Boolean,
    val shizukuGranted: Boolean,
)

@Composable
fun DashboardTab(
    runtime: BotRuntimeSnapshot,
    config: BotConfig,
    permissions: PermissionSummary,
    updateState: UpdateUiState,
    onUpdateConfig: ((BotConfig) -> BotConfig) -> Unit,
    onRunModeChange: (BotRunMode) -> Unit,
    onStartOrToggle: () -> Unit,
    onStop: () -> Unit,
    onToggleFloatingIcon: () -> Unit,
    onOpenCalibrationInGame: () -> Unit,
    onTestBridge: () -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onDownloadUpdate: (UpdateReleaseInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
) {
    val isRunning = runtime.status == BotRunStatus.RUNNING || runtime.status == BotRunStatus.WAITING_GAME
    val isPaused = runtime.status == BotRunStatus.PAUSED
    val isAdbMode = config.bridge.mode != ControlBackendMode.ACCESSIBILITY_LEGACY
    val canShowOverlay = permissions.overlayGranted || permissions.accessibilityEnabled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Automatic New Version Banner
        when (updateState) {
            is UpdateUiState.Available -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E293B)),
                    border = BorderStroke(1.dp, ClashSky),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = ClashSky)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Có bản cập nhật mới: v${updateState.release.versionName}",
                                fontWeight = FontWeight.Bold,
                                color = ClashSky,
                            )
                        }
                        if (updateState.release.changelog.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = updateState.release.changelog,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFDCEEFB),
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onDownloadUpdate(updateState.release) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ClashSky, contentColor = Color(0xFF031926)),
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tải & Cập nhật ngay", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            is UpdateUiState.Downloading -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E293B)),
                    border = BorderStroke(1.dp, ClashSky),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Đang tải bản v${updateState.release.versionName} (${updateState.progressPercent}%)...",
                            fontWeight = FontWeight.Bold,
                            color = ClashSky,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (updateState.progressPercent / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            is UpdateUiState.ReadyToInstall -> {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF102A18)),
                    border = BorderStroke(1.dp, ClashEmerald),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Đã tải xong v${updateState.release.versionName}!",
                            fontWeight = FontWeight.Bold,
                            color = ClashEmerald,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onInstallUpdate(updateState.apkFile) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ClashEmerald, contentColor = Color(0xFF071B0D)),
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cài đặt bản mới ngay", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            else -> Unit
        }

        // Responsible-use & Supercell ToS notice
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B210B)),
            border = BorderStroke(1.dp, ClashGold.copy(alpha = 0.45f)),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = ClashGold,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Lưu ý: Công cụ tự động vi phạm Điều khoản dịch vụ (ToS) của Supercell và có thể gây cảnh báo/khóa tài khoản. Không nên treo 24/24 liên tục.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFDE68A),
                )
            }
        }

        // 1. ADB Bridge Control Backend Selector (NO AccessibilityService needed!)
        SectionCard(
            title = "Cầu nối ADB (Không dùng Trợ năng)",
            subtitle = "Chụp màn hình & chạm trực tiếp bằng quyền ADB shell — mượt, không bị chặn Trợ năng, không hỏi quay màn hình",
        ) {
            SingleChoiceChipRow(
                options = ControlBackendMode.entries.map { it to it.label },
                selected = config.bridge.mode,
                onSelect = { m ->
                    onUpdateConfig { it.copy(bridge = it.bridge.copy(mode = m)) }
                },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (permissions.bridgeReady) "● ${runtime.bridgeStatusText.ifBlank { "ADB cầu nối sẵn sàng" }}" else "● ${runtime.bridgeStatusText}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (permissions.bridgeReady) ClashEmerald else ClashGold,
            )

            Spacer(modifier = Modifier.height(8.dp))
            when (config.bridge.mode) {
                ControlBackendMode.SHIZUKU_ADB -> {
                    Text(
                        text = "Chạy độc lập 100% trên điện thoại: Cài app Shizuku → bật Gỡ lỗi không dây trong Shizuku → bấm 'Cấp quyền Shizuku'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (!permissions.shizukuRunning) onOpenShizukuApp() else onRequestShizuku()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Cable, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (!permissions.shizukuRunning) "Mở Shizuku" else "Cấp quyền Shizuku")
                        }
                        OutlinedButton(
                            onClick = onTestBridge,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kiểm tra ADB")
                        }
                    }
                }

                ControlBackendMode.LOCAL_ADB_TCP -> {
                    Text(
                        text = "Cắm cáp vào PC 1 lần chạy 'adb tcpip 5555' rồi rút dây — app kết nối thẳng ADB 127.0.0.1:5555 không cần cài thêm Shizuku.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = config.bridge.adbTcpHost,
                            onValueChange = { h -> onUpdateConfig { it.copy(bridge = it.bridge.copy(adbTcpHost = h)) } },
                            label = { Text("IP ADB") },
                            singleLine = true,
                            modifier = Modifier.weight(1.4f),
                        )
                        IntNumberField(
                            label = "Cổng",
                            value = config.bridge.adbTcpPort,
                            min = 1,
                            max = 65535,
                            onValueChange = { p -> onUpdateConfig { it.copy(bridge = it.bridge.copy(adbTcpPort = p)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onTestBridge, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Cable, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Kết nối & Kiểm tra ADB TCP", fontWeight = FontWeight.Bold)
                    }
                }

                ControlBackendMode.PC_HTTP_ADB -> {
                    Text(
                        text = "Trên PC chạy 'node .\\scripts\\adb-bridge.mjs' (cắm USB dùng http://127.0.0.1:8085 hoặc nhập IP Wi-Fi PC).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = config.bridge.pcBridgeUrl,
                        onValueChange = { u -> onUpdateConfig { it.copy(bridge = it.bridge.copy(pcBridgeUrl = u)) } },
                        label = { Text("Địa chỉ PC ADB Bridge") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onTestBridge, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Cable, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Kiểm tra kết nối PC ADB Bridge", fontWeight = FontWeight.Bold)
                    }
                }

                ControlBackendMode.ACCESSIBILITY_LEGACY -> {
                    Text(
                        text = "Chế độ Trợ năng cũ (không khuyến khích nếu máy hay bị Android chặn Trợ năng).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }

        // 2. Main Control Card
        SectionCard(
            title = "Trung tâm điều khiển",
            subtitle = "Trạng thái: ${runtime.status.label} • Màn hình: ${runtime.screenState.label}",
        ) {
            Text(
                text = runtime.currentAction,
                style = MaterialTheme.typography.bodyMedium,
                color = ClashSky,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Chế độ farm:",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            SingleChoiceChipRow(
                options = listOf(
                    BotRunMode.HOME_ONLY to "Nhà Chính",
                    BotRunMode.BUILDER_BASE_ONLY to "Nhà Thợ Xây",
                    BotRunMode.ALTERNATE_BOTH to "Luân phiên cả 2",
                ),
                selected = config.runMode,
                onSelect = onRunModeChange,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStartOrToggle,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) ClashGold else ClashEmerald,
                        contentColor = Color(0xFF0A120A),
                    ),
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isRunning -> "Tạm dừng"
                            isPaused -> "Tiếp tục"
                            else -> "Bắt đầu Auto"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (isRunning || isPaused) {
                    Button(
                        onClick = onStop,
                        colors = ButtonDefaults.buttonColors(containerColor = ClashCrimson),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Dừng")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onToggleFloatingIcon,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.RadioButtonChecked, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (runtime.floatingHudVisible) {
                        "Ẩn nút icon nổi trên màn hình"
                    } else {
                        "Hiện nút icon nổi di chuyển được (không cần Trợ năng)"
                    },
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenCalibrationInGame,
                modifier = Modifier.fillMaxWidth(),
                enabled = canShowOverlay,
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Tự nhận diện màn hình trực tiếp trên game")
            }
        }

        // 3. Permissions Checklist (adapts automatically to ADB Bridge vs Legacy mode)
        SectionCard(
            title = if (isAdbMode) "Quyền cần thiết (Chế độ ADB — Không cần Trợ năng)" else "Quyền hệ thống (Chế độ Trợ năng cũ)",
            subtitle = if (isAdbMode) "Chỉ cần ADB cầu nối + Hiển thị trên ứng dụng khác để hiện icon nổi" else "Cấp đủ quyền Trợ năng & Chụp màn hình",
        ) {
            if (isAdbMode) {
                PermissionRow(
                    title = "1. Kết nối ${config.bridge.mode.label}",
                    subtitle = "Chụp màn hình & chạm/vuốt trực tiếp bằng quyền ADB shell",
                    granted = permissions.bridgeReady,
                    actionLabel = "Kiểm tra",
                    onAction = onTestBridge,
                )
                PermissionRow(
                    title = "2. Hiển thị trên ứng dụng khác (Icon nổi)",
                    subtitle = "Hiện icon tròn di chuyển được & khung nhận diện mà không dùng Trợ năng",
                    granted = permissions.overlayGranted,
                    actionLabel = "Cấp quyền",
                    onAction = onRequestOverlayPermission,
                )
            } else {
                PermissionRow(
                    title = "1. Dịch vụ Trợ năng (AccessibilityService)",
                    subtitle = "Chạm/vuốt tự động & chụp màn hình Android 11+",
                    granted = permissions.accessibilityEnabled,
                    actionLabel = "Mở Cài đặt",
                    onAction = onRequestAccessibility,
                )
                PermissionRow(
                    title = "2. Chụp màn hình (Trợ năng 11+ / MediaProjection)",
                    subtitle = "Đọc Vàng/Dầu/DE, Thợ xây và danh sách nâng cấp",
                    granted = permissions.mediaProjectionReady,
                    actionLabel = "Cấp quyền",
                    onAction = onRequestMediaProjection,
                )
            }

            PermissionRow(
                title = "3. Thông báo dịch vụ nền",
                subtitle = "Giữ dịch vụ chạy ổn định & hiện nút dừng nhanh",
                granted = permissions.notificationsGranted,
                actionLabel = "Cấp quyền",
                onAction = onRequestNotifications,
            )
            PermissionRow(
                title = "4. Không hạn chế pin (Chạy nền)",
                subtitle = "Tránh Android tự tắt app khi treo máy lâu",
                granted = permissions.batteryUnrestricted,
                actionLabel = "Tắt giới hạn",
                onAction = onRequestBatteryExemption,
            )
        }

        // 4. Live Session Stats
        val s = runtime.stats
        SectionCard(
            title = "Thống kê phiên hiện tại",
            subtitle = "Thợ xây: ${s.lastBuilderStatus.freeBuilders}/${s.lastBuilderStatus.totalBuilders} • Kho: V=${formatShort(s.lastHomeResources.gold)} (${s.lastStorageFill.goldPercent}%) D=${formatShort(s.lastHomeResources.elixir)} (${s.lastStorageFill.elixirPercent}%) DE=${s.lastHomeResources.darkElixir}",
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Trận Nhà Chính", "${s.attacksCompleted}", ClashEmerald, Modifier.weight(1f))
                StatTile("Trận Nhà Thợ Xây", "${s.builderBaseAttacks}", ClashSky, Modifier.weight(1f))
                StatTile("Lượt Next", "${s.basesSkipped}", ClashGold, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Xây mới / Nâng", "${s.buildingsBuilt}/${s.buildingsUpgraded}", ClashGold, Modifier.weight(1f))
                StatTile("Tường nâng", "${s.wallsUpgraded}", ClashEmerald, Modifier.weight(1f))
                StatTile("Lab nâng", "${s.labUpgradesStarted}", ClashElixir, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Vàng ước tính", formatShort(s.estimatedGoldLooted), ClashGold, Modifier.weight(1f))
                StatTile("Dầu ước tính", formatShort(s.estimatedElixirLooted), ClashElixir, Modifier.weight(1f))
                StatTile("Khôi phục lỗi", "${s.reconnectRecoveries}", ClashSky, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (granted) ClashEmerald else ClashGold,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (!granted) {
            OutlinedButton(onClick = onAction) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Text("Đã bật", color = ClashEmerald, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatShort(amount: Long): String = when {
    amount >= 1_000_000L -> String.format("%.2fM", amount / 1_000_000.0)
    amount >= 1_000L -> "${amount / 1_000L}K"
    else -> amount.toString()
}
