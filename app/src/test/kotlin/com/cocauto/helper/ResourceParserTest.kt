package com.cocauto.helper

import com.cocauto.helper.model.AutoUpgradeConfig
import com.cocauto.helper.model.BuilderStatus
import com.cocauto.helper.model.CurrencyType
import com.cocauto.helper.model.FullStorageCondition
import com.cocauto.helper.model.HomeFarmConfig
import com.cocauto.helper.model.HomeResources
import com.cocauto.helper.model.LootMatchMode
import com.cocauto.helper.model.LootReading
import com.cocauto.helper.model.NormPoint
import com.cocauto.helper.model.NormRect
import com.cocauto.helper.model.StorageFillLevels
import com.cocauto.helper.model.UpgradeCandidate
import com.cocauto.helper.model.UpgradePriorityOrder
import com.cocauto.helper.model.WallCurrencyMode
import com.cocauto.helper.vision.OcrTextLine
import com.cocauto.helper.vision.ResourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceParserTest {

    @Test
    fun parseResourceNumber_handlesCoCSpacingAndOcrTypos() {
        assertEquals(1_250_400L, ResourceParser.parseResourceNumber("1 250 400"))
        assertEquals(450_200L, ResourceParser.parseResourceNumber("450,200"))
        assertEquals(8_450L, ResourceParser.parseResourceNumber("8.450"))
        assertEquals(1_025_000L, ResourceParser.parseResourceNumber("l O25 000"))
        assertEquals(8_550L, ResourceParser.parseResourceNumber("8 5S0"))
        assertEquals(0L, ResourceParser.parseResourceNumber("0"))
        assertNull(ResourceParser.parseResourceNumber("BOSS"))
    }

    @Test
    fun parseEnemyLootLines_sortsTopToBottomAndIgnoresHeaders() {
        val lines = listOf(
            OcrTextLine("Available Loot:", NormRect(0.05f, 0.08f, 0.20f, 0.11f)),
            OcrTextLine("6 250", NormRect(0.06f, 0.23f, 0.16f, 0.26f)),
            OcrTextLine("820 500", NormRect(0.06f, 0.13f, 0.18f, 0.16f)),
            OcrTextLine("745 100", NormRect(0.06f, 0.18f, 0.18f, 0.21f)),
        )
        val loot = ResourceParser.parseEnemyLootLines(lines)
        assertEquals(820_500L, loot.gold)
        assertEquals(745_100L, loot.elixir)
        assertEquals(6_250L, loot.darkElixir)
        assertEquals(1_565_600L, loot.combinedGoldElixir)
    }

    @Test
    fun parseBuilderStatus_parsesValidAndRejectsInvalid() {
        assertEquals(BuilderStatus(2, 5), ResourceParser.parseBuilderStatus("2/5"))
        assertEquals(BuilderStatus(0, 6), ResourceParser.parseBuilderStatus("O / 6"))
        assertEquals(BuilderStatus(1, 5), ResourceParser.parseBuilderStatus("l/5"))
        assertNull(ResourceParser.parseBuilderStatus("9/9"))
    }

    @Test
    fun matchesLootFilter_supportsAllThreeModes() {
        val loot = LootReading(gold = 500_000L, elixir = 420_000L, darkElixir = 4_000L)

        val combinedCfg = HomeFarmConfig(
            lootMatchMode = LootMatchMode.COMBINED_SUM,
            minCombinedGoldElixir = 900_000L,
            minDarkElixir = 3_000L,
        )
        assertTrue(ResourceParser.matchesLootFilter(loot, combinedCfg))

        val allCfg = HomeFarmConfig(
            lootMatchMode = LootMatchMode.ALL_MATCH,
            minGold = 500_000L,
            minElixir = 450_000L,
            minDarkElixir = 2_000L,
        )
        assertFalse(ResourceParser.matchesLootFilter(loot, allCfg))

        val anyCfg = allCfg.copy(lootMatchMode = LootMatchMode.ANY_MATCH)
        assertTrue(ResourceParser.matchesLootFilter(loot, anyCfg))
    }

    @Test
    fun parseAndSelectBuildingUpgrade_respectsBlacklistReserveAndPriority() {
        val ocrLines = listOf(
            OcrTextLine("Suggested Upgrades", NormRect(0.35f, 0.12f, 0.58f, 0.15f)),
            OcrTextLine("Town Hall", NormRect(0.35f, 0.20f, 0.48f, 0.23f)),
            OcrTextLine("1 500 000", NormRect(0.52f, 0.20f, 0.62f, 0.23f)),
            OcrTextLine("Wall (x50)", NormRect(0.35f, 0.27f, 0.48f, 0.30f)),
            OcrTextLine("1 000 000", NormRect(0.52f, 0.27f, 0.62f, 0.30f)),
            OcrTextLine("Tháp Cung Thủ", NormRect(0.35f, 0.34f, 0.49f, 0.37f)),
            OcrTextLine("1 200 000", NormRect(0.52f, 0.34f, 0.62f, 0.37f)),
            OcrTextLine("X-Bow", NormRect(0.35f, 0.41f, 0.45f, 0.44f)),
            OcrTextLine("3 500 000", NormRect(0.52f, 0.41f, 0.62f, 0.44f)),
        )

        val candidates = ResourceParser.parseUpgradeCandidates(ocrLines, currencyResolver = { CurrencyType.GOLD })
        assertEquals(4, candidates.size)
        assertTrue(candidates.first { it.name.contains("Wall") }.isWall)

        val home = HomeResources(gold = 1_600_000L, elixir = 2_000_000L, darkElixir = 20_000L)
        val config = AutoUpgradeConfig(
            minKeepGold = 300_000L,
            skipBuildingKeywords = listOf("Town Hall", "Nhà Chính", "Wall"),
            upgradePriority = UpgradePriorityOrder.CHEAPEST_FIRST,
        )

        // Town Hall skipped by keyword, Wall excluded for building upgrades,
        // X-Bow exceeds gold reserve (1.6M - 3.5M < 300k), so Archer Tower (1.2M, leaves 400k >= 300k) wins!
        val chosen = ResourceParser.selectBestBuildingUpgrade(candidates, home, config)
        assertNotNull(chosen)
        assertEquals("Tháp Cung Thủ", chosen?.name)
    }

    @Test
    fun isSkippedByKeywords_matchesVietnameseWithoutDiacritics() {
        assertTrue(ResourceParser.isSkippedByKeywords("Nhà Chính", listOf("nha chinh")))
        assertTrue(ResourceParser.isSkippedByKeywords("Tường", listOf("tuong")))
        assertFalse(ResourceParser.isSkippedByKeywords("Pháo Đại Bác", listOf("Town Hall", "Tường")))
    }

    @Test
    fun wallBuilderReservationAndCurrencyChoice_workAccurately() {
        val builders = BuilderStatus(freeBuilders = 1, totalBuilders = 5)
        assertEquals(0, ResourceParser.usableBuildingBuilders(builders, reserveForWalls = 1))
        assertEquals(1, ResourceParser.usableBuildingBuilders(builders, reserveForWalls = 0))

        val cfg = AutoUpgradeConfig(
            autoUpgradeWalls = true,
            wallGoldThreshold = 4_000_000L,
            wallElixirThreshold = 4_000_000L,
            wallCurrencyMode = WallCurrencyMode.BOTH,
            minKeepGold = 500_000L,
            minKeepElixir = 500_000L,
        )
        val homeRichElixir = HomeResources(gold = 4_200_000L, elixir = 5_800_000L, darkElixir = 10_000L)
        assertEquals(
            CurrencyType.ELIXIR,
            ResourceParser.chooseWallUpgradeCurrency(homeRichElixir, builders, cfg, estimatedWallCost = 1_000_000L),
        )

        val homeBelowThreshold = HomeResources(gold = 2_000_000L, elixir = 2_000_000L, darkElixir = 0L)
        assertNull(ResourceParser.chooseWallUpgradeCurrency(homeBelowThreshold, builders, cfg))
    }

    @Test
    fun selectBestLabUpgrade_prefersDarkElixirWhenConfigured() {
        val candidates = listOf(
            UpgradeCandidate("Dragon", 2_000_000L, CurrencyType.ELIXIR, NormPoint(0.4f, 0.4f)),
            UpgradeCandidate("Hog Rider", 45_000L, CurrencyType.DARK_ELIXIR, NormPoint(0.6f, 0.4f)),
        )
        val home = HomeResources(gold = 1_000_000L, elixir = 5_000_000L, darkElixir = 80_000L)
        val cfg = AutoUpgradeConfig(
            minKeepElixir = 300_000L,
            minKeepDarkElixir = 10_000L,
            labPreferDarkElixir = true,
        )
        val selected = ResourceParser.selectBestLabUpgrade(candidates, home, cfg)
        assertEquals("Hog Rider", selected?.name)
    }

    @Test
    fun bilingualScreenStateKeywords_detectEnglishAndVietnamese() {
        assertTrue(ResourceParser.isDisconnectDialogText("Connection Lost - Reload"))
        assertTrue(ResourceParser.isDisconnectDialogText("Mất kết nối - Tải lại"))
        assertTrue(ResourceParser.isReturnHomeText("Về nhà"))
        assertTrue(ResourceParser.isReturnHomeText("Return Home"))
        assertTrue(ResourceParser.isPersonalBreakText("Thiết bị khác đang kết nối"))
        assertTrue(ResourceParser.isEndBattleText("Kết thúc trận"))
    }

    @Test
    fun isStorageFull_detectsBarFillAndOptionalCapsAcrossAllModes() {
        val home = HomeResources(gold = 350_000L, elixir = 350_000L, darkElixir = 0L)
        val fullBoth = StorageFillLevels(goldPercent = 98, elixirPercent = 96, darkElixirPercent = 0)
        val fullGoldOnly = StorageFillLevels(goldPercent = 97, elixirPercent = 60, darkElixirPercent = 0)

        val bothCfg = HomeFarmConfig(
            stopWhenStorageFull = true,
            fullStorageCondition = FullStorageCondition.GOLD_AND_ELIXIR,
            fullStorageFillPercent = 95,
        )
        assertTrue(ResourceParser.isStorageFull(home, fullBoth, bothCfg))
        assertFalse(ResourceParser.isStorageFull(home, fullGoldOnly, bothCfg))

        val orCfg = bothCfg.copy(fullStorageCondition = FullStorageCondition.GOLD_OR_ELIXIR)
        assertTrue(ResourceParser.isStorageFull(home, fullGoldOnly, orCfg))

        val numericCapCfg = bothCfg.copy(
            fullStorageGoldCap = 350_000L,
            fullStorageElixirCap = 350_000L,
        )
        assertTrue(ResourceParser.isStorageFull(home, StorageFillLevels(10, 10, 0), numericCapCfg))

        val disabledCfg = bothCfg.copy(stopWhenStorageFull = false)
        assertFalse(ResourceParser.isStorageFull(home, fullBoth, disabledCfg))
    }

    @Test
    fun parseStrictBuilderStatus_picksLeftmostBuilderAndIgnoresLab() {
        val mergedLine = listOf(
            OcrTextLine("0/2 1/1", NormRect(0.85f, 0.74f, 0.95f, 0.79f)),
        )
        assertEquals(BuilderStatus(0, 2), ResourceParser.parseStrictBuilderStatus(mergedLine))

        val separateLines = listOf(
            OcrTextLine("1/1", NormRect(0.91f, 0.74f, 0.95f, 0.79f)),
            OcrTextLine("0/2", NormRect(0.86f, 0.74f, 0.90f, 0.79f)),
        )
        assertEquals(BuilderStatus(0, 2), ResourceParser.parseStrictBuilderStatus(separateLines))
    }

    @Test
    fun selectBestBuildingUpgrade_prioritizesNewBuildingsWhenConfigured() {
        val lines = listOf(
            OcrTextLine("Xây mới", NormRect(0.50f, 0.16f, 0.65f, 0.19f)),
            OcrTextLine("Súng Cối", NormRect(0.50f, 0.22f, 0.64f, 0.25f)),
            OcrTextLine("25 000", NormRect(0.75f, 0.22f, 0.85f, 0.25f)),
            OcrTextLine("Đề xuất nâng cấp", NormRect(0.50f, 0.30f, 0.70f, 0.33f)),
            OcrTextLine("Pháo Đại Bác", NormRect(0.50f, 0.36f, 0.68f, 0.39f)),
            OcrTextLine("10 000", NormRect(0.75f, 0.36f, 0.85f, 0.39f)),
        )
        val candidates = ResourceParser.parseUpgradeCandidates(lines, currencyResolver = { CurrencyType.GOLD })
        assertTrue(candidates.first { it.name == "Súng Cối" }.isNewBuilding)
        assertFalse(candidates.first { it.name == "Pháo Đại Bác" }.isNewBuilding)

        val home = HomeResources(gold = 350_000L, elixir = 350_000L, darkElixir = 0L)
        val cfg = AutoUpgradeConfig(
            minKeepGold = 10_000L,
            autoBuildNewBuildings = true,
            prioritizeNewBuildingsFirst = true,
            autoUpgradeBuildings = true,
            upgradePriority = UpgradePriorityOrder.CHEAPEST_FIRST,
        )
        val selected = ResourceParser.selectBestBuildingUpgrade(candidates, home, cfg)
        assertEquals("Súng Cối", selected?.name)
        assertTrue(selected?.isNewBuilding == true)
    }

    @Test
    fun parseUpgradeDurationSeconds_handlesVietnameseAndEnglishFormats() {
        assertEquals(0L, ResourceParser.parseUpgradeDurationSeconds("Tức thì"))
        assertEquals(0L, ResourceParser.parseUpgradeDurationSeconds("Instant"))
        assertEquals(900L, ResourceParser.parseUpgradeDurationSeconds("15ph"))
        assertEquals(3_600L, ResourceParser.parseUpgradeDurationSeconds("1giờ"))
        assertEquals(14_400L, ResourceParser.parseUpgradeDurationSeconds("4giờ"))
        assertEquals(868L, ResourceParser.parseUpgradeDurationSeconds("14ph 28gi"))
        assertEquals(10_440L, ResourceParser.parseUpgradeDurationSeconds("2giờ 54ph"))
        assertEquals(129_600L, ResourceParser.parseUpgradeDurationSeconds("1ng 12giờ"))
        assertEquals(108_000L, ResourceParser.parseUpgradeDurationSeconds("1d 6h"))
        assertEquals(5_400L, ResourceParser.parseUpgradeDurationSeconds("1h 30m"))
        assertNull(ResourceParser.parseUpgradeDurationSeconds("Cấp 5"))
        assertNull(ResourceParser.parseUpgradeDurationSeconds("200 000"))
    }

    @Test
    fun parse2026Sidebar_skipsInProgressAndGemsAndPicksShortestAffordableThenWallsLast() {
        // Mirrors the exact 2026 CoC "Công trình và Tường" right-hand sidebar layout
        val sidebarLines = listOf(
            // Top in-progress rows (NO cost row below -> must be skipped!)
            OcrTextLine("Tháp Cung thủ", NormRect(0.70f, 0.105f, 0.82f, 0.130f)),
            OcrTextLine("14ph 28gi", NormRect(0.87f, 0.105f, 0.94f, 0.130f)),
            OcrTextLine("Súng cối", NormRect(0.70f, 0.165f, 0.80f, 0.190f)),
            OcrTextLine("2giờ 54ph", NormRect(0.87f, 0.165f, 0.94f, 0.190f)),
            // Divider
            OcrTextLine("Đã lọc", NormRect(0.79f, 0.290f, 0.86f, 0.312f)),
            // Card 1: 4giờ, 200 000 Gold (RED unaffordable) + description prose
            OcrTextLine("Tháp Thổi khí", NormRect(0.73f, 0.340f, 0.83f, 0.362f)),
            OcrTextLine("4giờ", NormRect(0.90f, 0.340f, 0.94f, 0.362f)),
            OcrTextLine("Mới", NormRect(0.73f, 0.376f, 0.77f, 0.398f)),
            OcrTextLine("200 000", NormRect(0.87f, 0.376f, 0.94f, 0.398f)),
            OcrTextLine("Thổi bay những kẻ tấn công trên không một cách thô bạo.", NormRect(0.70f, 0.415f, 0.94f, 0.438f)),
            // Card 2: 1giờ, 100 000 Gold (affordable)
            OcrTextLine("Tháp Pháp sư", NormRect(0.73f, 0.495f, 0.83f, 0.517f)),
            OcrTextLine("1giờ", NormRect(0.90f, 0.495f, 0.94f, 0.517f)),
            OcrTextLine("Mới", NormRect(0.73f, 0.531f, 0.77f, 0.553f)),
            OcrTextLine("100 000", NormRect(0.87f, 0.531f, 0.94f, 0.553f)),
            // Card 3: 30ph, 45 000 Gold (SHORTER & affordable -> MUST WIN FIRST!)
            OcrTextLine("Pháo Đại Bác", NormRect(0.73f, 0.590f, 0.83f, 0.612f)),
            OcrTextLine("30ph", NormRect(0.90f, 0.590f, 0.94f, 0.612f)),
            OcrTextLine("Cấp 5", NormRect(0.73f, 0.626f, 0.78f, 0.648f)),
            OcrTextLine("45 000", NormRect(0.87f, 0.626f, 0.94f, 0.648f)),
            // Card 4: Builder's Hut costing 1 000 GEMS -> MUST NEVER BE CHOSEN!
            OcrTextLine("Lều Thợ xây", NormRect(0.73f, 0.685f, 0.83f, 0.707f)),
            OcrTextLine("Mới", NormRect(0.73f, 0.721f, 0.77f, 0.743f)),
            OcrTextLine("1 000", NormRect(0.89f, 0.721f, 0.94f, 0.743f)),
            // Card 5: Wall costing 30 000 Gold -> excluded from buildings, selected LAST by selectBestWallCandidate!
            OcrTextLine("Tường (x50)", NormRect(0.73f, 0.780f, 0.83f, 0.802f)),
            OcrTextLine("Cấp 5", NormRect(0.73f, 0.816f, 0.78f, 0.838f)),
            OcrTextLine("30 000", NormRect(0.88f, 0.816f, 0.94f, 0.838f)),
        )

        val candidates = ResourceParser.parseUpgradeCandidates(
            lines = sidebarLines,
            currencyResolver = { line ->
                if (line.text.trim() == "1 000") CurrencyType.GEMS else CurrencyType.GOLD
            },
            redCostResolver = { line ->
                line.text.trim() == "200 000" || line.text.trim() == "1 000"
            },
        )

        // In-progress rows & prose description must NOT become candidates
        assertEquals(5, candidates.size)
        assertFalse(candidates.any { it.name.contains("Tháp Cung thủ") })
        assertFalse(candidates.any { it.name.contains("Súng cối") })
        assertFalse(candidates.any { it.name.contains("Thổi bay") })

        val home = HomeResources(gold = 155_886L, elixir = 692_607L, darkElixir = 0L)
        val cfg = AutoUpgradeConfig() // Defaults: SHORTEST_TIME_FIRST, minKeep=10k, wallThreshold=50k

        // 1st pick: shortest-time affordable building ("Pháo Đại Bác", 30ph)
        val firstPick = ResourceParser.selectBestBuildingUpgrade(candidates, home, cfg)
        assertNotNull(firstPick)
        assertEquals("Pháo Đại Bác", firstPick?.name)
        assertEquals(1_800L, firstPick?.durationSeconds)

        // 2nd pick (after Pháo Đại Bác is upgraded, remaining Gold = 110_886): next shortest ("Tháp Pháp sư", 1giờ, 100k)
        val remainingCandidates = candidates.filterNot { it.name == "Pháo Đại Bác" }
        val secondHome = HomeResources(gold = 110_886L, elixir = 692_607L, darkElixir = 0L)
        val secondPick = ResourceParser.selectBestBuildingUpgrade(remainingCandidates, secondHome, cfg)
        assertNotNull(secondPick)
        assertEquals("Tháp Pháp sư", secondPick?.name)
        assertTrue(secondPick?.isNewBuilding == true)

        // 3rd pick (only 60_000 Gold left -> no affordable non-wall buildings remain, Gems rejected -> null!)
        val lowGoldHome = HomeResources(gold = 60_000L, elixir = 692_607L, darkElixir = 0L)
        val thirdBuildingPick = ResourceParser.selectBestBuildingUpgrade(remainingCandidates, lowGoldHome, cfg)
        assertNull(thirdBuildingPick)

        // LAST: with 60_000 Gold and 1 free builder, Walls (30_000 Gold) ARE upgraded!
        val wallPick = ResourceParser.selectBestWallCandidate(
            candidates = remainingCandidates,
            home = lowGoldHome,
            builderStatus = BuilderStatus(freeBuilders = 1, totalBuilders = 3),
            config = cfg,
        )
        assertNotNull(wallPick)
        assertEquals("Tường (x50)", wallPick?.first?.name)
        assertEquals(CurrencyType.GOLD, wallPick?.second)
    }

    @Test
    fun parseArmyCampCapacity_detectsCampAndIgnoresBuilderBadge() {
        val armyScreen = listOf(
            OcrTextLine("Quân đội của tôi", NormRect(0.10f, 0.08f, 0.30f, 0.12f)),
            OcrTextLine("115/115", NormRect(0.12f, 0.16f, 0.22f, 0.20f)),
        )
        assertEquals(115 to 115, ResourceParser.parseArmyCampCapacity(armyScreen))

        val emptyArmyScreen = listOf(
            OcrTextLine("0/135", NormRect(0.12f, 0.16f, 0.22f, 0.20f)),
        )
        assertEquals(0 to 135, ResourceParser.parseArmyCampCapacity(emptyArmyScreen))

        val homeHudOnly = listOf(
            OcrTextLine("0/3", NormRect(0.85f, 0.74f, 0.90f, 0.78f)),
            OcrTextLine("1/1", NormRect(0.91f, 0.74f, 0.95f, 0.78f)),
        )
        assertNull(ResourceParser.parseArmyCampCapacity(homeHudOnly))
    }

    @Test
    fun parseBattleSummaryLoot_extractsActualEarnedGoldElixirAndIgnoresBonus() {
        val summaryLines = listOf(
            OcrTextLine("Chiến thắng", NormRect(0.38f, 0.14f, 0.62f, 0.22f)),
            OcrTextLine("Bạn nhận được:", NormRect(0.40f, 0.32f, 0.60f, 0.36f)),
            OcrTextLine("181 240", NormRect(0.42f, 0.40f, 0.56f, 0.45f)),
            OcrTextLine("165 400", NormRect(0.42f, 0.48f, 0.56f, 0.53f)),
            OcrTextLine("1 250", NormRect(0.44f, 0.56f, 0.54f, 0.60f)),
            OcrTextLine("+28", NormRect(0.46f, 0.63f, 0.52f, 0.67f)),
            OcrTextLine("100% Thưởng", NormRect(0.40f, 0.69f, 0.60f, 0.73f)),
            OcrTextLine("Về làng", NormRect(0.42f, 0.84f, 0.58f, 0.90f)),
        )
        val earned = ResourceParser.parseBattleSummaryLoot(summaryLines)
        assertEquals(181_240L, earned.gold)
        assertEquals(165_400L, earned.elixir)
        assertEquals(1_250L, earned.darkElixir)
    }
}
