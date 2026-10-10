package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiPlayUpdateClientTest {
    private val name = "DiPlay-v0.2.34-legacy-release.apk"
    private val prefix = "https://github.com/programmerguohuajing/DiPlay-Legacy-Android/releases/download/v0.2.34/"

    @Test fun acceptsOnlyHigherStableVersion() {
        assertTrue(DiPlayUpdateClient.newerVersion("v0.2.34", "0.2.33"))
        assertTrue(DiPlayUpdateClient.newerVersion("v1.0.0", "0.9.99"))
        assertFalse(DiPlayUpdateClient.newerVersion("v0.2.33", "0.2.33"))
        assertFalse(DiPlayUpdateClient.newerVersion("v0.2.32", "0.2.33"))
        assertFalse(DiPlayUpdateClient.newerVersion("v0.2.34-beta", "0.2.33"))
        assertFalse(DiPlayUpdateClient.newerVersion("vabc", "0.2.33"))
    }

    @Test fun discoversReleaseOnlyWithMatchingApkAndChecksumAssets() {
        val release = DiPlayUpdateClient.parseLatest(json(), "0.2.33")
        assertEquals("v0.2.34", release?.tag)
        assertEquals(name, release?.apkName)
        assertEquals(prefix + name, release?.apkUrl)
        assertNull(DiPlayUpdateClient.parseLatest(json(), "0.2.34"))
        assertNull(DiPlayUpdateClient.parseLatest(json(prerelease = true), "0.2.33"))
    }

    @Test fun rejectsMissingOrExternalReleaseAssets() {
        try {
            DiPlayUpdateClient.parseLatest(json(shaName = "different.sha256"), "0.2.33")
            throw AssertionError("Missing checksum should fail")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("SHA-256"))
        }
        try {
            DiPlayUpdateClient.parseLatest(json(apkUrl = "https://malicious.example/$name"), "0.2.33")
            throw AssertionError("External asset should fail")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message!!.contains("APK"))
        }
    }

    @Test fun checksumAcceptsOnlyValidDigestForExpectedApk() {
        val hash = "a".repeat(64)
        assertEquals(hash, DiPlayUpdateClient.parseChecksum("$hash  $name\n", name))
        assertEquals(hash, DiPlayUpdateClient.parseChecksum(hash, name))
        for (bad in listOf("abcd  $name", "$hash  different.apk", "a".repeat(65))) {
            try {
                DiPlayUpdateClient.parseChecksum(bad, name)
                throw AssertionError("Expected rejection of invalid digest: $bad")
            } catch (_: java.io.IOException) { }
        }
    }

    private fun json(
        prerelease: Boolean = false,
        shaName: String = "$name.sha256",
        apkUrl: String = prefix + name,
    ): String = org.json.JSONObject().apply {
        put("tag_name", "v0.2.34")
        put("prerelease", prerelease)
        put("draft", false)
        put("body", "A stable update")
        put("assets", org.json.JSONArray().apply {
            put(org.json.JSONObject().put("name", name).put("size", 1024)
                .put("browser_download_url", apkUrl))
            put(org.json.JSONObject().put("name", shaName).put("size", 100)
                .put("browser_download_url", prefix + shaName))
        })
    }.toString()
}
