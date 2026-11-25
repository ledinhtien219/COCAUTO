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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.DeployEdge
import com.cocauto.helper.model.DeployStrategyType
import com.cocauto.helper.model.FullStorageAction
import com.cocauto.helper.model.FullStorageCondition
import com.cocauto.helper.model.LootMatchMode
import com.cocauto.helper.ui.components.ExpandableSectionCard
import com.cocauto.helper.ui.components.IntNumberField
import com.cocauto.helper.ui.components.LongNumberField
import com.cocauto.helper.ui.components.SectionCard
import com.cocauto.helper.ui.components.SettingDivider
import com.cocauto.helper.ui.components.SettingGroupLabel
import com.cocauto.helper.ui.components.SettingSwitchRow
import com.cocauto.helper.ui.components.SingleChoiceChipRow
import com.cocauto.helper.ui.theme.ClashElixir
import com.cocauto.helper.ui.theme.ClashEmerald
import com.cocauto.helper.ui.theme.ClashGold
import com.cocauto.helper.ui.theme.ClashSky
import com.cocauto.helper.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FarmTab(
    config: BotConfig,
    onUpdateConfig: ((BotConfig) -> BotConfig) -> Unit,
) {
    val home = config.homeFarm
    val bb = config.builderBase
    val safety = config.safety
    var showAdvancedDeploy by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. PRIMARY FARM CARD — Ultra clean & scannable!
        SectionCard(
            title = "Farm Nhà Chính",
            subtitle = "Clash Anytime • Đánh xong tự Về làng chạy lệnh tiếp theo",
            icon = Icons.Default.Bolt,
            accentColor = if (home.attackWhenArmyReady) ClashEmerald else ClashGold,
            badgeText = if (home.attackWhenArmyReady) "CHIẾN NGAY" else "LỌC NHÀ",
            badgeColor = if (home.attackWhenArmyReady) ClashEmerald else ClashGold,
        ) {
            SettingSwitchRow(
                title = "Đủ quân là chiến ngay",
                subtitle = "Bỏ qua mọi bộ lọc Vàng/Dầu — hễ đủ quân là đánh ngay nhà đầu tiên",
                checked = home.attackWhenArmyReady,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(attackWhenArmyReady = v)) } },
            )

            if (home.attackWhenArmyReady) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0E2616)),
                    border = BorderStroke(1.dp, ClashEmerald.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "✓ Đang chạy nhanh: Có quân trên thanh thả lính là đánh luôn, không cần chờ đủ điều kiện.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = ClashEmerald,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            SettingDivider()
            SettingSwitchRow(
                title = "Tự động tìm nhà & đánh",
                checked = home.autoAttack,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(autoAttack = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Thu hoạch mỏ tài nguyên",
                subtitle = "Tự quét & chạm bong bóng Vàng / Dầu / DE",
                checked = home.autoCollectResources,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(autoCollectResources = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Thu hoạch Xe cướp (Loot Cart)",
                checked = home.collectLootCart,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(collectLootCart = v)) } },
            )

            // Detailed loot filter ONLY shown when user disables Fast Attack mode
            AnimatedVisibility(visible = !home.attackWhenArmyReady) {
                Column {
                    SettingDivider()
                    SettingGroupLabel("Cài nhanh ngưỡng lọc theo cấp TH:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AssistChip(
                            onClick = {
                                onUpdateConfig {
                                    it.copy(
                                        homeFarm = it.homeFarm.copy(
                                            lootMatchMode = LootMatchMode.COMBINED_SUM,
                                            minCombinedGoldElixir = 40_000L,
                                            minGold = 20_000L,
                                            minElixir = 20_000L,
                                            minDarkElixir = 0L,
                                        ),
                                    )
                                }
                            },
                            label = { Text("TH 3–6 (40K)") },
                        )
                        AssistChip(
                            onClick = {
                                onUpdateConfig {
                                    it.copy(
                                        homeFarm = it.homeFarm.copy(
                                            lootMatchMode = LootMatchMode.COMBINED_SUM,
                                            minCombinedGoldElixir = 250_000L,
                                            minGold = 120_000L,
                                            minElixir = 120_000L,
                                            minDarkElixir = 500L,
                                        ),
                                    )
                                }
                            },
                            label = { Text("TH 7–9 (250K)") },
                        )
                        AssistChip(
                            onClick = {
                                onUpdateConfig {
                                    it.copy(
                                        homeFarm = it.homeFarm.copy(
                                            lootMatchMode = LootMatchMode.COMBINED_SUM,
                                            minCombinedGoldElixir = 600_000L,
                                            minGold = 300_000L,
                                            minElixir = 300_000L,
                                            minDarkElixir = 2_500L,
                                        ),
                                    )
                                }
                            },
                            label = { Text("TH 10–12 (600K)") },
                        )
                        AssistChip(
                            onClick = {
                                onUpdateConfig {
                                    it.copy(
                                        homeFarm = it.homeFarm.copy(
                                            lootMatchMode = LootMatchMode.COMBINED_SUM,
                                            minCombinedGoldElixir = 900_000L,
                                            minGold = 450_000L,
                                            minElixir = 450_000L,
                                            minDarkElixir = 4_500L,
                                        ),
                                    )
                                }
                            },
                            label = { Text("TH 13+ (900K)") },
                        )
                    }

                    SettingGroupLabel("Điều kiện lọc:")
                    SingleChoiceChipRow(
                        options = listOf(
                            LootMatchMode.COMBINED_SUM to "Tổng Vàng + Dầu",
                            LootMatchMode.ALL_MATCH to "Đạt tất cả",
                            LootMatchMode.ANY_MATCH to "Đạt 1 trong 3",
                        ),
                        selected = home.lootMatchMode,
                        onSelect = { m -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(lootMatchMode = m)) } },
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    if (home.lootMatchMode == LootMatchMode.COMBINED_SUM) {
                        LongNumberField(
                            label = "Tổng Vàng + Dầu tối thiểu",
                            value = home.minCombinedGoldElixir,
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(minCombinedGoldElixir = v)) } },
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LongNumberField(
                                label = "Vàng tối thiểu",
                                value = home.minGold,
                                onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(minGold = v)) } },
                                modifier = Modifier.weight(1f),
                            )
                            LongNumberField(
                                label = "Dầu tối thiểu",
                                value = home.minElixir,
                                onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(minElixir = v)) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LongNumberField(
                            label = "Dầu Đen (0 = bỏ)",
                            value = home.minDarkElixir,
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(minDarkElixir = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                        IntNumberField(
                            label = "Số Next tối đa",
                            value = home.maxNextCount,
                            min = 1,
                            max = 200,
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(maxNextCount = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    SettingSwitchRow(
                        title = "Đánh luôn nếu chạm giới hạn Next",
                        checked = home.attackWhenMaxNextReached,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(attackWhenMaxNextReached = v)) } },
                    )
                }
            }
        }

        // 2. TROOP DEPLOYMENT STRATEGY (Essential choices upfront, rare slot overrides tucked cleanly!)
        val strategyLabel = when (home.deployStrategy) {
            DeployStrategyType.FOUR_SIDES_SURROUND -> "Rải 4 cạnh"
            DeployStrategyType.FOCUSED_DIRECTION -> "Dồn hướng"
            DeployStrategyType.PACED_WAVES -> "Theo nhịp"
        }
        ExpandableSectionCard(
            title = "Chiến thuật thả quân",
            subtitle = "Tự nhận diện thanh lính • Giữ nhả quân siêu nhanh",
            icon = Icons.Default.MilitaryTech,
            accentColor = ClashGold,
            badgeText = "$strategyLabel • ${home.maxBattleDurationSec}s",
            badgeColor = ClashGold,
            initiallyExpanded = true,
        ) {
            SingleChoiceChipRow(
                options = listOf(
                    DeployStrategyType.FOUR_SIDES_SURROUND to "Rải 4 cạnh",
                    DeployStrategyType.FOCUSED_DIRECTION to "Dồn 1–2 hướng",
                    DeployStrategyType.PACED_WAVES to "Thả theo nhịp",
                ),
                selected = home.deployStrategy,
                onSelect = { s -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(deployStrategy = s)) } },
            )

            if (home.deployStrategy != DeployStrategyType.FOUR_SIDES_SURROUND) {
                SettingGroupLabel("Hướng thả quân:")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        DeployEdge.NW to "Tây-Bắc (NW)",
                        DeployEdge.NE to "Đông-Bắc (NE)",
                        DeployEdge.SW to "Tây-Nam (SW)",
                        DeployEdge.SE to "Đông-Nam (SE)",
                    ).forEach { (edge, label) ->
                        val selected = edge in home.focusedEdges
                        FilterChip(
                            selected = selected,
                            onClick = {
                                val next = if (selected && home.focusedEdges.size > 1) {
                                    home.focusedEdges - edge
                                } else {
                                    home.focusedEdges + edge
                                }
                                onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(focusedEdges = next)) }
                            },
                            label = { Text(label) },
                            shape = RoundedCornerShape(10.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ClashGold.copy(alpha = 0.22f),
                                selectedLabelColor = ClashGold,
                            ),
                        )
                    }
                }
            }

            if (home.deployStrategy == DeployStrategyType.PACED_WAVES) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntNumberField(
                        label = "Số đợt sóng",
                        value = home.deployWaveCount,
                        min = 1,
                        max = 6,
                        onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(deployWaveCount = v)) } },
                        modifier = Modifier.weight(1f),
                    )
                    LongNumberField(
                        label = "Trễ mỗi đợt",
                        value = home.waveDelayMs,
                        suffix = "ms",
                        onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(waveDelayMs = v)) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            SettingDivider()
            SettingSwitchRow(
                title = "Đầu hàng sớm khi thả hết quân",
                subtitle = "Quay về làng sớm thay vì đợi lính dọn hết bản đồ",
                checked = home.earlySurrenderWhenTroopsEmpty,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(earlySurrenderWhenTroopsEmpty = v)) } },
            )
            if (home.earlySurrenderWhenTroopsEmpty) {
                Spacer(modifier = Modifier.height(4.dp))
                IntNumberField(
                    label = "Đợi thêm sau khi hết quân",
                    value = home.earlySurrenderWaitSec,
                    min = 5,
                    max = 90,
                    suffix = "giây",
                    onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(earlySurrenderWaitSec = v)) } },
                )
            }

            SettingDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showAdvancedDeploy = !showAdvancedDeploy }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (showAdvancedDeploy) "Ẩn tùy chỉnh ô lính & thời gian" else "Tùy chỉnh nâng cao (ô lính, tướng, phép, thời gian)",
                    style = MaterialTheme.typography.labelLarge,
                    color = ClashSky,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (showAdvancedDeploy) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = ClashSky,
                )
            }

            AnimatedVisibility(visible = showAdvancedDeploy) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntNumberField(
                            label = "Bật skill Tướng sau",
                            value = home.triggerHeroAbilityAfterSec,
                            min = 0,
                            max = 45,
                            suffix = "s",
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(triggerHeroAbilityAfterSec = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                        IntNumberField(
                            label = "Tối đa 1 trận",
                            value = home.maxBattleDurationSec,
                            min = 25,
                            max = 210,
                            suffix = "s",
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(maxBattleDurationSec = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntNumberField(
                            label = "Số ô lính dự phòng",
                            value = home.troopSlotCount,
                            min = 1,
                            max = 8,
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(troopSlotCount = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                        IntNumberField(
                            label = "Chạm / điểm",
                            value = home.tapsPerPoint,
                            min = 1,
                            max = 8,
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(tapsPerPoint = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SettingSwitchRow(
                        title = "Bỏ qua ô lính hết (màu xám)",
                        checked = home.skipEmptySlots,
                        onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(skipEmptySlots = v)) } },
                    )
                    SettingGroupLabel("Vị trí ô Tướng dự phòng:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (4..10).forEach { slot ->
                            val chosen = slot in home.heroSlotIndices
                            FilterChip(
                                selected = chosen,
                                onClick = {
                                    val next = if (chosen) home.heroSlotIndices - slot else home.heroSlotIndices + slot
                                    onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(heroSlotIndices = next)) }
                                },
                                label = { Text("Ô $slot") },
                            )
                        }
                    }
                    SettingGroupLabel("Vị trí ô Phép dự phòng:")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (7..12).forEach { slot ->
                            val chosen = slot in home.spellSlotIndices
                            FilterChip(
                                selected = chosen,
                                onClick = {
                                    val next = if (chosen) home.spellSlotIndices - slot else home.spellSlotIndices + slot
                                    onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(spellSlotIndices = next)) }
                                },
                                label = { Text("Ô $slot") },
                            )
                        }
                    }
                }
            }
        }

        // 3. AUTO STOP WHEN STORAGE FULL (Collapsible — collapsed by default when OFF!)
        ExpandableSectionCard(
            title = "Tự dừng khi kho đầy",
            subtitle = "Tự đo % đầy thanh Vàng/Dầu và dừng hoặc tạm nghỉ chờ Thợ rảnh",
            icon = Icons.Default.Inventory2,
            accentColor = if (home.stopWhenStorageFull) ClashGold else TextSecondary,
            badgeText = if (home.stopWhenStorageFull) "Bật • ${home.fullStorageFillPercent}%" else "Đang tắt",
            badgeColor = if (home.stopWhenStorageFull) ClashGold else TextSecondary,
            initiallyExpanded = home.stopWhenStorageFull,
        ) {
            SettingSwitchRow(
                title = "Bật tự dừng / tạm nghỉ khi kho đầy",
                checked = home.stopWhenStorageFull,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(stopWhenStorageFull = v)) } },
            )

            AnimatedVisibility(visible = home.stopWhenStorageFull) {
                Column {
                    SettingGroupLabel("Hành động khi kho đầy:")
                    SingleChoiceChipRow(
                        options = listOf(
                            FullStorageAction.PAUSE_WAIT_BUILDER to "Nghỉ chờ Thợ",
                            FullStorageAction.STOP_BOT to "Dừng hẳn Auto",
                        ),
                        selected = home.fullStorageAction,
                        onSelect = { a -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageAction = a)) } },
                    )

                    SettingGroupLabel("Điều kiện đầy:")
                    SingleChoiceChipRow(
                        options = listOf(
                            FullStorageCondition.GOLD_AND_ELIXIR to "Cả Vàng & Dầu",
                            FullStorageCondition.GOLD_OR_ELIXIR to "Vàng hoặc Dầu",
                            FullStorageCondition.ALL_INCLUDING_DE to "Vàng + Dầu + DE",
                        ),
                        selected = home.fullStorageCondition,
                        onSelect = { c -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageCondition = c)) } },
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IntNumberField(
                            label = "Mức thanh màu",
                            value = home.fullStorageFillPercent,
                            min = 50,
                            max = 100,
                            suffix = "%",
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageFillPercent = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                        if (home.fullStorageAction == FullStorageAction.PAUSE_WAIT_BUILDER) {
                            IntNumberField(
                                label = "Kiểm tra lại",
                                value = home.fullStorageRecheckMinutes,
                                min = 1,
                                max = 60,
                                suffix = "phút",
                                onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageRecheckMinutes = v)) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LongNumberField(
                            label = "Mốc Vàng (0 = tự đo)",
                            value = home.fullStorageGoldCap,
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageGoldCap = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                        LongNumberField(
                            label = "Mốc Dầu (0 = tự đo)",
                            value = home.fullStorageElixirCap,
                            onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageElixirCap = v)) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // 4. BUILDER BASE AUTO FARM (Collapsible — tucked neatly when disabled!)
        ExpandableSectionCard(
            title = "Farm Nhà Thợ Xây (Builder Base)",
            subtitle = "Chuyển thuyền, kích Tháp Đồng Hồ và farm luân phiên",
            icon = Icons.Default.HomeRepairService,
            accentColor = if (bb.enabled) ClashElixir else TextSecondary,
            badgeText = if (bb.enabled) "Bật • ${bb.attacksPerCycle} trận" else "Đang tắt",
            badgeColor = if (bb.enabled) ClashElixir else TextSecondary,
            initiallyExpanded = bb.enabled,
        ) {
            SettingSwitchRow(
                title = "Bật Auto Nhà Thợ Xây (chế độ Cả 2 làng)",
                checked = bb.enabled,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(enabled = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Thu hoạch mỏ Vàng / Dầu Nhà Thợ Xây",
                checked = bb.collectResources,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(collectResources = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Kích hoạt Tháp Đồng Hồ (Clock Tower)",
                checked = bb.activateClockTower,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(activateClockTower = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Farm nhanh (thả hết rồi đầu hàng sớm)",
                checked = bb.fastSurrenderMode,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(fastSurrenderMode = v)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntNumberField(
                    label = "Số trận / vòng",
                    value = bb.attacksPerCycle,
                    min = 1,
                    max = 30,
                    onValueChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(attacksPerCycle = v)) } },
                    modifier = Modifier.weight(1f),
                )
                IntNumberField(
                    label = if (bb.fastSurrenderMode) "Đầu hàng sau" else "Tối đa 1 trận",
                    value = if (bb.fastSurrenderMode) bb.fastSurrenderDelaySec else bb.maxBattleDurationSec,
                    min = 6,
                    max = 150,
                    suffix = "s",
                    onValueChange = { v ->
                        onUpdateConfig {
                            if (bb.fastSurrenderMode) {
                                it.copy(builderBase = it.builderBase.copy(fastSurrenderDelaySec = v))
                            } else {
                                it.copy(builderBase = it.builderBase.copy(maxBattleDurationSec = v))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            SettingGroupLabel("Hướng thả quân:")
            SingleChoiceChipRow(
                options = listOf(
                    DeployEdge.NW to "NW",
                    DeployEdge.NE to "NE",
                    DeployEdge.SW to "SW",
                    DeployEdge.SE to "SE",
                ),
                selected = bb.deployEdge,
                onSelect = { e -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(deployEdge = e)) } },
            )
        }

        // 5. SESSION WATCHDOG & SAFETY (Collapsible — smart defaults already enabled!)
        ExpandableSectionCard(
            title = "Giữ phiên & Tự khôi phục",
            subtitle = "Tự mở lại game khi văng, tự bấm Tải lại và giữ màn hình thức",
            icon = Icons.Default.Shield,
            accentColor = ClashSky,
            badgeText = "An toàn",
            badgeColor = ClashSky,
            initiallyExpanded = false,
        ) {
            SettingSwitchRow(
                title = "Tự mở lại Clash of Clans khi bị văng",
                checked = safety.autoRelaunchGame,
                onCheckedChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(autoRelaunchGame = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Tự bấm Tải lại (Reload) khi mất mạng",
                checked = safety.autoReloadOnDisconnect,
                onCheckedChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(autoReloadOnDisconnect = v)) } },
            )
            SettingDivider()
            SettingSwitchRow(
                title = "Giữ CPU thức khi treo máy (WakeLock)",
                checked = safety.keepScreenAwake,
                onCheckedChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(keepScreenAwake = v)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntNumberField(
                    label = "Độ lệch chạm",
                    value = safety.humanizeJitterPx,
                    min = 0,
                    max = 25,
                    suffix = "px",
                    onValueChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(humanizeJitterPx = v)) } },
                    modifier = Modifier.weight(1f),
                )
                IntNumberField(
                    label = "Nghỉ khi Break",
                    value = safety.personalBreakPauseMinutes,
                    min = 1,
                    max = 60,
                    suffix = "phút",
                    onValueChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(personalBreakPauseMinutes = v)) } },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            IntNumberField(
                label = "Số vòng Nhà Chính trước khi qua Builder Base",
                value = safety.homeCyclesBeforeBuilderBase,
                min = 1,
                max = 20,
                onValueChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(homeCyclesBeforeBuilderBase = v)) } },
            )
        }
    }
}
