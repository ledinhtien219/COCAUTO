package com.cocauto.helper

import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.NormRect
import com.cocauto.helper.vision.AutoCalibrationEngine
import com.cocauto.helper.vision.OcrTextLine
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoCalibrationEngineTest {

    @Test
    fun autoDetect_vietnameseHomeVillage2026_alignsAllUiElements() {
        // Simulated OCR boxes matching real Clash of Clans 2026 Vietnamese Home Village layout
        val lines = listOf(
            OcrTextLine("350 000", NormRect(0.840f, 0.036f, 0.922f, 0.068f)),
            OcrTextLine("350 000", NormRect(0.840f, 0.112f, 0.922f, 0.144f)),
            OcrTextLine("493", NormRect(0.880f, 0.184f, 0.922f, 0.214f)),
            OcrTextLine("1ng 17giờ", NormRect(0.845f, 0.246f, 0.924f, 0.272f)),
            OcrTextLine("CHIẾN ĐẤU", NormRect(0.058f, 0.935f, 0.142f, 0.975f)),
            OcrTextLine("0/2", NormRect(0.862f, 0.750f, 0.900f, 0.785f)),
            OcrTextLine("1/1", NormRect(0.908f, 0.750f, 0.942f, 0.785f)),
            OcrTextLine("Xây dựng", NormRect(0.865f, 0.935f, 0.942f, 0.975f)),
        )

        val result = AutoCalibrationEngine.applyDetectedOcr(
            base = CalibrationProfile(),
            lines = lines,
            screenWidth = 2400,
            screenHeight = 1080,
        )

        assertTrue(result.hasDetections)
        assertTrue(result.detectedItems.any { it.contains("Đánh") })
        assertTrue(result.detectedItems.any { it.contains("Vàng/Dầu") })
        assertTrue(result.detectedItems.any { it.contains("Thợ xây (0/2)") })
        assertTrue(result.detectedItems.any { it.contains("Lab (1/1)") })

        val p = result.profile
        // Attack button sits directly above "CHIẾN ĐẤU" at bottom-left
        assertTrue(p.homeAttackButton.x in 0.08f..0.13f)
        assertTrue(p.homeAttackButton.y in 0.84f..0.91f)

        // Builder badge detected at bottom-right (2026 UI), Lab shortcut right next to it
        assertTrue(p.builderBadgeButton.x in 0.85f..0.91f)
        assertTrue(p.builderBadgeButton.y in 0.73f..0.79f)
        assertTrue(p.labShortcutPoint.x > p.builderBadgeButton.x)

        // Gold & Elixir ROIs cover the detected resource bars tightly
        assertTrue(p.homeGoldRoi.top < 0.036f && p.homeGoldRoi.bottom > 0.068f)
        assertTrue(p.homeElixirRoi.top < 0.112f && p.homeElixirRoi.bottom > 0.144f)
    }

    @Test
    fun autoDetect_battleScoutScreen_locksEnemyLootAndNextButtons() {
        val lines = listOf(
            OcrTextLine("Available Loot:", NormRect(0.055f, 0.095f, 0.185f, 0.120f)),
            OcrTextLine("850 000", NormRect(0.065f, 0.135f, 0.165f, 0.165f)),
            OcrTextLine("720 000", NormRect(0.065f, 0.185f, 0.165f, 0.215f)),
            OcrTextLine("5 400", NormRect(0.065f, 0.235f, 0.135f, 0.265f)),
            OcrTextLine("Tiếp", NormRect(0.875f, 0.745f, 0.935f, 0.785f)),
            OcrTextLine("Kết thúc", NormRect(0.045f, 0.740f, 0.115f, 0.780f)),
        )

        val result = AutoCalibrationEngine.applyDetectedOcr(
            base = CalibrationProfile(),
            lines = lines,
            screenWidth = 2400,
            screenHeight = 1080,
        )

        assertTrue(result.detectedItems.any { it.contains("Tiếp") })
        assertTrue(result.detectedItems.any { it.contains("Loot địch") })
        assertTrue(result.detectedItems.any { it.contains("Kết thúc") })

        val p = result.profile
        assertTrue(p.nextBaseButton.x in 0.87f..0.94f)
        assertTrue(p.endBattleButton.x in 0.04f..0.12f)
        assertTrue(p.enemyLootRoi.top <= 0.135f && p.enemyLootRoi.bottom >= 0.265f)
    }

    @Test
    fun autoDetect_splitsMergedBuilderAndLabLineAndKeepsDeployInsideGrass() {
        val lines = listOf(
            OcrTextLine("350 000", NormRect(0.840f, 0.036f, 0.922f, 0.068f)),
            OcrTextLine("350 000", NormRect(0.840f, 0.112f, 0.922f, 0.144f)),
            // Merged single OCR line containing both builder (0/2) and lab (1/1)
            OcrTextLine("0/2 1/1", NormRect(0.858f, 0.748f, 0.942f, 0.786f)),
        )

        val result = AutoCalibrationEngine.applyDetectedOcr(
            base = CalibrationProfile(),
            lines = lines,
            screenWidth = 2640,
            screenHeight = 1216,
        )

        assertTrue(result.detectedItems.any { it.contains("Thợ xây (0/2)") })
        assertTrue(result.detectedItems.any { it.contains("Lab (1/1)") })
        val p = result.profile
        // Builder center is on the left half (0/2), Lab is on the right half (1/1)
        assertTrue(p.builderBadgeButton.x < 0.90f)
        assertTrue(p.labShortcutPoint.x > p.builderBadgeButton.x + 0.03f)
        // Deploy right stays inside green grass, avoiding right-side shop banner
        assertTrue(p.deployRight.x <= 0.845f)
    }
}
