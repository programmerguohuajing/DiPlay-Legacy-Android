package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DiPlayGiteeReleasesTest {
    private val tag = "v0.2.36"
    private val repo = "https://gitee.com/JingRuirui/DiPlay-Legacy-Android"
    private val apk = "DiPlay-$tag-legacy-release.apk"
    private val prefix = "$repo/releases/download/$tag/"

    @Test fun matchesPublishedAttachmentsNotAutomaticSourceArchives() {
        val metadata = JSONObject().put("tag_name", tag).put("id", 1245)
            .put("body", "Fixed China update mirror").toString()
        val links = attachments().toString()
        val requested = mutableListOf<String>()
        val release = DiPlayGiteeReleases.latest("0.2.35") { url ->
            requested += url
            if (url.endsWith("/releases/latest")) metadata else links
        }
        assertEquals(tag, release?.tag)
        assertEquals(prefix + apk, release?.apkUrl)
        assertEquals(prefix + apk + ".sha256", release?.checksumUrl)
        assertEquals(15_422_760L, release?.declaredSize)
        assertEquals(2, requested.size)
        assertTrue(requested[1].endsWith("/releases/1245/attach_files"))
        assertNull(DiPlayGiteeReleases.latest("0.2.36") { metadata })
    }

    @Test fun rejectsOtherRepositoriesAndMissingDigest() {
        val wrong = attachments().apply {
            getJSONObject(0).put("browser_download_url", "https://evil.example/$apk")
        }
        assertThrows(IOException::class.java) {
            DiPlayGiteeReleases.parseAttachments(tag, "notes", wrong)
        }
        assertThrows(IOException::class.java) {
            DiPlayGiteeReleases.parseAttachments(tag, "", JSONArray().put(
                JSONObject().put("name", apk)
                    .put("size", 123L)
                    .put("browser_download_url", prefix + apk),
            ))
        }
    }

    @Test fun rejectsSourceArchivesAndOversizedApks() {
        assertThrows(IOException::class.java) {
            DiPlayGiteeReleases.parseAttachments(tag, "notes", JSONArray().put(
                JSONObject().put("name", "$tag.zip")
                    .put("browser_download_url", "$repo/archive/refs/tags/$tag.zip"),
            ))
        }
        assertThrows(IOException::class.java) {
            DiPlayGiteeReleases.parseAttachments(tag, "notes", attachments().apply {
                getJSONObject(0).put("size", 200L * 1024 * 1024)
            })
        }
    }

    private fun attachments(): JSONArray = JSONArray()
        .put(JSONObject().put("name", apk).put("size", 15_422_760L)
            .put("browser_download_url", prefix + apk))
        .put(JSONObject().put("name", "$apk.sha256").put("size", 100)
            .put("browser_download_url", prefix + apk + ".sha256"))
}
