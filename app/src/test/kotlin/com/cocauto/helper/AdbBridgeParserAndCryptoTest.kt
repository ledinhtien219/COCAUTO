package com.cocauto.helper

import com.cocauto.helper.bridge.AdbRsaCrypto
import com.cocauto.helper.bridge.AdbShellOutputParser
import org.junit.Assert.assertEquals
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
}
