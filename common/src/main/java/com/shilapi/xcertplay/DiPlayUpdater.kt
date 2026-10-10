package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.host.R
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Checks for signed GitHub Release updates without blocking the UI.
 * Download and OS installation always require user confirmation.
 */
internal class DiPlayUpdater(private val activity: DiPlayActivity) {
    private val prefs = activity.getSharedPreferences("diplay_updates", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "diplay-updater").apply { isDaemon = true }
    }
    @Volatile private var closed = false
    @Volatile private var busy = false
    @Volatile private var cancelled = false
    private var awaitingInstallPermission: File? = null
    private var pendingRelease: DiPlayUpdateClient.Release? = null
    private var downloadDialog: AlertDialog? = null

    fun autoEnabled(): Boolean = prefs.getBoolean("auto_check", true)
    fun setAutoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_check", enabled).apply()
    }

    fun checkAutomatically() {
        if (!autoEnabled() || closed) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_success", 0L) in 0 until 24L * 60 * 60 * 1000) return
        // Network access may be absent when the car boots; retry on a later launch after an hour.
        if (now - prefs.getLong("last_attempt", 0L) in 0 until 60L * 60 * 1000) return
        prefs.edit().putLong("last_attempt", now).apply()
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (closed) return
        if (busy) {
            if (manual) toast(R.string.update_checking)
            return
        }
        busy = true
        if (manual) toast(R.string.update_checking)
        worker.execute {
            try {
                val installed = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                    ?: throw IOException("Installed version unavailable")
                val release = DiPlayUpdateClient.latest(installed)
                if (!manual) prefs.edit().putLong("last_success", System.currentTimeMillis()).apply()
                onUi {
                    if (release == null) {
                        if (manual) toast(R.string.update_up_to_date)
                    } else if (manual || !CarPlayBackgroundSession.hasSession()) {
                        showRelease(release)
                    } else {
                        pendingRelease = release // Do not interrupt an active CarPlay session.
                    }
                }
            } catch (error: Exception) {
                android.util.Log.w("DiPlayUpdate", "Release check failed", error)
                if (manual) onUi { showFailure(error) }
            } finally {
                busy = false
            }
        }
    }

    private fun showRelease(release: DiPlayUpdateClient.Release) {
        val notes = release.notes.ifBlank { activity.getString(R.string.update_no_notes) }
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available, release.tag))
            .setMessage(notes + "\n\n" + activity.getString(R.string.update_parked_notice))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.update_download) { _, _ -> download(release) }
            .show()
    }

    private fun download(release: DiPlayUpdateClient.Release) {
        if (closed || busy) return
        if (CarPlayBackgroundSession.hasSession()) {
            AlertDialog.Builder(activity).setMessage(R.string.update_disconnect_first)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        cancelled = false
        busy = true
        val meter = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        val label = TextView(activity).apply {
            text = "0%"
            gravity = Gravity.CENTER
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
            addView(meter)
            addView(label)
        }
        downloadDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
            .setCancelable(false)
            .show()
        worker.execute {
            try {
                var lastPercent = -1
                val file = DiPlayUpdateClient.download(
                    release, File(activity.cacheDir, "diplay-updates"),
                ) { percent ->
                    if (cancelled || closed) throw IOException("Download cancelled")
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onUi {
                            meter.progress = percent
                            label.text = "$percent%"
                        }
                    }
                }
                if (cancelled || closed) return@execute
                verifyPackage(file, release)
                onUi {
                    downloadDialog?.dismiss()
                    downloadDialog = null
                    AlertDialog.Builder(activity)
                        .setTitle(activity.getString(R.string.update_install_title, release.tag))
                        .setMessage(R.string.update_install_notice)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.update_install) { _, _ -> requestInstall(file) }
                        .show()
                }
            } catch (error: Exception) {
                android.util.Log.w("DiPlayUpdate", "Update download or verification failed", error)
                if (!cancelled) onUi { showFailure(error) }
            } finally {
                busy = false
                onUi { downloadDialog?.dismiss(); downloadDialog = null }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(apk: File, release: DiPlayUpdateClient.Release) {
        // GET_SIGNATURES remains supported on Android 4.4+ and works on old vendor APK parsers.
        val pm = activity.packageManager
        val installed = pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        val candidate = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
            ?: throw IOException("Downloaded file is not a readable APK")
        if (candidate.packageName != installed.packageName ||
            !DiPlayUpdateClient.newerVersion(release.tag, installed.versionName ?: "") ||
            candidate.versionName != release.tag.removePrefix("v") ||
            candidate.versionCode <= installed.versionCode
        ) throw IOException("APK package or version does not match the release")
        val installedCerts = installed.signatures?.map { it.toCharsString() }?.sorted()
        val candidateCerts = candidate.signatures?.map { it.toCharsString() }?.sorted()
        if (installedCerts.isNullOrEmpty() || candidateCerts != installedCerts) {
            throw IOException("APK signing certificate differs from the installed DiPlay app")
        }
    }

    private fun requestInstall(file: File) {
        if (CarPlayBackgroundSession.hasSession()) {
            AlertDialog.Builder(activity).setMessage(R.string.update_disconnect_first)
                .setPositiveButton(android.R.string.ok, null).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = file
            AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_open_settings) { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")),
                        )
                    } catch (_: Exception) {
                        toast(R.string.update_install_unavailable)
                    }
                }.show()
            return
        }
        try {
            // A file:// URI to app-private cache is unreadable by the system installer on KitKat.
            // FileProvider works on API 19+, granting access only to this verified APK.
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.apk-updates", file,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (Build.VERSION.SDK_INT >= 16) {
                    clipData = ClipData.newUri(activity.contentResolver, "DiPlay update", uri)
                }
            }
            activity.startActivity(intent)
            awaitingInstallPermission = null
        } catch (error: Exception) {
            android.util.Log.w("DiPlayUpdate", "System installer unavailable", error)
            toast(R.string.update_install_unavailable)
        }
    }

    fun onResume() {
        if (pendingRelease != null && !CarPlayBackgroundSession.hasSession()) {
            val release = pendingRelease!!
            pendingRelease = null
            showRelease(release)
        }
        val file = awaitingInstallPermission ?: return
        if (Build.VERSION.SDK_INT >= 26 && activity.packageManager.canRequestPackageInstalls()) {
            awaitingInstallPermission = null
            AlertDialog.Builder(activity).setMessage(R.string.update_install_notice)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_install) { _, _ -> requestInstall(file) }.show()
        }
    }

    fun close() {
        closed = true
        cancelled = true
        awaitingInstallPermission = null
        pendingRelease = null
        downloadDialog?.dismiss()
        downloadDialog = null
        worker.shutdownNow()
    }

    private fun showFailure(error: Exception) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.update_failed)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun onUi(action: () -> Unit) {
        activity.runOnUiThread {
            if (!closed && !activity.isFinishing && !activity.isDestroyed) action()
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
