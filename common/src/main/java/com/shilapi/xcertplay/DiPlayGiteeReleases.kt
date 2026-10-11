package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * China-accessible mirror of verified, official DiPlay APK releases.
 * Gitee's `releases/latest` only lists source archives in "assets".
 * Uploaded APKs must be queried separately via `releases/{id}/attach_files`.
 */
internal object DiPlayGiteeReleases {
    const val REPO = "JingRuirui/DiPlay-Legacy-Android"
    private const val API = "https://gitee.com/api/v5/repos/$REPO"
    private const val DOWNLOADS = "https://gitee.com/$REPO/releases/download/"
    private const val MAX_APK_BYTES = 120L * 1024 * 1024

    fun latest(installed: String, fetch: (String) -> String): DiPlayUpdateClient.Release? {
        val release = JSONObject(fetch("$API/releases/latest"))
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        // A reachable Gitee feed takes priority even if it has no newer release.
        if (!DiPlayUpdateClient.newerVersion(tag, installed)) return null
        val id = release.optLong("id")
        if (id <= 0) throw IOException("Gitee release has no valid ID")

        val attachments = JSONArray(fetch("$API/releases/$id/attach_files"))
        return parseAttachments(tag, release.optString("body"), attachments)
    }

    internal fun parseAttachments(
        tag: String,
        releaseNotes: String,
        attachments: JSONArray,
    ): DiPlayUpdateClient.Release {
        if (!Regex("^v\\d+\\.\\d+\\.\\d+$").matches(tag)) {
            throw IOException("Unexpected Gitee release tag")
        }
        val apkName = "DiPlay-$tag-legacy-release.apk"
        val canonicalPrefix = "$DOWNLOADS$tag/"
        var apkUrl: String? = null
        var shaUrl: String? = null
        var size = 0L
        for (index in 0 until attachments.length()) {
            val item = attachments.getJSONObject(index)
            val name = item.optString("name")
            val url = item.optString("browser_download_url")
            // Enforce exact owner/repository/tag and attachment names.
            if (url != canonicalPrefix + name) continue
            when (name) {
                apkName -> {
                    apkUrl = url
                    size = item.optLong("size")
                }
                "$apkName.sha256" -> shaUrl = url
            }
        }
        if (apkUrl == null || shaUrl == null || size <= 0 || size > MAX_APK_BYTES) {
            throw IOException("Gitee release $tag is missing verified APK/SHA-256 attachments")
        }
        return DiPlayUpdateClient.Release(
            tag, releaseNotes.take(6000), apkName, apkUrl, shaUrl, size,
        )
    }
}
