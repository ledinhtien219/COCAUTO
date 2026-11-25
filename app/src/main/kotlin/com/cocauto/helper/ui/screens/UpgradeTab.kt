package com.cocauto.helper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.cocauto.helper.ui.components.ExpandableSectionCard
import com.cocauto.helper.ui.components.IntNumberField
import com.cocauto.helper.ui.components.LongNumberField
import com.cocauto.helper.ui.components.SectionCard
import com.cocauto.helper.ui.components.SettingDivider
import com.cocauto.helper.ui.components.SettingGroupLabel
import com.cocauto.helper.ui.components.SettingSwitchRow
import com.cocauto.helper.ui.components.SingleChoiceChipRow
import com.cocauto.helper.ui.components.StatusPill
import com.cocauto.helper.ui.theme.ClashCrimson
import com.cocauto.helper.ui.theme.ClashElixir
import com.cocauto.helper.ui.theme.ClashEmerald
import com.cocauto.helper.ui.theme.ClashGold
import com.cocauto.helper.ui.theme.ClashSky
import com.cocauto.helper.ui.theme.TextSecondary

private val COMMON_SKIP_BUILDING_PRESETS = listOf(
    "Town Hall",
    "Nhà Chính",
    "Lều Thợ xây",
    "Builder's Hut",
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
    var showBuildingBlacklist by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 0. Live Builder Status + Visual 3-Step Pipeline Card
        val hasFreeBuilder = builderStatus.isValid && builderStatus.freeBuilders > 0
        val statusColor = when {
            !builderStatus.isValid -> ClashSky
            hasFreeBuilder -> ClashEmerald
            else -> ClashGold
        }
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (hasFreeBuilder) Color(0xFF0E2616) else Color(0xFF171D26),
            ),
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.50f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(13.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            !builderStatus.isValid -> "Thợ xây: Chờ vào Nhà Chính để đọc"
                            hasFreeBuilder -> "Thợ xây: ${builderStatus.freeBuilders}/${builderStatus.totalBuilders} đang RẢNH"
                            else -> "Thợ xây: ${builderStatus.freeBuilders}/${builderStatus.totalBuilders} đang BẬN"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        modifier = Modifier.weight(1f),
                    )
                    StatusPill(
                        text = if (hasFreeBuilder) "Sẵn sàng" else "Tự động chờ",
                        color = statusColor,
                        showDot = true,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    StatusPill("1. Thời gian ngắn", ClashEmerald)
                    StatusPill("2. Nhà còn lại", ClashSky)
                    StatusPill("3. Nâng Tường cuối", ClashGold)
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Tự khớp lượng Vàng / Dầu đang có • Tự bỏ qua nhà đang nâng, giá đỏ & tuyệt đối không tiêu Gem.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }

        // 1. Building Construction & Upgrades (Core Controls)
        val priorityLabel = when (up.upgradePriority) {
            UpgradePriorityOrder.SHORTEST_TIME_FIRST -> "Ngắn nhất"
            UpgradePriorityOrder.CHEAPEST_FIRST -> "Rẻ nhất"
            UpgradePriorityOrder.MOST_EXPENSIVE_FIRST -> "Đắt nhất"
            UpgradePriorityOrder.TOP_OF_LIST -> "Đầu DS"
        }
        SectionCard(
            title = "Nâng cấp & Xây công trình",
            subtitle = "Quét tối đa 3 trang menu Thợ xây để chọn nhà đúng thứ tự ưu tiên",
            icon = Icons.Default.Construction,
            accentColor = ClashGold,
            badgeText = priorityLabel,
            badgeColor = ClashEmerald,
        ) {
            SettingSwitchRow(
                title = "Chỉ hoạt động khi có Thợ rảnh (≥ 1)",
                subtitle = "Khi 0/N (bận hết), khóa hoàn toàn menu Thợ xây",
                checked = up.onlyActWhenBuilderFree,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(onlyActWhenBuilderFree = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Tự nâng công trình có sẵn",
                checked = up.autoUpgradeBuildings,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoUpgradeBuildings = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Tự xây công trình mới (bấm tích ✓ xanh)",
                checked = up.autoBuildNewBuildings,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoBuildNewBuildings = v)) } },
            )
            if (up.autoBuildNewBuildings) {
                SettingDivider()
                SettingSwitchRow(
                    title = "Ép ưu tiên nhà mới trước nhà cũ",
                    subtitle = "Tắt để xét chung theo thời gian ngắn nhất",
                    checked = up.prioritizeNewBuildingsFirst,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(prioritizeNewBuildingsFirst = v)) } },
                )
            }

            SettingDivider()
            SettingGroupLabel("Thứ tự chọn nhà:")
            SingleChoiceChipRow(
                options = listOf(
                    UpgradePriorityOrder.SHORTEST_TIME_FIRST to "Thời gian ngắn trước",
                    UpgradePriorityOrder.CHEAPEST_FIRST to "Rẻ nhất",
                    UpgradePriorityOrder.MOST_EXPENSIVE_FIRST to "Đắt nhất",
                    UpgradePriorityOrder.TOP_OF_LIST to "Đầu danh sách",
                ),
                selected = up.upgradePriority,
                onSelect = { p -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(upgradePriority = p)) } },
            )

            Spacer(modifier = Modifier.height(6.dp))
            SettingDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showBuildingBlacklist = !showBuildingBlacklist }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Danh sách công trình TRÁNH NÂNG (${up.skipBuildingKeywords.size} mục)",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClashSky,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (showBuildingBlacklist) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = ClashSky,
                )
            }

            AnimatedVisibility(visible = showBuildingBlacklist) {
                Column {
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
                            label = { Text("Thêm từ khóa tránh nâng (vd: Eagle)") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
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

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Gợi ý thêm nhanh:", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
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
            }
        }

        // 2. Auto Wall Upgrades (Runs LAST)
        val wallBadge = if (up.autoUpgradeWalls) {
            when (up.wallCurrencyMode) {
                WallCurrencyMode.BOTH -> "Bật • Vàng & Dầu"
                WallCurrencyMode.GOLD_ONLY -> "Bật • Vàng"
                WallCurrencyMode.ELIXIR_ONLY -> "Bật • Dầu"
            }
        } else {
            "Đang tắt"
        }
        ExpandableSectionCard(
            title = "Nâng Tường (Chạy sau cùng)",
            subtitle = "Chỉ nâng Tường khi đã xét hết công trình mà vẫn còn Thợ rảnh & đủ Vàng/Dầu",
            icon = Icons.Default.GridView,
            accentColor = if (up.autoUpgradeWalls) ClashEmerald else TextSecondary,
            badgeText = wallBadge,
            badgeColor = if (up.autoUpgradeWalls) ClashEmerald else TextSecondary,
            initiallyExpanded = false,
        ) {
            SettingSwitchRow(
                title = "Bật tự nâng Tường cuối cùng",
                checked = up.autoUpgradeWalls,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoUpgradeWalls = v)) } },
            )

            AnimatedVisibility(visible = up.autoUpgradeWalls) {
                Column {
                    SettingGroupLabel("Loại tài nguyên dùng nâng tường:")
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
                            label = "Ngưỡng Vàng",
                            value = up.wallGoldThreshold,
                            onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(wallGoldThreshold = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                        LongNumberField(
                            label = "Ngưỡng Dầu",
                            value = up.wallElixirThreshold,
                            onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(wallElixirThreshold = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntNumberField(
                            label = "Tường tối đa / lượt",
                            value = up.maxWallsPerCycle,
                            min = 1,
                            max = 20,
                            onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(maxWallsPerCycle = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                        IntNumberField(
                            label = "Giữ Thợ riêng cho Tường",
                            value = up.reserveBuildersForWalls,
                            min = 0,
                            max = 3,
                            onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(reserveBuildersForWalls = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 3. Minimum Resource Reserves (Collapsible with quick preset chips!)
        ExpandableSectionCard(
            title = "Tài nguyên giữ lại tối thiểu",
            subtitle = "Giữ lại ít nhất V=${formatCompact(up.minKeepGold)} • D=${formatCompact(up.minKeepElixir)} • DE=${formatCompact(up.minKeepDarkElixir)}",
            icon = Icons.Default.Savings,
            accentColor = ClashGold,
            badgeText = "${formatCompact(up.minKeepGold)} / ${formatCompact(up.minKeepElixir)}",
            badgeColor = ClashGold,
            initiallyExpanded = false,
        ) {
            SettingGroupLabel("Chọn nhanh mức giữ lại Vàng & Dầu:")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0L to "0", 10_000L to "10K", 50_000L to "50K", 200_000L to "200K").forEach { (amt, lbl) ->
                    AssistChip(
                        onClick = {
                            onUpdateConfig {
                                it.copy(upgrade = it.upgrade.copy(minKeepGold = amt, minKeepElixir = amt))
                            }
                        },
                        label = { Text(lbl) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
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
                label = "Giữ lại Dầu Đen (DE)",
                value = up.minKeepDarkElixir,
                onValueChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(minKeepDarkElixir = v)) } },
            )
        }

        // 4. Auto Laboratory Upgrades (Collapsible!)
        ExpandableSectionCard(
            title = "Phòng Thí Nghiệm (Laboratory)",
            subtitle = "Tự nghiên cứu lính / phép khi Lab rảnh (không tốn Thợ xây)",
            icon = Icons.Default.Science,
            accentColor = if (up.autoUpgradeLab) ClashElixir else TextSecondary,
            badgeText = if (up.autoUpgradeLab) "Bật • ${up.labCheckIntervalMinutes}p" else "Đang tắt",
            badgeColor = if (up.autoUpgradeLab) ClashElixir else TextSecondary,
            initiallyExpanded = false,
        ) {
            SettingSwitchRow(
                title = "Bật tự nâng cấp trong Lab",
                checked = up.autoUpgradeLab,
                onCheckedChange = { v -> onUpdateConfig { it.copy(upgrade = it.upgrade.copy(autoUpgradeLab = v)) } },
            )
            AnimatedVisibility(visible = up.autoUpgradeLab) {
                Column {
                    SettingDivider()
                    SettingSwitchRow(
                        title = "Ưu tiên lính / phép Dầu Đen trước",
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

                    SettingGroupLabel("Từ khóa lính / phép tránh nâng:")
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
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newLabSkip,
                            onValueChange = { newLabSkip = it },
                            label = { Text("VD: Valkyrie, Skeleton Spell...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
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
    }
}

private fun formatCompact(v: Long): String = when {
    v >= 1_000_000L -> "${v / 1_000_000L}M"
    v >= 1_000L -> "${v / 1_000L}K"
    else -> v.toString()
}
