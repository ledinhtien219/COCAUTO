package com.cocauto.helper.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.ControlBackendMode
import com.cocauto.helper.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher

/**
 * Pure Kotlin Android ADB-over-TCP client (connects directly to `adb tcpip 5555` on 127.0.0.1:5555
 * or any LAN TCP ADB daemon) using genuine AOSP RSA-2048 AUTH + binary `exec:screencap -p`.
 * Requires ZERO AccessibilityService, ZERO MediaProjection, and ZERO extra apps after 1-time `adb tcpip 5555`!
 */
class AdbTcpBridge(
    private val context: Context,
    private val hostProvider: () -> String,
    private val portProvider: () -> Int,
) : DeviceBridge {
    override val mode: ControlBackendMode = ControlBackendMode.LOCAL_ADB_TCP

    private val mutex = Mutex()
    private val nextLocalId = AtomicInteger(1)
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var maxPayload: Int = 256 * 1024
    private var connectedHostPort: String = ""

    private val keyPair: KeyPair by lazy { loadOrGenerateRsaKeyPair(context.applicationContext) }

    override suspend fun isConnected(): Boolean = mutex.withLock {
        val target = "${hostProvider().trim()}:${portProvider()}"
        socket?.isConnected == true && socket?.isClosed == false && connectedHostPort == target
    }

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val uid = execShell("id -u").trim()
            val (w, h) = getScreenSize()
            val target = "${hostProvider().trim().ifBlank { "127.0.0.1" }}:${portProvider()}"
            "ADB TCP $target (uid=$uid, ${w}x${h})"
        }
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // `exec:screencap -p` sends raw binary PNG bytes without PTY \r\n mangling!
            val pngBytes = runStreamCommand("exec:screencap -p")
            if (pngBytes.size < 100) return@runCatching null
            BitmapFactory.decodeByteArray(
                pngBytes,
                0,
                pngBytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
        }.getOrNull()
    }

    override suspend fun tap(xPx: Int, yPx: Int, holdMs: Long): Boolean = withContext(Dispatchers.IO) {
        val cmd = if (holdMs > 75L) {
            "input touchscreen swipe $xPx $yPx $xPx $yPx ${holdMs.coerceIn(40L, 400L)}"
        } else {
            "input tap $xPx $yPx"
        }
        runCatching { execShell(cmd); true }.getOrDefault(false)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val dur = durationMs.coerceIn(80L, 2_500L)
        runCatching { execShell("input touchscreen swipe $x1 $y1 $x2 $y2 $dur"); true }.getOrDefault(false)
    }

    override suspend fun pinchZoomOut(width: Int, height: Int): Boolean = true

    override suspend fun getScreenSize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val wm = runCatching { execShell("wm size") }.getOrDefault("")
        val rot = runCatching { execShell("dumpsys input | grep SurfaceOrientation | head -n 1") }.getOrDefault("")
        AdbShellOutputParser.parseScreenSize(wm, rot) ?: (2400 to 1080)
    }

    override suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        val out = runCatching {
            execShell("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' ; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 3")
        }.getOrDefault("")
        AdbShellOutputParser.parseForegroundPackage(out)
    }

    override suspend fun launchPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            execShell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
            true
        }.getOrDefault(false)
    }

    override fun disconnect() {
        closeSocketLocked()
    }

    private suspend fun execShell(cmd: String): String {
        val bytes = runStreamCommand("shell:$cmd")
        return bytes.toString(Charsets.UTF_8)
    }

    private suspend fun runStreamCommand(serviceSpec: String): ByteArray = mutex.withLock {
        val wasReused = socket?.isConnected == true && socket?.isClosed == false
        return@withLock try {
            runStreamCommandOnceLocked(serviceSpec)
        } catch (firstError: Exception) {
            closeSocketLocked()
            if (wasReused) {
                // Socket went stale (e.g. idle timeout or Wi-Fi <-> 4G switch) -> transparent single retry
                runStreamCommandOnceLocked(serviceSpec)
            } else {
                throw firstError
            }
        }
    }

    private fun runStreamCommandOnceLocked(serviceSpec: String): ByteArray {
        ensureConnectedLocked()
        val din = input ?: error("ADB input stream closed")
        val dout = output ?: error("ADB output stream closed")

        val localId = nextLocalId.getAndIncrement()
        val openPayload = (serviceSpec + "\u0000").toByteArray(Charsets.UTF_8)
        sendPacket(dout, CMD_OPEN, localId, 0, openPayload)

        val result = ByteArrayOutputStream()
        var remoteId = 0

        try {
            while (true) {
                val pkt = readPacket(din)
                when (pkt.cmd) {
                    CMD_OKAY -> {
                        if (pkt.arg1 == localId) {
                            remoteId = pkt.arg0
                        }
                    }

                    CMD_WRTE -> {
                        if (pkt.arg1 == localId) {
                            if (remoteId == 0) remoteId = pkt.arg0
                            result.write(pkt.data)
                            sendPacket(dout, CMD_OKAY, localId, remoteId, ByteArray(0))
                        }
                    }

                    CMD_CLSE -> {
                        if (pkt.arg1 == localId || (remoteId != 0 && pkt.arg0 == remoteId)) {
                            sendPacket(dout, CMD_CLSE, localId, pkt.arg0, ByteArray(0))
                            break
                        }
                    }
                }
            }
        } catch (e: Exception) {
            closeSocketLocked()
            throw e
        }
        return result.toByteArray()
    }

    private fun ensureConnectedLocked() {
        val host = hostProvider().trim().ifBlank { "127.0.0.1" }
        val port = portProvider().coerceIn(1, 65535)
        val target = "$host:$port"

        if (socket?.isConnected == true && socket?.isClosed == false && connectedHostPort == target) {
            return
        }
        closeSocketLocked()

        val isLoopback = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"
        val sock = Socket()
        sock.connect(InetSocketAddress(host, port), if (isLoopback) 1_500 else 3_000)
        sock.soTimeout = 15_000
        sock.tcpNoDelay = true
        sock.keepAlive = true

        val din = DataInputStream(sock.getInputStream())
        val dout = DataOutputStream(sock.getOutputStream())

        // 1. Send CNXN handshake (avoid advertising shell_v2 so raw shell: stream works cleanly)
        val banner = "host::features=cmd,stat_v2\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(dout, CMD_CNXN, ADB_VERSION, 256 * 1024, banner)

        var sentSignature = false
        while (true) {
            val pkt = readPacket(din)
            when (pkt.cmd) {
                CMD_CNXN -> {
                    maxPayload = pkt.arg1.coerceAtLeast(4096)
                    socket = sock
                    input = din
                    output = dout
                    connectedHostPort = target
                    return
                }

                CMD_AUTH -> {
                    if (pkt.arg0 == AUTH_TOKEN) {
                        if (!sentSignature) {
                            sentSignature = true
                            val sig = AdbRsaCrypto.signToken(keyPair.private, pkt.data)
                            sendPacket(dout, CMD_AUTH, AUTH_SIGNATURE, 0, sig)
                        } else {
                            // Server didn't recognize our key yet -> send public key (Android prompts "Allow USB debugging")
                            val pub = AdbRsaCrypto.encodeAndroidPublicKey(keyPair.public as RSAPublicKey)
                            sendPacket(dout, CMD_AUTH, AUTH_RSAPUBLICKEY, 0, pub)
                            // Allow extra time for user to tap "Always allow" on Android dialog
                            sock.soTimeout = 25_000
                        }
                    }
                }

                else -> {
                    runCatching { sock.close() }
                    error("ADB phản hồi không hợp lệ (cmd=0x${pkt.cmd.toString(16)})")
                }
            }
        }
    }

    private fun closeSocketLocked() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        connectedHostPort = ""
    }

    private data class AdbPacket(
        val cmd: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    )

    private fun sendPacket(dout: DataOutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray) {
        var checksum = 0
        for (b in data) checksum += (b.toInt() and 0xFF)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(cmd)
            .putInt(arg0)
            .putInt(arg1)
            .putInt(data.size)
            .putInt(checksum)
            .putInt(cmd xor -1)
            .array()
        dout.write(header)
        if (data.isNotEmpty()) dout.write(data)
        dout.flush()
    }

    private fun readPacket(din: DataInputStream): AdbPacket {
        val header = ByteArray(24)
        din.readFully(header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        bb.int // checksum
        bb.int // magic
        val data = if (len > 0) ByteArray(len).also { din.readFully(it) } else ByteArray(0)
        return AdbPacket(cmd, arg0, arg1, data)
    }

    private fun loadOrGenerateRsaKeyPair(appCtx: Context): KeyPair {
        val privFile = File(appCtx.filesDir, "coc_adb_rsa.priv")
        val pubFile = File(appCtx.filesDir, "coc_adb_rsa.pub")
        if (privFile.exists() && pubFile.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                return KeyPair(pub, priv)
            }
        }
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val pair = kpg.generateKeyPair()
        runCatching {
            privFile.writeBytes(pair.private.encoded)
            pubFile.writeBytes(pair.public.encoded)
        }
        return pair
    }

    companion object {
        private const val ADB_VERSION = 0x01000000
        private const val CMD_CNXN = 0x4e584e43 // "CNXN"
        private const val CMD_AUTH = 0x48545541 // "AUTH"
        private const val CMD_OPEN = 0x4e45504f // "OPEN"
        private const val CMD_OKAY = 0x59414b4f // "OKAY"
        private const val CMD_CLSE = 0x45534c43 // "CLSE"
        private const val CMD_WRTE = 0x45545257 // "WRTE"

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
    }
}

/**
 * Pure Kotlin AOSP-compatible RSA-2048 public key encoder & raw PKCS#1 v1.5 token signer for ADB AUTH.
 */
object AdbRsaCrypto {
    private const val KEY_LENGTH_WORDS = 64 // 2048 bits / 32 bits
    private val sha1DigestPrefix = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    fun buildAndroidPublicKeyStruct(publicKey: RSAPublicKey): ByteArray {
        val n = publicKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val r = BigInteger.ONE.shiftLeft(2048)
        val rr = r.multiply(r).mod(n)
        val n0 = n.mod(r32)
        val n0inv = r32.subtract(n0.modInverse(r32)).mod(r32).toLong().toInt()

        val bb = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(KEY_LENGTH_WORDS)
        bb.putInt(n0inv)

        var curN = n
        for (i in 0 until KEY_LENGTH_WORDS) {
            bb.putInt(curN.mod(r32).toLong().toInt())
            curN = curN.shiftRight(32)
        }
        var curRR = rr
        for (i in 0 until KEY_LENGTH_WORDS) {
            bb.putInt(curRR.mod(r32).toLong().toInt())
            curRR = curRR.shiftRight(32)
        }
        bb.putInt(publicKey.publicExponent.toInt())
        return bb.array()
    }

    fun encodeAndroidPublicKey(publicKey: RSAPublicKey): ByteArray {
        val struct = buildAndroidPublicKeyStruct(publicKey)
        val b64 = Base64.encodeToString(struct, Base64.NO_WRAP)
        return "$b64 cocauto@android\u0000".toByteArray(Charsets.UTF_8)
    }

    fun signToken(privateKey: java.security.PrivateKey, token: ByteArray): ByteArray {
        // ADB sends a 20-byte random token which adbd verifies as raw SHA-1 PKCS#1 v1.5 signature
        val keySizeBytes = 256
        val payloadLen = sha1DigestPrefix.size + token.size
        val padLen = keySizeBytes - 3 - payloadLen
        val padded = ByteArray(keySizeBytes)
        padded[0] = 0x00
        padded[1] = 0x01
        for (i in 0 until padLen) padded[2 + i] = 0xFF.toByte()
        padded[2 + padLen] = 0x00
        System.arraycopy(sha1DigestPrefix, 0, padded, 3 + padLen, sha1DigestPrefix.size)
        System.arraycopy(token, 0, padded, 3 + padLen + sha1DigestPrefix.size, token.size)

        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(padded)
    }
}
