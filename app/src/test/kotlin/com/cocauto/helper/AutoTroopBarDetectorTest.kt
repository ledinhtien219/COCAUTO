package com.cocauto.helper

import com.cocauto.helper.model.NormRect
import com.cocauto.helper.vision.AutoTroopBarDetector
import com.cocauto.helper.vision.OcrTextLine
import com.cocauto.helper.vision.ResourceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTroopBarDetectorTest {

    @Test
    fun parseTroopCardsFromOcr_extractsAllAvailableTroopsAndCounts() {
        val lines = listOf(
            OcrTextLine("x30", NormRect(0.28f, 0.82f, 0.34f, 0.855f)),
            OcrTextLine("x25", NormRect(0.37f, 0.82f, 0.43f, 0.855f)),
            OcrTextLine("×8  x4", NormRect(0.46f, 0.82f, 0.62f, 0.855f)),
        )

        val cards = AutoTroopBarDetector.parseTroopCardsFromOcr(lines)
        assertEquals(4, cards.size)
        assertEquals(30, cards[0].count)
        assertEquals(25, cards[1].count)
        assertEquals(8, cards[2].count)
        assertEquals(4, cards[3].count)
        // Sorted strictly left-to-right and tap targets land in lower troop card body
        for (i in 1 until cards.size) {
            assertTrue(cards[i].tapPoint.x > cards[i - 1].tapPoint.x)
        }
        assertTrue(cards.all { it.tapPoint.y in 0.87f..0.95f })
    }

    @Test
    fun shieldDialogAndAttackMenuMatchers_workForVietnameseAndEnglish() {
        assertTrue(ResourceParser.isShieldWarningDialogText("Tấn công sẽ giảm thời gian Khiên của bạn!"))
        assertTrue(ResourceParser.isShieldWarningDialogText("Attacking will disable your Shield"))
        assertTrue(ResourceParser.isScoutBannerText("Chiến lợi phẩm khả dụng:"))
        assertTrue(ResourceParser.isScoutBannerText("Available Loot:"))
        assertTrue(ResourceParser.isAttackMenuText("Tìm trận 170"))
        assertTrue(ResourceParser.isAttackMenuText("Tấn công!"))
        assertFalse(ResourceParser.isAttackMenuText("Chơi đơn"))
        assertFalse(ResourceParser.isAttackMenuText("Edit Army"))
    }
}
