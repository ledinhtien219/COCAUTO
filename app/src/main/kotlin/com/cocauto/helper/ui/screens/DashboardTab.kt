package com.cocauto.helper.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsEsports
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
import com.cocauto.helper.ui.components.ExpandableSectionCard
import com.cocauto.helper.ui.components.IntNumberField
import com.cocauto.helper.ui.components.SectionCard
import com.cocauto.helper.ui.components.SettingDivider
import com.cocauto.helper.ui.components.SettingGroupLabel
import com.cocauto.helper.ui.components.SettingSwitchRow
import com.cocauto.helper.ui.components.SingleChoiceChipRow
import com.cocauto.helper.ui.components.StatTile
import com.cocauto.helper.ui.components.StatusPill
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

@OptIn(ExperimentalLayoutApi::class)
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
    onBridgeModeChanged: (ControlBackendMode) -> Unit,
    onRequestShizuku: () -> Unit,
    onOpenShizukuApp: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onDownloadUpdate: (UpdateReleaseInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
    onResetStats: () -> Unit,
) {
    val isRunning = runtime.status == BotRunStatus.RUNNING || runtime.status == BotRunStatus.WAITING_GAME
    val isPaused = runtime.status == BotRunStatus.PAUSED
    val isAdbMode = config.bridge.mode != ControlBackendMode.ACCESSIBILITY_LEGACY
    val canShowOverlay = permissions.overlayGranted || permissions.accessibilityEnabled
    val allCoreReady = permissions.bridgeReady && canShowOverlay

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 0. Automatic New Version Banner (only shown when update exists)
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

        // 1. HERO CONTROL CENTER FIRST — instant 1-tap access without scrolling!
        val statusColor = when {
            isRunning -> ClashEmerald
            isPaused -> ClashGold
            else -> ClashSky
        }
        SectionCard(
            title = "Điều khiển nhanh",
            subtitle = runtime.currentAction,
            icon = Icons.Default.SportsEsports,
            accentColor = ClashGold,
            badgeText = runtime.status.label,
            badgeColor = statusColor,
        ) {
            // Compact live status strip (wraps neatly without truncating on any font scale!)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val netLabel = runtime.networkTypeLabel.ifBlank { "ADB" }
                StatusPill(
                    text = if (permissions.bridgeReady) "ADB OK • $netLabel" else "Chưa nối ADB",
                    color = if (permissions.bridgeReady) ClashEmerald else ClashCrimson,
                    showDot = true,
                )
                StatusPill(
                    text = if (config.homeFarm.attackWhenArmyReady) "Đủ quân là chiến" else "Có lọc nhà",
                    color = if (config.homeFarm.attackWhenArmyReady) ClashSky else ClashGold,
                )
                StatusPill(
                    text = "Ngắn → Tường",
                    color = ClashGold,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            SettingGroupLabel("Chế độ chạy:")
            SingleChoiceChipRow(
                options = listOf(
                    BotRunMode.HOME_ONLY to "Nhà Chính",
                    BotRunMode.BUILDER_BASE_ONLY to "Nhà Thợ Xây",
                    BotRunMode.ALTERNATE_BOTH to "Cả 2 làng",
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
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) ClashGold else ClashEmerald,
                        contentColor = Color(0xFF071A0A),
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
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClashCrimson),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Dừng", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleFloatingIcon,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.RadioButtonChecked, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (runtime.floatingHudVisible) "Ẩn nút nổi" else "Nút nổi",
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = onOpenCalibrationInGame,
                    modifier = Modifier.weight(1f),
                    enabled = canShowOverlay,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Nhận diện", maxLines = 1)
                }
            }
        }

        // 2. LIVE SESSION STATS (Full 3x3 real-time persisted stats + Reset button!)
        val s = runtime.stats
        val bldText = if (s.lastBuilderStatus.isValid) {
            "Thợ ${s.lastBuilderStatus.freeBuilders}/${s.lastBuilderStatus.totalBuilders}"
        } else {
            "Thợ 0/0"
        }
        SectionCard(
            title = "Thống kê phiên hiện tại",
            subtitle = "Kho: V=${formatShort(s.lastHomeResources.gold)} (${s.lastStorageFill.goldPercent}%) • D=${formatShort(s.lastHomeResources.elixir)} (${s.lastStorageFill.elixirPercent}%) • DE=${s.lastHomeResources.darkElixir}",
            icon = Icons.Default.BarChart,
            accentColor = ClashSky,
            badgeText = bldText,
            badgeColor = if (s.lastBuilderStatus.freeBuilders > 0) ClashEmerald else ClashGold,
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Trận Chính", "${s.attacksCompleted}", ClashEmerald, Modifier.weight(1f))
                StatTile("Trận Thợ Xây", "${s.builderBaseAttacks}", ClashSky, Modifier.weight(1f))
                StatTile("Lượt Next", "${s.basesSkipped}", ClashGold, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Xây / Nâng", "${s.buildingsBuilt}/${s.buildingsUpgraded}", ClashGold, Modifier.weight(1f))
                StatTile("Tường nâng", "${s.wallsUpgraded}", ClashEmerald, Modifier.weight(1f))
                StatTile("Lab nâng", "${s.labUpgradesStarted}", ClashElixir, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Vàng cướp", formatShort(s.estimatedGoldLooted), ClashGold, Modifier.weight(1f))
                StatTile("Dầu cướp", formatShort(s.estimatedElixirLooted), ClashElixir, Modifier.weight(1f))
                StatTile("Khôi phục lỗi", "${s.reconnectRecoveries}", ClashSky, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = onResetStats,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Đặt lại thống kê", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // 3. COLLAPSIBLE ADB BRIDGE CONNECTION (Auto-collapsed when already connected so dashboard stays spotless!)
        val netSuffix = if (runtime.networkTypeLabel.isNotBlank() && !runtime.bridgeStatusText.contains(runtime.networkTypeLabel)) {
            " [${runtime.networkTypeLabel}]"
        } else {
            ""
        }
        ExpandableSectionCard(
            title = "Cầu nối ADB (Wi-Fi & 4G)",
            subtitle = "${runtime.bridgeStatusText.ifBlank { "Đang chờ kết nối ADB" }}$netSuffix",
            icon = Icons.Default.Cable,
            accentColor = if (permissions.bridgeReady) ClashEmerald else ClashGold,
            badgeText = if (permissions.bridgeReady) "Đã nối" else "Cần nối",
            badgeColor = if (permissions.bridgeReady) ClashEmerald else ClashCrimson,
            initiallyExpanded = !permissions.bridgeReady,
        ) {
            SingleChoiceChipRow(
                options = ControlBackendMode.entries.map { it to it.label },
                selected = config.bridge.mode,
                onSelect = onBridgeModeChanged,
            )

            if (isAdbMode) {
                Spacer(modifier = Modifier.height(6.dp))
                SettingDivider()
                SettingSwitchRow(
                    title = "Tự kết nối ADB khi mở app",
                    subtitle = "Chạy mượt trên cả Wi-Fi lẫn chỉ dùng 4G/5G",
                    checked = config.bridge.autoConnectOnLaunch,
                    onCheckedChange = { v ->
                        onUpdateConfig { it.copy(bridge = it.bridge.copy(autoConnectOnLaunch = v)) }
                    },
                )
                SettingSwitchRow(
                    title = "Tự nối lại khi đổi mạng Wi-Fi ↔ 4G",
                    subtitle = "Tự dự phòng qua 127.0.0.1:5555 khi mất Wi-Fi",
                    checked = config.bridge.autoReconnectOnNetworkChange,
                    onCheckedChange = { v ->
                        onUpdateConfig { it.copy(bridge = it.bridge.copy(autoReconnectOnNetworkChange = v)) }
                    },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            when (config.bridge.mode) {
                ControlBackendMode.AUTO_ADB -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onTestBridge,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text("Kết nối ngay", fontWeight = FontWeight.Bold)
                        }
                        if (permissions.shizukuRunning && !permissions.shizukuGranted) {
                            OutlinedButton(
                                onClick = onRequestShizuku,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Cấp Shizuku")
                            }
                        } else if (!permissions.bridgeReady) {
                            OutlinedButton(
                                onClick = onOpenShizukuApp,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text("Mở Shizuku")
                            }
                        }
                    }
                }

                ControlBackendMode.SHIZUKU_ADB -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (!permissions.shizukuRunning) onOpenShizukuApp() else onRequestShizuku() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(if (!permissions.shizukuRunning) "Mở Shizuku" else "Cấp quyền Shizuku")
                        }
                        OutlinedButton(
                            onClick = onTestBridge,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Kiểm tra ADB")
                        }
                    }
                }

                ControlBackendMode.LOCAL_ADB_TCP -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = config.bridge.adbTcpHost,
                            onValueChange = { h -> onUpdateConfig { it.copy(bridge = it.bridge.copy(adbTcpHost = h)) } },
                            label = { Text("IP ADB") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
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
                    Button(
                        onClick = onTestBridge,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Kết nối ADB TCP", fontWeight = FontWeight.Bold)
                    }
                }

                ControlBackendMode.PC_HTTP_ADB -> {
                    OutlinedTextField(
                        value = config.bridge.pcBridgeUrl,
                        onValueChange = { u -> onUpdateConfig { it.copy(bridge = it.bridge.copy(pcBridgeUrl = u)) } },
                        label = { Text("URL PC ADB Bridge") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onTestBridge,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Kiểm tra PC Bridge", fontWeight = FontWeight.Bold)
                    }
                }

                ControlBackendMode.ACCESSIBILITY_LEGACY -> {
                    Text(
                        text = "Chế độ Trợ năng cũ (khuyên dùng Tự động ADB để không bị chặn).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }

        // 4. COLLAPSIBLE PERMISSIONS CHECKLIST (Auto-collapsed once all core permissions are granted!)
        val missingCount = listOf(
            if (isAdbMode) permissions.bridgeReady else permissions.accessibilityEnabled,
            if (isAdbMode) permissions.overlayGranted else permissions.mediaProjectionReady,
            permissions.notificationsGranted,
            permissions.batteryUnrestricted,
        ).count { !it }

        ExpandableSectionCard(
            title = "Quyền hệ thống",
            subtitle = if (missingCount == 0) {
                "Đã cấp đầy đủ quyền — sẵn sàng chạy ổn định"
            } else {
                "Còn $missingCount quyền chưa bật (chạm để cấp nhanh)"
            },
            icon = Icons.Default.Security,
            accentColor = if (missingCount == 0) ClashEmerald else ClashGold,
            badgeText = if (missingCount == 0) "Đủ 4/4" else "Thiếu $missingCount",
            badgeColor = if (missingCount == 0) ClashEmerald else ClashGold,
            initiallyExpanded = !allCoreReady,
        ) {
            if (isAdbMode) {
                PermissionRow(
                    title = "1. Kết nối ${config.bridge.mode.label}",
                    subtitle = "Chụp màn hình & chạm/vuốt không cần Trợ năng",
                    granted = permissions.bridgeReady,
                    actionLabel = "Kiểm tra",
                    onAction = onTestBridge,
                )
                SettingDivider()
                PermissionRow(
                    title = "2. Hiển thị trên ứng dụng khác",
                    subtitle = "Hiện nút Bật/Tắt nổi 1 chạm trên màn hình game",
                    granted = permissions.overlayGranted,
                    actionLabel = "Cấp quyền",
                    onAction = onRequestOverlayPermission,
                )
            } else {
                PermissionRow(
                    title = "1. Dịch vụ Trợ năng (Cũ)",
                    subtitle = "Chạm/vuốt tự động & chụp màn hình",
                    granted = permissions.accessibilityEnabled,
                    actionLabel = "Mở Cài đặt",
                    onAction = onRequestAccessibility,
                )
                SettingDivider()
                PermissionRow(
                    title = "2. Chụp màn hình",
                    subtitle = "Đọc tài nguyên, Thợ xây và danh sách nâng cấp",
                    granted = permissions.mediaProjectionReady,
                    actionLabel = "Cấp quyền",
                    onAction = onRequestMediaProjection,
                )
            }
            SettingDivider()
            PermissionRow(
                title = "3. Thông báo dịch vụ nền",
                subtitle = "Giữ app chạy ổn định khi ẩn xuống nền",
                granted = permissions.notificationsGranted,
                actionLabel = "Cấp quyền",
                onAction = onRequestNotifications,
            )
            SettingDivider()
            PermissionRow(
                title = "4. Không hạn chế pin",
                subtitle = "Tránh Android tự tắt auto khi treo lâu",
                granted = permissions.batteryUnrestricted,
                actionLabel = "Tắt giới hạn",
                onAction = onRequestBatteryExemption,
            )
        }

        // 5. Compact bottom disclaimer note (no longer clutters top of screen!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = ClashGold.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Lưu ý: Không nên treo auto 24/24 liên tục để an toàn tài khoản.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
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
            .padding(vertical = 4.dp),
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
            OutlinedButton(onClick = onAction, shape = RoundedCornerShape(10.dp)) {
                Text(actionLabel, style = MaterialTheme.typography.labelMedium)
            }
        } else {
            StatusPill(text = "Đã bật", color = ClashEmerald)
        }
    }
}

private fun formatShort(amount: Long): String = when {
    amount >= 1_000_000L -> String.format("%.2fM", amount / 1_000_000.0)
    amount >= 1_000L -> "${amount / 1_000L}K"
    else -> amount.toString()
}
