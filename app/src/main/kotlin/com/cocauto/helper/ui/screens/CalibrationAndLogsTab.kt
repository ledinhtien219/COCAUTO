package com.cocauto.helper.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cocauto.helper.model.AspectPreset
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.BotLogEntry
import com.cocauto.helper.model.BotRuntimeSnapshot
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.LogLevel
import com.cocauto.helper.ui.components.ExpandableSectionCard
import com.cocauto.helper.ui.components.SectionCard
import com.cocauto.helper.ui.components.SettingDivider
import com.cocauto.helper.ui.components.SettingGroupLabel
import com.cocauto.helper.ui.components.SettingSwitchRow
import com.cocauto.helper.ui.components.SingleChoiceChipRow
import com.cocauto.helper.ui.theme.ClashCrimson
import com.cocauto.helper.ui.theme.ClashEmerald
import com.cocauto.helper.ui.theme.ClashGold
import com.cocauto.helper.ui.theme.ClashSky
import com.cocauto.helper.ui.theme.TextSecondary
import com.cocauto.helper.updater.UpdateReleaseInfo
import com.cocauto.helper.updater.UpdateUiState
import java.io.File

@Composable
fun CalibrationAndLogsTab(
    runtime: BotRuntimeSnapshot,
    config: BotConfig,
    calibration: CalibrationProfile,
    logs: List<BotLogEntry>,
    currentVersionLabel: String,
    updateState: UpdateUiState,
    onUpdateConfig: ((BotConfig) -> BotConfig) -> Unit,
    onCheckUpdatesNow: () -> Unit,
    onDownloadUpdate: (UpdateReleaseInfo) -> Unit,
    onInstallUpdate: (File) -> Unit,
    onSelectPreset: (AspectPreset) -> Unit,
    onOpenCalibrationOverlay: () -> Unit,
    onRunOcrTestNow: () -> Unit,
    onExportJson: () -> String,
    onImportJson: (String) -> Boolean,
    onClearLogs: () -> Unit,
) {
    val context = LocalContext.current
    var importBuffer by remember { mutableStateOf("") }
    val upCfg = config.update
    val hasActionableUpdate = updateState is UpdateUiState.Available ||
        updateState is UpdateUiState.Downloading ||
        updateState is UpdateUiState.ReadyToInstall

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Calibration & Live OCR Test
        SectionCard(
            title = "Nhận diện màn hình & OCR",
            subtitle = "Tự căn tọa độ nút & khung tài nguyên ngay khi vào Clash of Clans",
            icon = Icons.Default.Tune,
            accentColor = ClashGold,
            badgeText = calibration.preset.label,
            badgeColor = ClashSky,
        ) {
            SettingSwitchRow(
                title = "Tự nhận diện giao diện màn hình",
                subtitle = "Khuyên bật — tự khớp mọi tỷ lệ màn hình",
                checked = config.safety.autoCalibrateScreen,
                onCheckedChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(autoCalibrateScreen = v)) } },
            )

            SettingDivider()
            SettingGroupLabel("Tỷ lệ màn hình dự phòng:")
            SingleChoiceChipRow(
                options = AspectPreset.entries.map { it to it.label },
                selected = calibration.preset,
                onSelect = onSelectPreset,
            )

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenCalibrationOverlay,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Nhận diện", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onRunOcrTestNow,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Thử OCR")
                }
            }

            if (runtime.lastOcrTestSummary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = runtime.lastOcrTestSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = ClashSky,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B1926))
                        .padding(10.dp),
                )
            }
        }

        // 2. Auto-Update Card (Collapsible unless an update is available)
        ExpandableSectionCard(
            title = "Cập nhật ứng dụng",
            subtitle = "Phiên bản hiện tại: $currentVersionLabel",
            icon = Icons.Default.SystemUpdate,
            accentColor = ClashSky,
            badgeText = when (updateState) {
                is UpdateUiState.Available -> "Có bản mới!"
                is UpdateUiState.ReadyToInstall -> "Sẵn sàng cài"
                else -> currentVersionLabel
            },
            badgeColor = if (hasActionableUpdate) ClashEmerald else ClashSky,
            initiallyExpanded = hasActionableUpdate,
        ) {
            SettingSwitchRow(
                title = "Tự kiểm tra cập nhật khi mở app",
                checked = upCfg.autoCheckOnStartup,
                onCheckedChange = { v -> onUpdateConfig { it.copy(update = it.update.copy(autoCheckOnStartup = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Tự tải APK khi phát hiện bản mới",
                checked = upCfg.autoDownloadWhenAvailable,
                onCheckedChange = { v -> onUpdateConfig { it.copy(update = it.update.copy(autoDownloadWhenAvailable = v)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = upCfg.updateSourceUrl,
                onValueChange = { url -> onUpdateConfig { it.copy(update = it.update.copy(updateSourceUrl = url)) } },
                label = { Text("Nguồn cập nhật (GitHub / URL)") },
                placeholder = { Text("ledinhtien219/COCAUTO") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onCheckUpdatesNow,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                enabled = updateState !is UpdateUiState.Checking && updateState !is UpdateUiState.Downloading,
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    if (updateState is UpdateUiState.Checking) "Đang kiểm tra..." else "Kiểm tra cập nhật ngay",
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            when (updateState) {
                is UpdateUiState.Idle -> Unit
                is UpdateUiState.Checking -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is UpdateUiState.UpToDate -> {
                    Text(
                        text = "✓ Bạn đang dùng bản mới nhất (v${updateState.currentVersion}).",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClashEmerald,
                    )
                }

                is UpdateUiState.Available -> {
                    Text(
                        text = "Phát hiện bản mới: v${updateState.release.versionName}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = ClashSky,
                    )
                    if (updateState.release.changelog.isNotBlank()) {
                        Text(
                            text = updateState.release.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = { onDownloadUpdate(updateState.release) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClashSky, contentColor = Color(0xFF031926)),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tải & Cài đặt v${updateState.release.versionName}", fontWeight = FontWeight.Bold)
                    }
                }

                is UpdateUiState.Downloading -> {
                    val mbDown = updateState.downloadedBytes / (1024f * 1024f)
                    val mbTotal = if (updateState.totalBytes > 0L) updateState.totalBytes / (1024f * 1024f) else 0f
                    val sizeText = if (mbTotal > 0f) String.format("%.1f / %.1f MB", mbDown, mbTotal) else String.format("%.1f MB", mbDown)
                    Text(
                        text = "Đang tải v${updateState.release.versionName}: ${updateState.progressPercent}% ($sizeText)",
                        style = MaterialTheme.typography.bodySmall,
                        color = ClashSky,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { (updateState.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                is UpdateUiState.ReadyToInstall -> {
                    Button(
                        onClick = { onInstallUpdate(updateState.apkFile) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClashEmerald, contentColor = Color(0xFF071B0D)),
                    ) {
                        Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cài đặt bản v${updateState.release.versionName}", fontWeight = FontWeight.Bold)
                    }
                }

                is UpdateUiState.Error -> {
                    Text(
                        text = updateState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClashCrimson,
                    )
                }
            }
        }

        // 3. Backup / Share Config JSON (Collapsible!)
        ExpandableSectionCard(
            title = "Sao lưu & Khôi phục (JSON)",
            subtitle = "Xuất hoặc nhập toàn bộ cài đặt & tọa độ sang máy khác",
            icon = Icons.Default.Backup,
            accentColor = ClashGold,
            initiallyExpanded = false,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val json = onExportJson()
                        copyToClipboard(context, "CoC Auto Config", json)
                        Toast.makeText(context, "Đã sao chép JSON cấu hình!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sao chép")
                }

                Button(
                    onClick = {
                        if (importBuffer.isNotBlank()) {
                            val ok = onImportJson(importBuffer)
                            Toast.makeText(
                                context,
                                if (ok) "Đã nhập cấu hình thành công!" else "JSON không hợp lệ!",
                                Toast.LENGTH_SHORT,
                            ).show()
                            if (ok) importBuffer = ""
                        }
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Khôi phục")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = importBuffer,
                onValueChange = { importBuffer = it },
                label = { Text("Dán chuỗi JSON cấu hình vào đây") },
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 4. Live Logs Console
        SectionCard(
            title = "Nhật ký hoạt động",
            subtitle = "Theo dõi OCR, thả quân và nâng cấp theo thời gian thực",
            icon = Icons.Default.Terminal,
            accentColor = ClashEmerald,
            badgeText = "${logs.size} dòng",
            badgeColor = ClashSky,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        val allText = logs.joinToString("\n") { "[${it.formattedTime()}] [${it.level}] ${it.message}" }
                        copyToClipboard(context, "CoC Auto Logs", allText)
                        Toast.makeText(context, "Đã sao chép nhật ký!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sao chép")
                }
                OutlinedButton(
                    onClick = onClearLogs,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Xóa log")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A0E14))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (logs.isEmpty()) {
                    Text(
                        text = "Chưa có nhật ký. Bấm 'Bắt đầu Auto' hoặc 'Thử OCR' để xem log.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                } else {
                    logs.take(80).forEach { entry ->
                        val color = when (entry.level) {
                            LogLevel.SUCCESS -> ClashEmerald
                            LogLevel.WARN -> ClashGold
                            LogLevel.ERROR -> ClashCrimson
                            LogLevel.INFO -> Color(0xFFC9D1D9)
                        }
                        Text(
                            text = "[${entry.formattedTime()}] ${entry.message}",
                            color = color,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
