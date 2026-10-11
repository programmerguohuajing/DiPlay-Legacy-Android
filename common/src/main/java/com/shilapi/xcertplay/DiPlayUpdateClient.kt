package com.shilapi.xcertplay

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** Prefer the Gitee release mirror, with GitHub fallback; never install unverified assets. */
internal object DiPlayUpdateClient {
    private const val REPO = "programmerguohuajing/DiPlay-Legacy-Android"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MAX_METADATA = 512 * 1024
    private const val MAX_DIGEST_FILE = 2 * 1024
    private const val MAX_APK_BYTES = 120L * 1024 * 1024
    private val tagPattern = Regex("^v(\\d+)\\.(\\d+)\\.(\\d+)$")
    private val digestPattern = Regex("^[a-fA-F0-9]{64}$")

    data class Release(
        val tag: String,
        val notes: String,
        val apkName: String,
        val apkUrl: String,
        val checksumUrl: String,
        val declaredSize: Long,
    )

    /** Treat malformed, prerelease and non-increasing version labels as non-updates. */
    fun newerVersion(remote: String, installed: String): Boolean {
        val remoteParts = tagPattern.matchEntire(remote)?.groupValues?.drop(1)
            ?.mapNotNull { it.toIntOrNull() } ?: return false
        val localParts = installed.removePrefix("v").substringBefore('-').split('.')
            .takeIf { it.size == 3 }?.mapNotNull { it.toIntOrNull() } ?: return false
        if (localParts.size != 3) return false
        for (index in 0..2) {
            if (remoteParts[index] > localParts[index]) return true
            if (remoteParts[index] < localParts[index]) return false
        }
        return false
    }

    /** China-accessible Gitee is first; try GitHub only if its mirror cannot be read. */
    fun latest(installed: String): Release? = try {
        DiPlayGiteeReleases.latest(installed) { readText(it, MAX_METADATA) }
    } catch (giteeFailure: Exception) {
        try {
            latestFromGitHub(installed)
        } catch (githubFailure: Exception) {
            githubFailure.addSuppressed(giteeFailure)
            throw githubFailure
        }
    }

    /** Unauthenticated GitHub API calls may be rate-limited on shared mobile networks. */
    private fun latestFromGitHub(installed: String): Release? = try {
        parseLatest(readText(LATEST_URL, MAX_METADATA), installed)
    } catch (apiFailure: Exception) {
        // GitHub's official Atom feed has no REST API quota.
        try {
            latestFromAtom(installed)
        } catch (feedFailure: Exception) {
            feedFailure.addSuppressed(apiFailure)
            throw feedFailure
        }
    }

    /** Atom fallback when anonymous REST API quota is exhausted. */
    private fun latestFromAtom(installed: String): Release? {
        val feedUrl = "https://github.com/$REPO/releases.atom"
        val entry = DiPlayAtomSource.newest(readText(feedUrl, MAX_METADATA), installed)
            ?: return null
        val apk = "DiPlay-${entry.tag}-legacy-release.apk"
        val url = "https://github.com/$REPO/releases/download/${entry.tag}/$apk"
        val head = connect(url, method = "HEAD")
        val size = try {
            head.getHeaderField("Content-Length")?.toLongOrNull()
                ?: throw IOException("Release APK size unavailable")
        } finally {
            head.disconnect()
        }
        if (size <= 0 || size > MAX_APK_BYTES) throw IOException("Invalid release APK size")
        val release = Release(entry.tag, entry.notes, apk, url, "$url.sha256", size)
        checksum(release) // Verify the expected digest file is actually published.
        return release
    }

    internal fun parseLatest(json: String, installed: String): Release? {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name")
        if (!newerVersion(tag, installed)) return null
        val apk = "DiPlay-$tag-legacy-release.apk"
        val prefix = "https://github.com/$REPO/releases/download/$tag/"
        val assets = root.getJSONArray("assets")
        var apkUrl: String? = null
        var shaUrl: String? = null
        var bytes = 0L
        for (index in 0 until assets.length()) {
            val asset = assets.getJSONObject(index)
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (url != prefix + name) continue
            when (name) {
                apk -> {
                    apkUrl = url
                    bytes = asset.optLong("size")
                }
                "$apk.sha256" -> shaUrl = url
            }
        }
        if (apkUrl == null || shaUrl == null || bytes <= 0 || bytes > MAX_APK_BYTES) {
            throw IOException("Release $tag does not contain a valid APK and SHA-256 asset")
        }
        return Release(tag, root.optString("body").take(6000), apk, apkUrl, shaUrl, bytes)
    }

    fun checksum(release: Release): String =
        parseChecksum(readText(release.checksumUrl, MAX_DIGEST_FILE), release.apkName)

    internal fun parseChecksum(content: String, apkName: String): String {
        val words = content.trim().split(Regex("\\s+"))
        val hash = words.firstOrNull()?.lowercase()
        if (hash == null || !digestPattern.matches(hash) ||
            (words.size > 1 && words.drop(1).joinToString(" ").trimStart('*') != apkName)
        ) throw IOException("Invalid release SHA-256 checksum file")
        return hash
    }

    fun download(release: Release, directory: File, progress: (Int) -> Unit): File {
        val expected = checksum(release)
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Cannot create update cache")
        val finished = File(directory, release.apkName)
        val temporary = File(directory, release.apkName + ".part")
        temporary.delete()
        try {
            val connection = connect(release.apkUrl)
            try {
                val messageDigest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                connection.inputStream.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            count += read
                            if (count > MAX_APK_BYTES || count > release.declaredSize) {
                                throw IOException("APK exceeds its declared size")
                            }
                            messageDigest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            progress((count * 100L / release.declaredSize).toInt().coerceIn(0, 100))
                        }
                    }
                }
                val actual = messageDigest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                if (count != release.declaredSize || actual != expected) {
                    throw IOException("Downloaded APK size or SHA-256 does not match the published release")
                }
            } finally {
                connection.disconnect()
            }
            if (finished.exists() && !finished.delete()) throw IOException("Cannot replace cached update")
            if (!temporary.renameTo(finished)) throw IOException("Cannot finalize the downloaded APK")
            return finished
        } finally {
            temporary.delete()
        }
    }

    private fun readText(url: String, limit: Int): String {
        val connection = connect(url)
        try {
            connection.inputStream.use { stream ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    if (data.size() + read > limit) throw IOException("Update metadata is too large")
                    data.write(buffer, 0, read)
                }
                return data.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Older Android 4.4 systems often disable TLS 1.2 in their platform HTTPS provider. */
    private val legacyTlsFactory by lazy {
        val trust = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm(),
        )
        trust.init(null as java.security.KeyStore?)
        javax.net.ssl.SSLContext.getInstance("TLS", org.conscrypt.Conscrypt.newProvider()).apply {
            init(null, trust.trustManagers, null)
        }.socketFactory
    }

    private fun connect(url: String, method: String = "GET"): HttpsURLConnection {
        var next = url
        repeat(7) {
            val candidate = URL(next)
            val host = candidate.host.lowercase()
            val approvedHost = host == "api.github.com" || host == "github.com" ||
                host == "release-assets.githubusercontent.com" ||
                host == "objects.githubusercontent.com" ||
                host == "gitee.com" || host.endsWith(".gitee.com")
            if (candidate.protocol != "https" || !approvedHost) {
                throw IOException("Untrusted update redirect")
            }
            val connection = candidate.openConnection() as HttpsURLConnection
            if (android.os.Build.VERSION.SDK_INT < 22) {
                connection.sslSocketFactory = legacyTlsFactory
            }
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "DiPlay-Legacy-Android-Updater")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val redirect = connection.getHeaderField("Location")
                    ?: throw IOException("Update redirect has no target")
                next = URL(candidate, redirect).toString()
                connection.disconnect()
            } else {
                if (status != 200) {
                    connection.disconnect()
                    throw IOException("Update server returned HTTP $status")
                }
                return connection
            }
        }
        throw IOException("Too many update redirects")
    }
}
