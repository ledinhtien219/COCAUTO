package com.cocauto.helper.bridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.ControlBackendMode
import com.cocauto.helper.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

@Serializable
private data class PcHealthResponse(
    val ok: Boolean = false,
    val serial: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val foregroundPackage: String = "",
)

@Serializable
private data class PcForegroundResponse(
    val packageName: String = "",
)

/**
 * HTTP client connecting to `scripts/adb-bridge.mjs` running on the user's PC (over USB `adb reverse`
 * on `http://127.0.0.1:8085` or over home Wi-Fi `http://192.168.x.x:8085`).
 * Requires ZERO AccessibilityService, ZERO MediaProjection, and ZERO extra Android apps!
 */
class PcHttpAdbBridge(
    private val baseUrlProvider: () -> String,
) : DeviceBridge {
    override val mode: ControlBackendMode = ControlBackendMode.PC_HTTP_ADB

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile
    private var lastHealthy = false

    @Volatile
    private var cachedSize: Pair<Int, Int> = 2400 to 1080

    private fun normalizedBaseUrl(): String {
        val raw = baseUrlProvider().trim().trimEnd('/')
        if (raw.isBlank()) return "http://127.0.0.1:8085"
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
    }

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        if (lastHealthy) return@withContext true
        testConnection().isSuccess
    }

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGetText("${normalizedBaseUrl()}/health")
            val health = json.decodeFromString<PcHealthResponse>(body)
            if (!health.ok) error("PC ADB Bridge chưa thấy điện thoại kết nối ADB.")
            if (health.width >= 320 && health.height >= 320) {
                cachedSize = health.width to health.height
            }
            lastHealthy = true
            val msg = "PC ADB Bridge sẵn sàng (${health.serial}, ${health.width}x${health.height})"
            BotStateStore.log(msg, LogLevel.SUCCESS)
            msg
        }.onFailure {
            lastHealthy = false
        }
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = httpGetBytes("${normalizedBaseUrl()}/screencap")
            if (bytes.size < 100) return@runCatching null
            val bmp = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            )
            if (bmp != null) {
                cachedSize = bmp.width to bmp.height
                lastHealthy = true
            }
            bmp
        }.onFailure {
            lastHealthy = false
        }.getOrNull()
    }

    override suspend fun tap(xPx: Int, yPx: Int, holdMs: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = """{"x":$xPx,"y":$yPx,"holdMs":$holdMs}"""
            httpPostJson("${normalizedBaseUrl()}/tap", payload)
            true
        }.getOrDefault(false)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = """{"x1":$x1,"y1":$y1,"x2":$x2,"y2":$y2,"durationMs":$durationMs}"""
            httpPostJson("${normalizedBaseUrl()}/swipe", payload)
            true
        }.getOrDefault(false)
    }

    override suspend fun pinchZoomOut(width: Int, height: Int): Boolean = true

    override suspend fun getScreenSize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGetText("${normalizedBaseUrl()}/health")
            val health = json.decodeFromString<PcHealthResponse>(body)
            if (health.width >= 320 && health.height >= 320) {
                cachedSize = health.width to health.height
            }
        }
        cachedSize
    }

    override suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGetText("${normalizedBaseUrl()}/foreground")
            json.decodeFromString<PcForegroundResponse>(body).packageName
        }.getOrDefault("")
    }

    override suspend fun launchPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            httpPostJson("${normalizedBaseUrl()}/launch", """{"packageName":"$packageName"}""")
            true
        }.getOrDefault(false)
    }

    override fun disconnect() {
        lastHealthy = false
    }

    private fun httpGetText(urlStr: String): String {
        return httpGetBytes(urlStr).toString(Charsets.UTF_8)
    }

    private fun httpGetBytes(urlStr: String): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 10_000
            requestMethod = "GET"
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(urlStr: String, jsonBody: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 10_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
