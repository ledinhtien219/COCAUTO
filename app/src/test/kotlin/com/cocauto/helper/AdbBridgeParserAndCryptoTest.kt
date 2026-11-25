package com.cocauto.helper

import com.cocauto.helper.bridge.AdbRsaCrypto
import com.cocauto.helper.bridge.AdbShellOutputParser
import com.cocauto.helper.bridge.BridgeAutoConnectPlanner
import com.cocauto.helper.model.BotConfig
import com.cocauto.helper.model.ControlBackendMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class AdbBridgeParserAndCryptoTest {

    @Test
    fun parseForegroundPackage_extractsCoCPackageAcrossAndroidVersions() {
        val android14Dump = """
            mCurrentFocus=Window{a1b2c3 u0 com.supercell.clashofclans/com.supercell.titan.GameApp}
            mFocusedApp=ActivityRecord{d4e5f6 u0 com.supercell.clashofclans/com.supercell.titan.GameApp t42}
        """.trimIndent()
        assertEquals("com.supercell.clashofclans", AdbShellOutputParser.parseForegroundPackage(android14Dump))

        val resumedDump = """
            topResumedActivity=ActivityRecord{12345 u0 com.supercell.clashofclans/com.supercell.titan.GameApp t12}
        """.trimIndent()
        assertEquals("com.supercell.clashofclans", AdbShellOutputParser.parseForegroundPackage(resumedDump))
    }

    @Test
    fun parseScreenSize_handlesLandscapeRotationSwap() {
        val wm = "Physical size: 1216x2640"
        val landscapeRot = "  SurfaceOrientation: 1"
        assertEquals(2640 to 1216, AdbShellOutputParser.parseScreenSize(wm, landscapeRot))

        val portraitRot = "  SurfaceOrientation: 0"
        assertEquals(1216 to 2640, AdbShellOutputParser.parseScreenSize(wm, portraitRot))
    }

    @Test
    fun adbRsaCrypto_producesValid524ByteAndroidPubkeyAnd256ByteSignature() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = kpg.generateKeyPair()
        val pubStruct = AdbRsaCrypto.buildAndroidPublicKeyStruct(pair.public as RSAPublicKey)
        assertEquals(524, pubStruct.size)
        val bb = ByteBuffer.wrap(pubStruct).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, bb.int) // 64 uint32 words = 2048 bits

        val fakeToken = ByteArray(20) { it.toByte() }
        val sig = AdbRsaCrypto.signToken(pair.private, fakeToken)
        assertEquals(256, sig.size)
    }

    @Test
    fun autoConnectPlanner_prioritizes4gSafeLocalTcpAndFallsBackToShizukuAndPc() {
        val defaultPlan = BridgeAutoConnectPlanner.planCandidates(ControlBackendMode.AUTO_ADB)
        assertEquals(
            listOf(
                ControlBackendMode.LOCAL_ADB_TCP,
                ControlBackendMode.SHIZUKU_ADB,
                ControlBackendMode.PC_HTTP_ADB,
            ),
            defaultPlan,
        )

        // When Shizuku was last working and still granted, keep Shizuku first and Local TCP as immediate 4G failover
        val stickyShizukuPlan = BridgeAutoConnectPlanner.planCandidates(
            mode = ControlBackendMode.AUTO_ADB,
            lastWorkingMode = ControlBackendMode.SHIZUKU_ADB,
            shizukuRunningAndGranted = true,
        )
        assertEquals(
            listOf(
                ControlBackendMode.SHIZUKU_ADB,
                ControlBackendMode.LOCAL_ADB_TCP,
                ControlBackendMode.PC_HTTP_ADB,
            ),
            stickyShizukuPlan,
        )

        // Explicit mode tries only that mode; legacy returns empty ADB list
        assertEquals(listOf(ControlBackendMode.LOCAL_ADB_TCP), BridgeAutoConnectPlanner.planCandidates(ControlBackendMode.LOCAL_ADB_TCP))
        assertTrue(BridgeAutoConnectPlanner.planCandidates(ControlBackendMode.ACCESSIBILITY_LEGACY).isEmpty())
    }

    @Test
    fun bridgeConfig_defaultsToAutoAdbAndRemainsBackwardCompatibleWithSavedJson() {
        val fresh = BotConfig()
        assertEquals(ControlBackendMode.AUTO_ADB, fresh.bridge.mode)
        assertTrue(fresh.bridge.autoConnectOnLaunch)
        assertTrue(fresh.bridge.autoReconnectOnNetworkChange)

        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val oldV200Json = """{"bridge":{"mode":"SHIZUKU_ADB","adbTcpHost":"127.0.0.1","adbTcpPort":5555}}"""
        val decoded = json.decodeFromString<BotConfig>(oldV200Json)
        assertEquals(ControlBackendMode.SHIZUKU_ADB, decoded.bridge.mode)
        assertTrue(decoded.bridge.autoConnectOnLaunch)
    }

    @Test
    fun battleSummaryAndRewardChestDetectors_matchVietnamese2026Screens() {
        assertTrue(com.cocauto.helper.vision.ResourceParser.isReturnHomeText("Về làng"))
        assertTrue(com.cocauto.helper.vision.ResourceParser.isBattleSummaryScreenText("CHIẾN THẮNG Bạn nhận được: 181 257"))
        assertTrue(com.cocauto.helper.vision.ResourceParser.isBattleSummaryScreenText("Bại trận Số binh lính đã sử dụng"))
        assertTrue(com.cocauto.helper.vision.ResourceParser.isStarBonusOrRewardPopup("Vật phẩm đã được chuyển vào Kho chứa tạm thời. Tiếp tục"))
        assertTrue(com.cocauto.helper.vision.ResourceParser.isConfirmDialogButtonText("Tiếp tục"))
    }
}
