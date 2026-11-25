package com.cocauto.helper.bridge

import android.graphics.Bitmap
import com.cocauto.helper.model.ControlBackendMode

data class TouchStep(
    val xPx: Int,
    val yPx: Int,
    val holdMs: Long = 50L,
    val pauseAfterMs: Long = 35L,
) {
    fun encode(): String = "$xPx,$yPx,$holdMs,$pauseAfterMs"
}

/**
 * Unified hardware/automation bridge abstraction allowing CoC Auto to run completely
 * without AccessibilityService (via Shizuku ADB, Local ADB TCP, or PC ADB Bridge)
 * while retaining optional legacy Accessibility fallback.
 */
interface DeviceBridge {
    val mode: ControlBackendMode
    suspend fun isConnected(): Boolean
    suspend fun testConnection(): Result<String>
    suspend fun captureScreen(): Bitmap?
    fun lastCapturedPhysicalSize(): Pair<Int, Int>? = null
    suspend fun tap(xPx: Int, yPx: Int, holdMs: Long = 55L): Boolean
    suspend fun executeTouchBatch(steps: List<TouchStep>): Boolean {
        var allOk = true
        for (step in steps) {
            if (!tap(step.xPx, step.yPx, step.holdMs)) allOk = false
            if (step.pauseAfterMs > 0L) kotlinx.coroutines.delay(step.pauseAfterMs)
        }
        return allOk
    }
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 360L): Boolean
    suspend fun pinchZoomOut(width: Int, height: Int): Boolean
    suspend fun getScreenSize(): Pair<Int, Int>
    suspend fun getForegroundPackage(): String
    suspend fun launchPackage(packageName: String): Boolean
    fun disconnect()
}

/**
 * Pure Kotlin parsers for Android ADB shell outputs (`dumpsys`, `wm size`).
 */
object AdbShellOutputParser {
    private val currentFocusRegex = Regex("""mCurrentFocus=Window\{[^}]*\s([A-Za-z0-9_.]+)/[^}\s]+""")
    private val focusedAppRegex = Regex("""mFocusedApp=ActivityRecord\{[^}]*\s([A-Za-z0-9_.]+)/[^}\s]+""")
    private val resumedActivityRegex = Regex("""(?:topResumedActivity|mResumedActivity)[^\n]*?\s([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+""")
    private val overrideSizeRegex = Regex("""Override size:\s*(\d+)x(\d+)""")
    private val physicalSizeRegex = Regex("""Physical size:\s*(\d+)x(\d+)""")
    private val orientationRegex = Regex("""(?:SurfaceOrientation|mCurrentRotation|mRotation)[^0-9]*([0-3])""")

    fun parseForegroundPackage(dumpsysOutput: String): String {
        if (dumpsysOutput.isBlank()) return ""
        val patterns = listOf(currentFocusRegex, resumedActivityRegex, focusedAppRegex)
        for (regex in patterns) {
            val match = regex.find(dumpsysOutput)
            val pkg = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (pkg.isNotBlank() && pkg != "null") {
                return pkg
            }
        }
        return ""
    }

    fun parseScreenSize(wmSizeOutput: String, displayInfoOutput: String = ""): Pair<Int, Int>? {
        val sizeMatch = overrideSizeRegex.find(wmSizeOutput) ?: physicalSizeRegex.find(wmSizeOutput) ?: return null
        val rawW = sizeMatch.groupValues[1].toIntOrNull() ?: return null
        val rawH = sizeMatch.groupValues[2].toIntOrNull() ?: return null
        if (rawW < 100 || rawH < 100) return null

        val rot = orientationRegex.find(displayInfoOutput)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val isLandscapeRot = rot == 1 || rot == 3
        return if (isLandscapeRot && rawW < rawH) {
            rawH to rawW
        } else if (!isLandscapeRot && rawW > rawH && displayInfoOutput.isNotBlank()) {
            rawH to rawW
        } else {
            rawW to rawH
        }
    }
}
