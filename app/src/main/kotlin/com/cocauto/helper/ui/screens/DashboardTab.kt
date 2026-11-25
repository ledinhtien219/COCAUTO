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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
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
    val accessibilityEnabled: Boolean,
    val mediaProjectionReady: Boolean,
    val notificationsGranted: Boolean,
    val batteryUnrestricted: Boolean,
)

@Composable
fun DashboardTab(
    runtime: BotRuntimeSnapshot,
    config: BotConfig,
    permissions: PermissionSummary,
    updateState: UpdateUiState,
    onRunModeChange: (BotRunMode) -> Unit,
    onStartOrToggle: () -> Unit,
    onStop: () -> Unit,
    onToggleFloatingIcon: () -> Unit,
    onOpenCalibrationInGame: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestMediaProjection: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onDownloadUpdate: (UpdateReleaseInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
) {
    val isRunning = runtime.status == BotRunStatus.RUNNING || runtime.status == BotRunStatus.WAITING_GAME
    val isPaused = runtime.status == BotRunStatus.PAUSED
    val allCoreReady = permissions.accessibilityEnabled && permissions.mediaProjectionReady

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Automatic New Version Banner (appears whenever an update is available/downloading/ready)
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
                    text = "Lưu ý rủi ro tài khoản: Công cụ tự động chạm/OCR vi phạm Điều khoản dịch vụ (ToS) của Supercell và có thể dẫn đến cảnh báo hoặc khóa tài khoản. Chỉ nên thử nghiệm có kiểm soát, không treo 24/24 liên tục.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFDE68A),
                )
            }
        }

        // Main Control Card
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
                text = "Chế độ chạy:",
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
                enabled = permissions.accessibilityEnabled,
            ) {
                Icon(Icons.Default.RadioButtonChecked, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (runtime.floatingHudVisible) {
                        "Ẩn nút icon nổi trên màn hình"
                    } else {
                        "Hiện nút icon nổi di chuyển được (bấm để mở thao tác)"
                    },
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = onOpenCalibrationInGame,
                modifier = Modifier.fillMaxWidth(),
                enabled = permissions.accessibilityEnabled,
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Tự nhận diện màn hình trực tiếp trên game")
            }

            if (!allCoreReady) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Cần bật Dịch vụ Trợ năng và Quyền chụp màn hình ở mục bên dưới trước khi chạy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ClashGold,
                )
            }
        }

        // Permissions Checklist
        SectionCard(
            title = "Quyền hệ thống bắt buộc (Không cần Root)",
            subtitle = "Cấp đủ các quyền này để app tự đọc màn hình và chạm trên Clash of Clans",
        ) {
            PermissionRow(
                title = "1. Dịch vụ Trợ năng (AccessibilityService)",
                subtitle = "Chạm/vuốt tự động, zoom bản đồ & hiện thanh điều khiển nổi",
                granted = permissions.accessibilityEnabled,
                actionLabel = "Mở Cài đặt",
                onAction = onRequestAccessibility,
            )
            PermissionRow(
                title = "2. Chụp màn hình (Trợ năng 11+ / MediaProjection)",
                subtitle = "Android 11+ tự chụp qua Trợ năng; máy cũ dùng MediaProjection",
                granted = permissions.mediaProjectionReady,
                actionLabel = "Cấp quyền",
                onAction = onRequestMediaProjection,
            )
            PermissionRow(
                title = "3. Thông báo dịch vụ nền",
                subtitle = "Hiển thị thanh trạng thái & nút dừng nhanh trên thông báo",
                granted = permissions.notificationsGranted,
                actionLabel = "Cấp quyền",
                onAction = onRequestNotifications,
            )
            PermissionRow(
                title = "4. Không hạn chế pin (Chạy nền)",
                subtitle = "Tránh Android tự tắt dịch vụ khi treo máy lâu",
                granted = permissions.batteryUnrestricted,
                actionLabel = "Tắt giới hạn",
                onAction = onRequestBatteryExemption,
            )
        }

        // Live Session Stats
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
