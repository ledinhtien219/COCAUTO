package com.cocauto.helper.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cocauto.helper.BuildConfig
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Handles in-app update checking (GitHub Releases or custom JSON / local LAN server),
 * streaming APK download with live progress, optional SHA-256 verification, and
 * Android FileProvider APK install intent launching.
 */
class AppUpdater(private val context: Context) {

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    suspend fun checkForUpdates(
        rawSourceUrl: String,
        autoDownload: Boolean = false,
        silentIfUnconfigured: Boolean = false,
    ) {
        val endpoint = UpdateManifestParser.normalizeSourceUrl(rawSourceUrl)
        if (endpoint.isBlank()) {
            if (!silentIfUnconfigured) {
                _state.value = UpdateUiState.Error("Chưa nhập link nguồn cập nhật (GitHub owner/repo hoặc URL update.json).")
            }
            return
        }

        _state.value = UpdateUiState.Checking
        val result = withContext(Dispatchers.IO) {
            fetchManifest(endpoint)
        }

        result.fold(
            onSuccess = { release ->
                val hasUpdate = UpdateManifestParser.isNewerVersion(
                    remote = release,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName,
                )
                if (hasUpdate) {
                    BotStateStore.log("Phát hiện bản cập nhật mới: v${release.versionName}!", LogLevel.SUCCESS)
                    _state.value = UpdateUiState.Available(release)
                    if (autoDownload) {
                        downloadUpdate(release)
                    }
                } else {
                    _state.value = UpdateUiState.UpToDate(currentVersionName)
                    if (!silentIfUnconfigured) {
                        BotStateStore.log("Bạn đang dùng phiên bản mới nhất (v$currentVersionName).", LogLevel.INFO)
                    }
                }
            },
            onFailure = { err ->
                val msg = "Không kiểm tra được cập nhật: ${err.message ?: "Lỗi mạng"}"
                BotStateStore.log(msg, LogLevel.WARN)
                if (!silentIfUnconfigured) {
                    _state.value = UpdateUiState.Error(msg)
                } else {
                    _state.value = UpdateUiState.Idle
                }
            },
        )
    }

    suspend fun downloadUpdate(release: UpdateReleaseInfo) {
        _state.value = UpdateUiState.Downloading(release, 0, 0L, -1L)
        val downloadResult = withContext(Dispatchers.IO) {
            performApkDownload(release)
        }

        downloadResult.fold(
            onSuccess = { apkFile ->
                _state.value = UpdateUiState.ReadyToInstall(release, apkFile)
                BotStateStore.log("Đã tải xong bản v${release.versionName}, sẵn sàng cài đặt.", LogLevel.SUCCESS)
            },
            onFailure = { err ->
                val msg = "Lỗi tải bản cập nhật: ${err.message ?: "Thử lại sau"}"
                BotStateStore.log(msg, LogLevel.ERROR)
                _state.value = UpdateUiState.Error(msg)
            },
        )
    }

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${appContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Launches Android's standard Package Installer UI for the downloaded APK using FileProvider.
     */
    fun launchApkInstaller(activityContext: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            _state.value = UpdateUiState.Error("Tệp APK đã tải không tồn tại.")
            return false
        }
        return runCatching {
            val contentUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activityContext.startActivity(intent)
            true
        }.onFailure { err ->
            _state.value = UpdateUiState.Error("Không mở được trình cài đặt: ${err.message}")
        }.getOrDefault(false)
    }

    private fun fetchManifest(endpoint: String): Result<UpdateReleaseInfo> {
        return runCatching {
            val conn = openConnectionFollowingRedirects(endpoint, accept = "application/json")
            try {
                val code = conn.responseCode
                if (code == 404 && endpoint.contains("api.github.com/repos/")) {
                    error("Chưa có bản phát hành (Release) nào trên GitHub này.")
                }
                if (code !in 200..299) {
                    error("HTTP $code từ nguồn cập nhật")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                UpdateManifestParser.parseResponse(body, endpoint).getOrThrow()
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun performApkDownload(release: UpdateReleaseInfo): Result<File> {
        return runCatching {
            val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            // Clean up stale cached APKs before downloading
            updatesDir.listFiles()?.forEach { old -> runCatching { old.delete() } }

            val safeVer = release.versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "latest" }
            val tempFile = File(updatesDir, "coc-auto-$safeVer.apk.part")
            val finalFile = File(updatesDir, "coc-auto-$safeVer.apk")

            val conn = openConnectionFollowingRedirects(release.apkUrl, accept = "application/octet-stream")
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    error("HTTP $code khi tải APK")
                }
                val totalBytes = conn.contentLengthLong
                var downloaded = 0L
                var lastReportedPct = -1

                conn.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read

                            val pct = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                -1
                            }
                            if (pct != lastReportedPct || downloaded % (256 * 1024) < buffer.size) {
                                lastReportedPct = pct
                                _state.value = UpdateUiState.Downloading(
                                    release = release,
                                    progressPercent = pct.coerceAtLeast(0),
                                    downloadedBytes = downloaded,
                                    totalBytes = totalBytes,
                                )
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            if (tempFile.length() < 100_000L) {
                tempFile.delete()
                error("Tệp tải về quá nhỏ, không phải APK hợp lệ.")
            }

            if (release.sha256.isNotBlank()) {
                val actualHash = computeSha256(tempFile)
                if (!actualHash.equals(release.sha256, ignoreCase = true)) {
                    tempFile.delete()
                    error("Sai mã kiểm tra SHA-256! Đã hủy cài đặt để đảm bảo an toàn.")
                }
            }

            if (finalFile.exists()) finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }
            finalFile
        }
    }

    private fun openConnectionFollowingRedirects(urlStr: String, accept: String): HttpURLConnection {
        var currentUrl = urlStr
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "CoCAutoHelper/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", accept)
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) error("Redirect thiếu header Location")
                currentUrl = UpdateManifestParser.resolveRelativeUrl(currentUrl, location)
            } else {
                return conn
            }
        }
        error("Quá nhiều lần chuyển hướng (redirect)")
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
