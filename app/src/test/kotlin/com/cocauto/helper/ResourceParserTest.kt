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

        val candidates = ResourceParser.parseUpgradeCandidates(ocrLines) { CurrencyType.GOLD }
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
}
