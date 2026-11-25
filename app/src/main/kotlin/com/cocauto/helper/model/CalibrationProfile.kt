package com.cocauto.helper.model

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
enum class AspectPreset(val label: String) {
    AUTO("Tự động theo máy"),
    RATIO_16_9("16:9 (Chuẩn)"),
    RATIO_19_5_9("19.5:9 (Phổ biến)"),
    RATIO_20_9("20:9 (Màn dài)"),
}

@Serializable
data class NormPoint(
    val x: Float,
    val y: Float,
) {
    fun toPixel(screenWidth: Int, screenHeight: Int): Pair<Float, Float> {
        val clampedX = x.coerceIn(0.005f, 0.995f)
        val clampedY = y.coerceIn(0.005f, 0.995f)
        return Pair(clampedX * screenWidth, clampedY * screenHeight)
    }
}

@Serializable
data class NormRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val width: Float get() = (right - left).coerceAtLeast(0.01f)
    val height: Float get() = (bottom - top).coerceAtLeast(0.01f)

    fun centerPoint(): NormPoint = NormPoint(centerX, centerY)

    fun normalized(): NormRect {
        val l = minOf(left, right).coerceIn(0f, 0.98f)
        val r = maxOf(left, right).coerceIn(l + 0.01f, 1f)
        val t = minOf(top, bottom).coerceIn(0f, 0.98f)
        val b = maxOf(top, bottom).coerceIn(t + 0.01f, 1f)
        return NormRect(l, t, r, b)
    }
}

/**
 * Normalized landscape screen coordinates (0f..1f) for Clash of Clans UI anchors & OCR ROIs.
 * Users can adjust every box and anchor visually over the live game via Calibration Overlay.
 */
@Serializable
data class CalibrationProfile(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val preset: AspectPreset = AspectPreset.RATIO_19_5_9,
    // Home Village buttons & HUD ROIs (aligned to Clash of Clans 2025/2026 UI layout)
    val homeAttackButton: NormPoint = NormPoint(0.098f, 0.870f),
    val homeAttackLabelRoi: NormRect = NormRect(0.045f, 0.905f, 0.155f, 0.990f),
    val findMatchButton: NormPoint = NormPoint(0.215f, 0.740f),
    val armyConfirmAttackButton: NormPoint = NormPoint(0.826f, 0.900f),
    val builderBadgeButton: NormPoint = NormPoint(0.885f, 0.765f),
    val builderBadgeRoi: NormRect = NormRect(0.845f, 0.715f, 0.920f, 0.805f),
    val builderSuggestionsRoi: NormRect = NormRect(0.450f, 0.140f, 0.945f, 0.720f),
    val upgradeActionButton: NormPoint = NormPoint(0.560f, 0.840f),
    val upgradeWallElixirButton: NormPoint = NormPoint(0.640f, 0.840f),
    val upgradeConfirmButton: NormPoint = NormPoint(0.500f, 0.785f),
    val homeGoldRoi: NormRect = NormRect(0.765f, 0.025f, 0.935f, 0.082f),
    val homeElixirRoi: NormRect = NormRect(0.765f, 0.098f, 0.935f, 0.155f),
    val homeDarkElixirRoi: NormRect = NormRect(0.800f, 0.172f, 0.935f, 0.225f),
    val lootCartPoint: NormPoint = NormPoint(0.180f, 0.250f),
    val boatToBuilderBase: NormPoint = NormPoint(0.240f, 0.820f),
    val labShortcutPoint: NormPoint = NormPoint(0.935f, 0.765f),
    val labMenuRoi: NormRect = NormRect(0.180f, 0.220f, 0.820f, 0.780f),
    val labStartButton: NormPoint = NormPoint(0.700f, 0.790f),
    val dismissAreaPoint: NormPoint = NormPoint(0.030f, 0.450f),

    // Battle & Scouting ROIs & buttons
    val enemyLootRoi: NormRect = NormRect(0.045f, 0.105f, 0.215f, 0.300f),
    val nextBaseButton: NormPoint = NormPoint(0.885f, 0.715f),
    val nextButtonRoi: NormRect = NormRect(0.805f, 0.635f, 0.965f, 0.800f),
    val endBattleButton: NormPoint = NormPoint(0.103f, 0.745f),
    val endBattleRoi: NormRect = NormRect(0.035f, 0.690f, 0.175f, 0.805f),
    val surrenderConfirmButton: NormPoint = NormPoint(0.585f, 0.635f),
    val returnHomeButton: NormPoint = NormPoint(0.500f, 0.870f),
    val returnHomeRoi: NormRect = NormRect(0.390f, 0.810f, 0.610f, 0.935f),
    val battleCenterRoi: NormRect = NormRect(0.250f, 0.220f, 0.750f, 0.680f),
    val dialogCenterRoi: NormRect = NormRect(0.250f, 0.250f, 0.750f, 0.750f),

    // Troop bar (1-based slots 1..12 mapped across this rect)
    val troopBarRoi: NormRect = NormRect(0.085f, 0.840f, 0.915f, 0.975f),

    // Deploy diamond corners (after zoom-out) anchored safely on outer green grass outside the red zone
    val deployTop: NormPoint = NormPoint(0.500f, 0.085f),
    val deployRight: NormPoint = NormPoint(0.885f, 0.465f),
    val deployBottom: NormPoint = NormPoint(0.500f, 0.775f),
    val deployLeft: NormPoint = NormPoint(0.115f, 0.465f),

    // Builder Base buttons
    val bbAttackButton: NormPoint = NormPoint(0.075f, 0.875f),
    val bbFindNowButton: NormPoint = NormPoint(0.720f, 0.680f),
    val bbBoatReturnHome: NormPoint = NormPoint(0.760f, 0.210f),
    val bbClockTowerPoint: NormPoint = NormPoint(0.350f, 0.280f),
    val bbClockBoostButton: NormPoint = NormPoint(0.500f, 0.830f),
) {
    /**
     * Returns normalized center point for troop slot [slotIndex] (1-based, 1..maxSlots).
     */
    fun troopSlotPoint(slotIndex: Int, totalVisibleSlots: Int = 11): NormPoint {
        val slots = totalVisibleSlots.coerceIn(4, 14)
        val idx = (slotIndex - 1).coerceIn(0, slots - 1)
        val slotWidth = troopBarRoi.width / slots
        val cx = troopBarRoi.left + slotWidth * (idx + 0.5f)
        val cy = troopBarRoi.centerY
        return NormPoint(cx, cy)
    }

    /**
     * Returns a small ROI covering troop slot [slotIndex] for saturation/availability analysis.
     */
    fun troopSlotRect(slotIndex: Int, totalVisibleSlots: Int = 11): NormRect {
        val slots = totalVisibleSlots.coerceIn(4, 14)
        val idx = (slotIndex - 1).coerceIn(0, slots - 1)
        val slotWidth = troopBarRoi.width / slots
        val marginX = slotWidth * 0.18f
        val marginY = troopBarRoi.height * 0.18f
        return NormRect(
            left = troopBarRoi.left + slotWidth * idx + marginX,
            top = troopBarRoi.top + marginY,
            right = troopBarRoi.left + slotWidth * (idx + 1) - marginX,
            bottom = troopBarRoi.bottom - marginY,
        ).normalized()
    }

    /**
     * Generates [count] evenly spaced deployment points along the requested perimeter [edge].
     */
    fun edgePoints(edge: DeployEdge, count: Int, insetTowardCenter: Float = 0f): List<NormPoint> {
        val n = count.coerceAtLeast(1)
        val (start, end) = when (edge) {
            DeployEdge.NW -> deployLeft to deployTop
            DeployEdge.NE -> deployTop to deployRight
            DeployEdge.SE -> deployRight to deployBottom
            DeployEdge.SW -> deployBottom to deployLeft
        }
        val centerX = (deployLeft.x + deployRight.x) * 0.5f
        val centerY = (deployTop.y + deployBottom.y) * 0.5f
        val inset = insetTowardCenter.coerceIn(0f, 0.5f)

        return List(n) { i ->
            val t = if (n == 1) 0.5f else 0.08f + 0.84f * (i.toFloat() / (n - 1).toFloat())
            val rawX = start.x + (end.x - start.x) * t
            val rawY = start.y + (end.y - start.y) * t
            NormPoint(
                x = rawX + (centerX - rawX) * inset,
                y = rawY + (centerY - rawY) * inset,
            )
        }
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION = 5

        fun forScreenDimensions(width: Int, height: Int): CalibrationProfile {
            val landscapeW = maxOf(width, height).coerceAtLeast(1)
            val landscapeH = minOf(width, height).coerceAtLeast(1)
            val ratio = landscapeW.toFloat() / landscapeH.toFloat()
            val preset = when {
                abs(ratio - 16f / 9f) <= 0.14f -> AspectPreset.RATIO_16_9
                ratio >= 2.18f -> AspectPreset.RATIO_20_9
                else -> AspectPreset.RATIO_19_5_9
            }
            return forPreset(preset)
        }

        fun forPreset(preset: AspectPreset): CalibrationProfile = when (preset) {
            AspectPreset.RATIO_16_9 -> CalibrationProfile(
                preset = AspectPreset.RATIO_16_9,
                homeAttackButton = NormPoint(0.080f, 0.875f),
                homeGoldRoi = NormRect(0.775f, 0.022f, 0.960f, 0.082f),
                homeElixirRoi = NormRect(0.775f, 0.098f, 0.960f, 0.158f),
                homeDarkElixirRoi = NormRect(0.805f, 0.172f, 0.960f, 0.230f),
                enemyLootRoi = NormRect(0.045f, 0.110f, 0.235f, 0.310f),
                nextBaseButton = NormPoint(0.905f, 0.770f),
                troopBarRoi = NormRect(0.050f, 0.835f, 0.950f, 0.980f),
                deployLeft = NormPoint(0.110f, 0.475f),
                deployRight = NormPoint(0.890f, 0.475f),
            )

            AspectPreset.RATIO_20_9 -> CalibrationProfile(
                preset = AspectPreset.RATIO_20_9,
                homeAttackButton = NormPoint(0.105f, 0.865f),
                homeGoldRoi = NormRect(0.770f, 0.025f, 0.930f, 0.082f),
                homeElixirRoi = NormRect(0.770f, 0.098f, 0.930f, 0.155f),
                homeDarkElixirRoi = NormRect(0.805f, 0.172f, 0.930f, 0.225f),
                enemyLootRoi = NormRect(0.055f, 0.105f, 0.205f, 0.295f),
                nextBaseButton = NormPoint(0.885f, 0.715f),
                troopBarRoi = NormRect(0.110f, 0.840f, 0.890f, 0.975f),
                deployTop = NormPoint(0.500f, 0.085f),
                deployLeft = NormPoint(0.105f, 0.465f),
                deployRight = NormPoint(0.895f, 0.465f),
                deployBottom = NormPoint(0.500f, 0.775f),
            )

            AspectPreset.RATIO_19_5_9, AspectPreset.AUTO -> CalibrationProfile(
                preset = AspectPreset.RATIO_19_5_9,
            )
        }
    }
}
