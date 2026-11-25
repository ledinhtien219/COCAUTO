package com.cocauto.helper.updater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

data class UpdateReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String = "",
    val sha256: String = "",
    val mandatory: Boolean = false,
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val currentVersion: String) : UpdateUiState
    data class Available(val release: UpdateReleaseInfo) : UpdateUiState
    data class Downloading(
        val release: UpdateReleaseInfo,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState

    data class ReadyToInstall(
        val release: UpdateReleaseInfo,
        val apkFile: File,
    ) : UpdateUiState

    data class Error(val message: String) : UpdateUiState
}

@Serializable
private data class CustomUpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrl: String = "",
    val changelog: String = "",
    val sha256: String = "",
    val mandatory: Boolean = false,
)

@Serializable
private data class GitHubReleaseResponse(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/**
 * Pure Kotlin update endpoint normalizer, dual-format manifest parser (GitHub Releases + custom update.json),
 * and semantic / versionCode comparator resistant to missing-dot tag typos (e.g. "1.41" vs "1.4.1").
 */
object UpdateManifestParser {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val githubShortRepoRegex = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
    private val githubWebUrlRegex = Regex("""^https?://github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)(/.*)?$""")
    private val bodyVersionCodeRegex = Regex("""(?i)(?:versionCode|build)\s*[:=#-]?\s*(\d{1,6})""")
    private val bodySha256Regex = Regex("""(?i)sha-?256\s*[:=]\s*`?([0-9a-f]{64})`?""")

    fun normalizeSourceUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return ""

        if (githubShortRepoRegex.matches(trimmed)) {
            return "https://api.github.com/repos/$trimmed/releases/latest"
        }

        val webMatch = githubWebUrlRegex.matchEntire(trimmed)
        if (webMatch != null) {
            val owner = webMatch.groupValues[1]
            val repo = webMatch.groupValues[2].removeSuffix(".git")
            return "https://api.github.com/repos/$owner/$repo/releases/latest"
        }

        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }

        return runCatching {
            val uri = URI(withScheme)
            if (uri.path.isNullOrBlank() || uri.path == "/") {
                withScheme.trimEnd('/') + "/update.json"
            } else {
                withScheme
            }
        }.getOrDefault(withScheme)
    }

    fun parseResponse(rawJson: String, sourceUrl: String): Result<UpdateReleaseInfo> {
        return runCatching {
            val custom = runCatching { json.decodeFromString<CustomUpdateManifest>(rawJson) }.getOrNull()
            if (custom != null && custom.apkUrl.isNotBlank()) {
                val resolvedApkUrl = resolveRelativeUrl(sourceUrl, custom.apkUrl)
                return@runCatching UpdateReleaseInfo(
                    versionCode = custom.versionCode,
                    versionName = custom.versionName.trim().removePrefix("v").removePrefix("V"),
                    apkUrl = resolvedApkUrl,
                    changelog = custom.changelog.trim(),
                    sha256 = custom.sha256.trim().lowercase(),
                    mandatory = custom.mandatory,
                )
            }

            val gh = json.decodeFromString<GitHubReleaseResponse>(rawJson)
            val apkAsset = gh.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: error("Bản phát hành GitHub không có tệp .apk đính kèm.")
            val cleanVersion = gh.tagName.trim().removePrefix("v").removePrefix("V")
                .ifBlank { gh.name.trim().removePrefix("v").removePrefix("V") }

            val parsedCode = bodyVersionCodeRegex.find(gh.body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val parsedSha = bodySha256Regex.find(gh.body)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()

            UpdateReleaseInfo(
                versionCode = parsedCode,
                versionName = cleanVersion,
                apkUrl = apkAsset.browserDownloadUrl,
                changelog = gh.body.trim(),
                sha256 = parsedSha,
                mandatory = false,
            )
        }
    }

    /**
     * Returns true ONLY when [remote] is strictly newer than the installed version.
     * - Compares integer [versionCode] first whenever available (100% exact).
     * - Guards against human tag typos like "1.41" when installed version is "1.4.1" (same digits "141").
     */
    fun isNewerVersion(
        remote: UpdateReleaseInfo,
        currentVersionCode: Int,
        currentVersionName: String,
    ): Boolean {
        if (remote.versionCode > 0 && currentVersionCode > 0) {
            return remote.versionCode > currentVersionCode
        }

        val rParts = parseVersionParts(remote.versionName)
        val cParts = parseVersionParts(currentVersionName)
        // Guard against missing-dot typo like tag "1.41" matching installed "1.4.1"
        if (rParts.size == 2 && cParts.size == 3 && rParts[0] == cParts[0]) {
            val combinedCurrentMinorPatch = cParts[1] * 10 + cParts[2]
            if (cParts[2] in 0..9 && rParts[1] == combinedCurrentMinorPatch) {
                return false
            }
        }

        return compareSemVer(remote.versionName, currentVersionName) > 0
    }

    fun compareSemVer(remoteVersion: String, currentVersion: String): Int {
        val rParts = parseVersionParts(remoteVersion)
        val cParts = parseVersionParts(currentVersion)
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val cv = cParts.getOrElse(i) { 0 }
            if (rv != cv) return rv.compareTo(cv)
        }
        return 0
    }

    private fun parseVersionParts(version: String): List<Int> {
        val clean = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (clean.isBlank()) return emptyList()
        return clean.split('.').map { part ->
            part.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun resolveRelativeUrl(baseUrl: String, targetUrl: String): String {
        val trimmed = targetUrl.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return runCatching {
            URI(baseUrl).resolve(trimmed).toString()
        }.getOrDefault(trimmed)
    }
}
