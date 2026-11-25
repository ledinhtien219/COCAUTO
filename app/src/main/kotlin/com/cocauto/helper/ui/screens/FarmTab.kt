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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.cocauto.helper.ui.components.IntNumberField
import com.cocauto.helper.ui.components.LongNumberField
import com.cocauto.helper.ui.components.SectionCard
import com.cocauto.helper.ui.components.SettingSwitchRow
import com.cocauto.helper.ui.components.SingleChoiceChipRow
import com.cocauto.helper.ui.theme.ClashEmerald
import com.cocauto.helper.ui.theme.ClashGold
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 1. Home Village Loot & Collection
        SectionCard(
            title = "Auto Farm Nhà Chính (Clash Anytime)",
            subtitle = "Mặc định chỉ cần đủ quân là tự đánh ngay — không chờ đủ các điều kiện lọc Vàng/Dầu",
        ) {
            SettingSwitchRow(
                title = "Đủ quân là chiến ngay (Bỏ qua lọc tài nguyên)",
                subtitle = "Bật: cứ có quân là đánh ngay nhà đầu tiên. Tắt: lọc nhà theo ngưỡng Vàng/Dầu bên dưới",
                checked = home.attackWhenArmyReady,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(attackWhenArmyReady = v)) } },
            )

            if (home.attackWhenArmyReady) {
                Spacer(modifier = Modifier.height(6.dp))
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF102A18)),
                    border = BorderStroke(1.dp, ClashEmerald.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Chế độ Chiến nhanh đang BẬT: Chỉ cần đủ quân trên thanh lính là tự thả quân đánh ngay, đánh xong tự Về làng chạy trận tiếp theo!",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = ClashEmerald,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            SettingSwitchRow(
                title = "Thu hoạch mỏ Vàng / Dầu / Dầu Đen",
                subtitle = "Tự quét bong bóng tài nguyên trên bản đồ làng",
                checked = home.autoCollectResources,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(autoCollectResources = v)) } },
            )
            SettingSwitchRow(
                title = "Thu hoạch Xe cướp tài nguyên (Loot Cart)",
                checked = home.collectLootCart,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(collectLootCart = v)) } },
            )
            SettingSwitchRow(
                title = "Tự động tìm nhà & đánh",
                subtitle = "Hỗ trợ luồng Clash Anytime không cần chờ huấn luyện quân",
                checked = home.autoAttack,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(autoAttack = v)) } },
            )

            if (!home.attackWhenArmyReady) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Cài nhanh ngưỡng lọc theo cấp Nhà Chính (TH):",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                                        troopSlotCount = 4,
                                        heroSlotIndices = emptySet(),
                                        spellSlotIndices = emptySet(),
                                    ),
                                )
                            }
                        },
                        label = { Text("TH 3–6 (40K+)") },
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
                                        troopSlotCount = 4,
                                        heroSlotIndices = setOf(5, 6),
                                        spellSlotIndices = setOf(7, 8),
                                    ),
                                )
                            }
                        },
                        label = { Text("TH 7–9 (250K+)") },
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
                                        troopSlotCount = 4,
                                        heroSlotIndices = setOf(5, 6, 7),
                                        spellSlotIndices = setOf(8, 9),
                                    ),
                                )
                            }
                        },
                        label = { Text("TH 10–12 (600K+)") },
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
                                        troopSlotCount = 4,
                                        heroSlotIndices = setOf(5, 6, 7, 8),
                                        spellSlotIndices = setOf(9, 10),
                                    ),
                                )
                            }
                        },
                        label = { Text("TH 13+ (900K+)") },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Điều kiện lọc tài nguyên nhà địch:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
                SingleChoiceChipRow(
                    options = listOf(
                        LootMatchMode.COMBINED_SUM to "Tổng Vàng + Dầu",
                        LootMatchMode.ALL_MATCH to "Đạt tất cả",
                        LootMatchMode.ANY_MATCH to "Đạt 1 trong 3",
                    ),
                    selected = home.lootMatchMode,
                    onSelect = { m -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(lootMatchMode = m)) } },
                )

                Spacer(modifier = Modifier.height(10.dp))
                if (home.lootMatchMode == LootMatchMode.COMBINED_SUM) {
                    LongNumberField(
                        label = "Tổng Vàng + Dầu tối thiểu",
                        value = home.minCombinedGoldElixir,
                        onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(minCombinedGoldElixir = v)) } },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
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
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LongNumberField(
                        label = "Dầu Đen tối thiểu (0 = bỏ qua)",
                        value = home.minDarkElixir,
                        onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(minDarkElixir = v)) } },
                        modifier = Modifier.weight(1f),
                    )
                    IntNumberField(
                        label = "Số lần Next tối đa",
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
                    subtitle = "Nếu tắt, bot sẽ quay về nhà chính rồi tìm lại",
                    checked = home.attackWhenMaxNextReached,
                    onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(attackWhenMaxNextReached = v)) } },
                )
            }
        }

        // 1.5. Auto Stop / Pause when Storages are Full
        SectionCard(
            title = "Tự dừng / Tạm nghỉ khi kho đầy",
            subtitle = "Tự đo % đầy thanh Vàng/Dầu trên màn hình (không cần nhập sức chứa theo cấp TH), thử nâng cấp trước rồi mới dừng nếu tất cả thợ đang bận",
        ) {
            SettingSwitchRow(
                title = "Bật tự dừng farm khi kho đầy",
                checked = home.stopWhenStorageFull,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(stopWhenStorageFull = v)) } },
            )

            if (home.stopWhenStorageFull) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("Hành động khi kho đầy:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                SingleChoiceChipRow(
                    options = listOf(
                        FullStorageAction.PAUSE_WAIT_BUILDER to "Tạm nghỉ chờ Thợ rảnh",
                        FullStorageAction.STOP_BOT to "Dừng hẳn Auto",
                    ),
                    selected = home.fullStorageAction,
                    onSelect = { a -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageAction = a)) } },
                )

                Spacer(modifier = Modifier.height(6.dp))
                Text("Điều kiện coi là kho đầy:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                SingleChoiceChipRow(
                    options = listOf(
                        FullStorageCondition.GOLD_AND_ELIXIR to "Đầy cả Vàng & Dầu",
                        FullStorageCondition.GOLD_OR_ELIXIR to "Đầy Vàng hoặc Dầu",
                        FullStorageCondition.ALL_INCLUDING_DE to "Đầy cả Vàng + Dầu + DE",
                    ),
                    selected = home.fullStorageCondition,
                    onSelect = { c -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageCondition = c)) } },
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntNumberField(
                        label = "Mức đầy thanh màu (%)",
                        value = home.fullStorageFillPercent,
                        min = 50,
                        max = 100,
                        suffix = "%",
                        onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageFillPercent = v)) } },
                        modifier = Modifier.weight(1f),
                    )
                    if (home.fullStorageAction == FullStorageAction.PAUSE_WAIT_BUILDER) {
                        IntNumberField(
                            label = "Kiểm tra lại sau",
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
                Text(
                    text = "Mốc số tối đa tùy chọn (để 0 = chỉ dùng tự đo % thanh màu):",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LongNumberField(
                        label = "Mốc Vàng đầy",
                        value = home.fullStorageGoldCap,
                        onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageGoldCap = v)) } },
                        modifier = Modifier.weight(1f),
                    )
                    LongNumberField(
                        label = "Mốc Dầu đầy",
                        value = home.fullStorageElixirCap,
                        onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(fullStorageElixirCap = v)) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 2. Troop Deployment Strategy
        SectionCard(
            title = "Chiến thuật thả quân",
            subtitle = "Linh hoạt nhiều kiểu thả quân theo đội hình bạn đang dùng",
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
                Spacer(modifier = Modifier.height(8.dp))
                Text("Chọn hướng thả quân (có thể chọn nhiều):", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(modifier = Modifier.height(4.dp))
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
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ClashGold.copy(alpha = 0.22f),
                                selectedLabelColor = ClashGold,
                            ),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntNumberField(
                    label = "Số ô lính (1..8)",
                    value = home.troopSlotCount,
                    min = 1,
                    max = 8,
                    onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(troopSlotCount = v)) } },
                    modifier = Modifier.weight(1f),
                )
                IntNumberField(
                    label = "Chạm / mỗi điểm",
                    value = home.tapsPerPoint,
                    min = 1,
                    max = 8,
                    onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(tapsPerPoint = v)) } },
                    modifier = Modifier.weight(1f),
                )
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

            Spacer(modifier = Modifier.height(8.dp))
            Text("Vị trí ô Tướng trên thanh quân:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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

            Spacer(modifier = Modifier.height(6.dp))
            Text("Vị trí ô Phép trên thanh quân:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntNumberField(
                    label = "Bật kỹ năng Tướng sau",
                    value = home.triggerHeroAbilityAfterSec,
                    min = 0,
                    max = 45,
                    suffix = "s",
                    onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(triggerHeroAbilityAfterSec = v)) } },
                    modifier = Modifier.weight(1f),
                )
                IntNumberField(
                    label = "Thời gian trận tối đa",
                    value = home.maxBattleDurationSec,
                    min = 25,
                    max = 210,
                    suffix = "s",
                    onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(maxBattleDurationSec = v)) } },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            SettingSwitchRow(
                title = "Tự bỏ qua ô lính đã hết (nhận diện màu xám)",
                checked = home.skipEmptySlots,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(skipEmptySlots = v)) } },
            )
            SettingSwitchRow(
                title = "Đầu hàng sớm sau khi thả hết quân",
                subtitle = "Giúp tăng tốc độ farm khi không cần đợi lính đi dọn biên",
                checked = home.earlySurrenderWhenTroopsEmpty,
                onCheckedChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(earlySurrenderWhenTroopsEmpty = v)) } },
            )
            if (home.earlySurrenderWhenTroopsEmpty) {
                IntNumberField(
                    label = "Chờ thêm trước khi kết thúc sớm",
                    value = home.earlySurrenderWaitSec,
                    min = 5,
                    max = 90,
                    suffix = "s",
                    onValueChange = { v -> onUpdateConfig { it.copy(homeFarm = it.homeFarm.copy(earlySurrenderWaitSec = v)) } },
                )
            }
        }

        // 3. Builder Base Auto Farm
        SectionCard(
            title = "Auto Farm Nhà Thợ Xây (Builder Base)",
            subtitle = "Tự chuyển thuyền, thu hoạch mỏ, kích Tháp Đồng Hồ và farm liên tục",
        ) {
            SettingSwitchRow(
                title = "Kích hoạt Auto Nhà Thợ Xây (chế độ Luân phiên)",
                checked = bb.enabled,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(enabled = v)) } },
            )
            SettingSwitchRow(
                title = "Thu hoạch mỏ Vàng/Dầu Nhà Thợ Xây",
                checked = bb.collectResources,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(collectResources = v)) } },
            )
            SettingSwitchRow(
                title = "Tự kích hoạt Tháp Đồng Hồ (Clock Tower)",
                checked = bb.activateClockTower,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(activateClockTower = v)) } },
            )
            SettingSwitchRow(
                title = "Chế độ Farm nhanh (thả hết rồi đầu hàng sớm)",
                subtitle = "Rất hiệu quả để cày nhanh Elixir & Vàng Nhà Thợ Xây",
                checked = bb.fastSurrenderMode,
                onCheckedChange = { v -> onUpdateConfig { it.copy(builderBase = it.builderBase.copy(fastSurrenderMode = v)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntNumberField(
                    label = "Số trận / chu kỳ",
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

            Spacer(modifier = Modifier.height(8.dp))
            Text("Hướng thả quân Nhà Thợ Xây:", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
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

        // 4. Session Watchdog & Recovery
        SectionCard(
            title = "Giữ phiên & Tự khôi phục",
            subtitle = "Tự xử lý văng game, mất mạng và dao động thao tác tự nhiên",
        ) {
            SettingSwitchRow(
                title = "Tự mở lại Clash of Clans khi bị văng",
                checked = safety.autoRelaunchGame,
                onCheckedChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(autoRelaunchGame = v)) } },
            )
            SettingSwitchRow(
                title = "Tự bấm Tải lại (Reload) khi mất kết nối",
                checked = safety.autoReloadOnDisconnect,
                onCheckedChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(autoReloadOnDisconnect = v)) } },
            )
            SettingSwitchRow(
                title = "Giữ CPU hoạt động khi treo auto (WakeLock)",
                checked = safety.keepScreenAwake,
                onCheckedChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(keepScreenAwake = v)) } },
            )

            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IntNumberField(
                    label = "Độ lệch chạm ngẫu nhiên",
                    value = safety.humanizeJitterPx,
                    min = 0,
                    max = 25,
                    suffix = "px",
                    onValueChange = { v -> onUpdateConfig { it.copy(safety = it.safety.copy(humanizeJitterPx = v)) } },
                    modifier = Modifier.weight(1f),
                )
                IntNumberField(
                    label = "Nghỉ khi gặp Break",
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
