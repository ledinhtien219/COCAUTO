package com.cocauto.helper.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.UpgradePriorityOrder
import com.cocauto.helper.model.WallCurrencyMode
import com.cocauto.helper.ui.components.IntNumberField
import com.cocauto.helper.ui.components.LongNumberField
import com.cocauto.helper.ui.components.SectionCard
import com.cocauto.helper.ui.components.SettingSwitchRow
import com.cocauto.helper.ui.components.SingleChoiceChipRow
import com.cocauto.helper.ui.theme.ClashCrimson
import com.cocauto.helper.ui.theme.ClashEmerald
import com.cocauto.helper.ui.theme.ClashGold
import com.cocauto.helper.ui.theme.TextSecondary

private val COMMON_SKIP_BUILDING_PRESETS = listOf(
    "Town Hall",
    "Nhà Chính",
    "Wall",
    "Tường",
    "Barbarian King",
    "Archer Queen",
    "Grand Warden",
    "Royal Champion",
    "Minion Prince",
    "Hero",
    "Trap",
    "Bẫy",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpgradeTab(
    config: BotConfig,
    builderStatus: BuilderStatus,
    onUpdateConfig: ((BotConfig) -> BotConfig) -> Unit,
) {
    val up = config.upgrade
    var newBuildingSkip by remember { mutableStateOf("") }
    var newLabSkip by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 0. Live Builder Recognition Status Card
        val hasFreeBuilder = builderStatus.isValid && builderStatus.freeBuilders > 0
        val statusColor = when {
            !builderStatus.isValid -> TextSecondary
            hasFreeBuilder -> ClashEmerald
            else -> ClashGold
        }
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (hasFreeBuilder) Color(0xFF102A18) else Color(0xFF1F242D),
            ),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = when {
                        !builderStatus.isValid -> "Nhận dạng Thợ xây: Chưa quét được (sẽ tự đọc khi vào Nhà Chính)"
                        hasFreeBuilder -> "Thợ xây: ${builderStatus.freeBuilders}/${builderStatus.totalBuilders} đang RẢNH — Sẵn sàng nâng & xây!"
                        else -> "Thợ xây: ${builderStatus.freeBuilders}/${builderStatus.totalBuilders} đang BẬN — Khóa menu nâng & xây"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
            }
        }

        // 1. Minimum Resource Reserves
        SectionCard(
            title = "Ngưỡng tài nguyên tối thiểu giữ lại",
            subtitle = "Bot sẽ KHÔNG nâng cấp nếu sau khi trừ chi phí, tài nguyên còn lại thấp hơn mức này",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LongNumberField(
                    label = "Giữ lại Vàng",
                    value = up.minKeepGold,
                    onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(minKeepGold = v)) } },
                    modifier = Modifier.weight(1f),
                )
                LongNumberField(
                    label = "Giữ lại Dầu",
                    value = up.minKeepElixir,
                    onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(minKeepElixir = v)) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LongNumberField(
                label = "Giữ lại Dầu Đen (Dark Elixir)",
                value = up.minKeepDarkElixir,
                onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(minKeepDarkElixir = v)) } },
            )
        }

        // 2. Builder Suggested Building Upgrades & New Construction
        SectionCard(
            title = "Nhận dạng Thợ xây — Tự nâng cấp & Xây mới",
            subtitle = "Đọc chính xác số Thợ xây (tách riêng khỏi Lab) — chỉ mở menu nâng cấp & đặt xây mới khi có Thợ rảnh",
        ) {
            SettingSwitchRow(
                title = "Chỉ nâng & xây khi nhận diện có Thợ xây rảnh (≥ 1)",
                subtitle = "Khi 0/N (bận hết), bot bỏ qua hoàn toàn menu Thợ xây để không bấm nhầm",
                checked = up.onlyActWhenBuilderFree,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(onlyActWhenBuilderFree = v)) } },
            )
            SettingSwitchRow(
                title = "Tự xây công trình mới (New Buildings)",
                subtitle = "Tự chọn nhà chưa xây trong gợi ý Thợ xây và bấm dấu tích xanh ✓ xác nhận",
                checked = up.autoBuildNewBuildings,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoBuildNewBuildings = v)) } },
            )
            if (up.autoBuildNewBuildings) {
                SettingSwitchRow(
                    title = "Ưu tiên xây công trình mới trước khi nâng cũ",
                    checked = up.prioritizeNewBuildingsFirst,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(prioritizeNewBuildingsFirst = v)) } },
                )
            }
            SettingSwitchRow(
                title = "Bật tự nâng công trình có sẵn khi có Thợ rảnh",
                checked = up.autoUpgradeBuildings,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoUpgradeBuildings = v)) } },
            )

            Spacer(modifier = Modifier.height(6.dp))
            IntNumberField(
                label = "Số Thợ giữ riêng để nâng Tường (0 = dùng hết)",
                value = up.reserveBuildersForWalls,
                min = 0,
                max = 3,
                onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(reserveBuildersForWalls = v)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Thứ tự ưu tiên nâng công trình:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            SingleChoiceChipRow(
                options = listOf(
                    UpgradePriorityOrder.CHEAPEST_FIRST to "Rẻ nhất trước",
                    UpgradePriorityOrder.MOST_EXPENSIVE_FIRST to "Đắt nhất trước",
                    UpgradePriorityOrder.TOP_OF_LIST to "Đầu danh sách",
                ),
                selected = up.upgradePriority,
                onSelect = { p -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(upgradePriority = p)) } },
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Bộ lọc công trình TRÁNH NÂNG (hỗ trợ cả Tiếng Anh & Tiếng Việt):",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Active blacklist chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                up.skipBuildingKeywords.forEach { keyword ->
                    InputChip(
                        selected = true,
                        onClick = {
                            onUpdateConfig {
                                it.copy(upgrade = it.upgrade.copy(skipBuildingKeywords = it.upgrade.skipBuildingKeywords - keyword))
                            }
                        },
                        label = { Text(keyword) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Xóa") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ClashCrimson.copy(alpha = 0.22f),
                            selectedLabelColor = ClashCrimson,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newBuildingSkip,
                    onValueChange = { newBuildingSkip = it },
                    label = { Text("Thêm từ khóa tránh nâng (vd: Eagle, X-Bow)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilledTonalIconButton(
                    onClick = {
                        val trimmed = newBuildingSkip.trim()
                        if (trimmed.isNotBlank() && trimmed !in up.skipBuildingKeywords) {
                            onUpdateConfig {
                                it.copy(upgrade = it.upgrade.copy(skipBuildingKeywords = it.upgrade.skipBuildingKeywords + trimmed))
                            }
                            newBuildingSkip = ""
                        }
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Thêm")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text("Gợi ý chọn nhanh:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                COMMON_SKIP_BUILDING_PRESETS.filter { it !in up.skipBuildingKeywords }.forEach { preset ->
                    AssistChip(
                        onClick = {
                            onUpdateConfig {
                                it.copy(upgrade = it.upgrade.copy(skipBuildingKeywords = it.upgrade.skipBuildingKeywords + preset))
                            }
                        },
                        label = { Text("+ $preset") },
                    )
                }
            }
        }

        // 3. Auto Wall Upgrades
        SectionCard(
            title = "Tự nâng Tường (Auto Wall)",
            subtitle = "Xả bớt Vàng / Dầu khi kho sắp đầy (có thể dùng Thợ giữ riêng ở trên)",
        ) {
            SettingSwitchRow(
                title = "Bật tự nâng Tường khi tài nguyên vượt ngưỡng",
                checked = up.autoUpgradeWalls,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoUpgradeWalls = v)) } },
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text("Loại tài nguyên dùng nâng tường:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            SingleChoiceChipRow(
                options = listOf(
                    WallCurrencyMode.BOTH to "Cả Vàng & Dầu",
                    WallCurrencyMode.GOLD_ONLY to "Chỉ Vàng",
                    WallCurrencyMode.ELIXIR_ONLY to "Chỉ Dầu",
                ),
                selected = up.wallCurrencyMode,
                onSelect = { m -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(wallCurrencyMode = m)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LongNumberField(
                    label = "Ngưỡng Vàng nâng tường",
                    value = up.wallGoldThreshold,
                    onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(wallGoldThreshold = v)) } },
                    modifier = Modifier.weight(1f),
                )
                LongNumberField(
                    label = "Ngưỡng Dầu nâng tường",
                    value = up.wallElixirThreshold,
                    onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(wallElixirThreshold = v)) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            IntNumberField(
                label = "Số ô Tường tối đa mỗi chu kỳ",
                value = up.maxWallsPerCycle,
                min = 1,
                max = 20,
                onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(maxWallsPerCycle = v)) } },
            )
        }

        // 4. Auto Laboratory Upgrades
        SectionCard(
            title = "Tự nâng Phòng Thí Nghiệm (Laboratory)",
            subtitle = "Kiểm tra định kỳ và tự nghiên cứu lính / phép khi Lab rảnh",
        ) {
            SettingSwitchRow(
                title = "Bật tự nâng cấp trong Phòng Thí Nghiệm",
                checked = up.autoUpgradeLab,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoUpgradeLab = v)) } },
            )
            SettingSwitchRow(
                title = "Ưu tiên nâng lính/phép dùng Dầu Đen trước",
                checked = up.labPreferDarkElixir,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(labPreferDarkElixir = v)) } },
            )
            Spacer(modifier = Modifier.height(6.dp))
            IntNumberField(
                label = "Chu kỳ kiểm tra Lab",
                value = up.labCheckIntervalMinutes,
                min = 5,
                max = 240,
                suffix = "phút",
                onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(labCheckIntervalMinutes = v)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text("Từ khóa lính/phép TRÁNH nâng trong Lab:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Spacer(modifier = Modifier.height(4.dp))
            if (up.skipLabKeywords.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    up.skipLabKeywords.forEach { kw ->
                        InputChip(
                            selected = true,
                            onClick = {
                                onUpdateConfig {
                                    it.copy(upgrade = it.upgrade.copy(skipLabKeywords = it.upgrade.skipLabKeywords - kw))
                                }
                            },
                            label = { Text(kw) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Xóa") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ClashGold.copy(alpha = 0.22f),
                                selectedLabelColor = ClashGold,
                            ),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newLabSkip,
                    onValueChange = { newLabSkip = it },
                    label = { Text("VD: Valkyrie, Skeleton Spell...") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(6.dp))
                FilledTonalIconButton(
                    onClick = {
                        val trimmed = newLabSkip.trim()
                        if (trimmed.isNotBlank() && trimmed !in up.skipLabKeywords) {
                            onUpdateConfig {
                                it.copy(upgrade = it.upgrade.copy(skipLabKeywords = it.upgrade.skipLabKeywords + trimmed))
                            }
                            newLabSkip = ""
                        }
                    },
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Thêm")
                }
            }
        }
    }
}
