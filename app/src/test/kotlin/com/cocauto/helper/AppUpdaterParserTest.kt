package com.cocauto.helper

import com.cocauto.helper.updater.UpdateManifestParser
import com.cocauto.helper.updater.UpdateReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterParserTest {

    @Test
    fun defaultUpdateSource_resolvesToConfiguredGithubRepo() {
        assertEquals(
            "https://api.github.com/repos/ledinhtien219/COCAUTO/releases/latest",
            UpdateManifestParser.normalizeSourceUrl(com.cocauto.helper.model.UpdateConfig.DEFAULT_UPDATE_SOURCE),
        )
    }

    @Test
    fun normalizeSourceUrl_handlesGithubShortcutsAndLanHosts() {
        assertEquals(
            "https://api.github.com/repos/myuser/coc-auto/releases/latest",
            UpdateManifestParser.normalizeSourceUrl("myuser/coc-auto"),
        )
        assertEquals(
            "https://api.github.com/repos/myuser/coc-auto/releases/latest",
            UpdateManifestParser.normalizeSourceUrl("https://github.com/myuser/coc-auto/releases"),
        )
        assertEquals(
            "http://192.168.1.12:8080/update.json",
            UpdateManifestParser.normalizeSourceUrl("192.168.1.12:8080"),
        )
        assertEquals(
            "https://example.com/coc/update.json",
            UpdateManifestParser.normalizeSourceUrl("https://example.com/coc/update.json"),
        )
    }

    @Test
    fun parseResponse_parsesCustomManifestWithRelativeApkUrl() {
        val raw = """
            {
              "versionCode": 4,
              "versionName": "v1.3.0",
              "apkUrl": "app-debug.apk",
              "changelog": "- Cải thiện OCR\n- Tự nâng tường nhanh hơn",
              "sha256": "ABCDEF012345"
            }
        """.trimIndent()

        val parsed = UpdateManifestParser.parseResponse(
            rawJson = raw,
            sourceUrl = "http://192.168.1.12:8080/update.json",
        ).getOrThrow()

        assertEquals(4, parsed.versionCode)
        assertEquals("1.3.0", parsed.versionName)
        assertEquals("http://192.168.1.12:8080/app-debug.apk", parsed.apkUrl)
        assertEquals("abcdef012345", parsed.sha256)
    }

    @Test
    fun parseResponse_parsesGithubReleaseAsset() {
        val raw = """
            {
              "tag_name": "v1.4.2",
              "name": "CoC Auto v1.4.2",
              "body": "Bản sửa lỗi Builder Base",
              "assets": [
                {
                  "name": "notes.txt",
                  "browser_download_url": "https://github.com/u/r/releases/download/v1.4.2/notes.txt"
                },
                {
                  "name": "coc-auto-v1.4.2.apk",
                  "browser_download_url": "https://github.com/u/r/releases/download/v1.4.2/coc-auto-v1.4.2.apk"
                }
              ]
            }
        """.trimIndent()

        val parsed = UpdateManifestParser.parseResponse(
            rawJson = raw,
            sourceUrl = "https://api.github.com/repos/u/r/releases/latest",
        ).getOrThrow()

        assertEquals("1.4.2", parsed.versionName)
        assertEquals("https://github.com/u/r/releases/download/v1.4.2/coc-auto-v1.4.2.apk", parsed.apkUrl)
        assertEquals("Bản sửa lỗi Builder Base", parsed.changelog)
    }

    @Test
    fun isNewerVersion_comparesVersionCodeAndSemVerProperly() {
        val newerByCode = UpdateReleaseInfo(versionCode = 4, versionName = "1.2.0", apkUrl = "x.apk")
        assertTrue(UpdateManifestParser.isNewerVersion(newerByCode, 3, "1.2.0"))

        val sameVersion = UpdateReleaseInfo(versionCode = 3, versionName = "1.2.0", apkUrl = "x.apk")
        assertFalse(UpdateManifestParser.isNewerVersion(sameVersion, 3, "1.2.0"))

        val newerSemVerGithub = UpdateReleaseInfo(versionCode = 0, versionName = "1.10.0", apkUrl = "x.apk")
        assertTrue(UpdateManifestParser.isNewerVersion(newerSemVerGithub, 3, "1.2.0"))

        val olderSemVerGithub = UpdateReleaseInfo(versionCode = 0, versionName = "1.1.9", apkUrl = "x.apk")
        assertFalse(UpdateManifestParser.isNewerVersion(olderSemVerGithub, 3, "1.2.0"))

        // Missing-dot typo guard: tag "1.41" must NOT endlessly re-trigger update when installed is "1.4.1"
        val missingDotTypo = UpdateReleaseInfo(versionCode = 0, versionName = "1.41", apkUrl = "x.apk")
        assertFalse(UpdateManifestParser.isNewerVersion(missingDotTypo, 7, "1.4.1"))

        // GitHub Release body containing "Build: 9" extracts exact versionCode=9
        val ghBodyWithBuild = """
            {
              "tag_name": "v1.5.0",
              "name": "CoC Auto v1.5.0",
              "body": "Bản phát hành tự động\n- Build: 9\n- SHA-256: `0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef`",
              "assets": [
                {
                  "name": "CoC-Auto-v1.5.0.apk",
                  "browser_download_url": "https://github.com/u/r/releases/download/v1.5.0/CoC-Auto-v1.5.0.apk"
                }
              ]
            }
        """.trimIndent()
        val parsed = UpdateManifestParser.parseResponse(
            ghBodyWithBuild,
            "https://api.github.com/repos/u/r/releases/latest",
        ).getOrThrow()
        assertEquals(9, parsed.versionCode)
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", parsed.sha256)
        assertFalse(UpdateManifestParser.isNewerVersion(parsed, 9, "1.5.0"))
        assertTrue(UpdateManifestParser.isNewerVersion(parsed, 8, "1.4.2"))
    }
}
