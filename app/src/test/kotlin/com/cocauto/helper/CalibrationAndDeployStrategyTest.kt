package com.cocauto.helper

import com.cocauto.helper.data.ExportBundle
import com.cocauto.helper.engine.DeployStrategyEngine
import com.cocauto.helper.model.AspectPreset
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.BotRunMode
import com.cocauto.helper.model.CalibrationProfile
import com.cocauto.helper.model.DeployEdge
import com.cocauto.helper.model.DeployStrategyType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationAndDeployStrategyTest {

    @Test
    fun calibrationPresets_matchPhoneAspectRatios() {
        assertEquals(AspectPreset.RATIO_16_9, CalibrationProfile.forScreenDimensions(1920, 1080).preset)
        assertEquals(AspectPreset.RATIO_19_5_9, CalibrationProfile.forScreenDimensions(2340, 1080).preset)
        assertEquals(AspectPreset.RATIO_20_9, CalibrationProfile.forScreenDimensions(2400, 1080).preset)
    }

    @Test
    fun troopSlotPoints_areMonotonicallyIncreasingAndWithinScreen() {
        val profile = CalibrationProfile.forPreset(AspectPreset.RATIO_19_5_9)
        val xs = (1..10).map { profile.troopSlotPoint(it, totalVisibleSlots = 10).x }
        for (i in 1 until xs.size) {
            assertTrue("Expected monotonic troop bar x coordinates", xs[i] > xs[i - 1])
            assertTrue(xs[i] in 0.01f..0.99f)
        }
    }

    @Test
    fun deployStrategies_produceExpectedGeometry() {
        val profile = CalibrationProfile.forPreset(AspectPreset.RATIO_19_5_9)

        val fourSides = DeployStrategyEngine.planDropPoints(
            strategy = DeployStrategyType.FOUR_SIDES_SURROUND,
            calibration = profile,
            focusedEdges = emptySet(),
            pointsPerEdge = 5,
        )
        assertEquals(20, fourSides.size)
        assertTrue(fourSides.all { it.x in 0.05f..0.95f && it.y in 0.05f..0.90f })

        val focused = DeployStrategyEngine.planDropPoints(
            strategy = DeployStrategyType.FOCUSED_DIRECTION,
            calibration = profile,
            focusedEdges = setOf(DeployEdge.NW),
            pointsPerEdge = 5,
        )
        assertEquals(7, focused.size)
        // NW points must lie in the top-left quadrant
        assertTrue(focused.all { it.x <= 0.51f && it.y <= 0.50f })
    }

    @Test
    fun exportBundle_roundTripsThroughJsonCleanly() {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
        val original = ExportBundle(
            config = BotConfig(runMode = BotRunMode.ALTERNATE_BOTH),
            calibration = CalibrationProfile.forPreset(AspectPreset.RATIO_20_9),
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ExportBundle>(encoded)
        assertEquals(original, decoded)
    }
}
