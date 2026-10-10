package com.shilapi.xcertplay

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
        val copyPaths: List<String> = emptyList(),
    )

    /** Android 9 and OEMs without working Downloads storage can still export privately. */
    fun saveWithoutPicker(context: Context, fileName: String, report: String, preferredRoot: File? = null): SavedReport {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return SavedReport(saveToDownloads(context.contentResolver, fileName, report))
            } catch (_: Exception) {
                // Preserve the report even when the OEM's public storage provider is absent.
            }
        }
        val saved = try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            } else {
                saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
            saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
        }
        val copyPaths = exportCopiesToAccessibleStorage(context, fileName, report, preferredRoot)
        return saved.copy(copyPaths = copyPaths)
    }

    /** Exposes writable USB-like volumes to the in-app KitKat destination picker. */
    fun availableUsbVolumes(context: Context): List<File> {
        val candidates = linkedSetOf<File>()
        runCatching {
            context.getExternalFilesDirs(null)?.drop(1)?.filterNotNull()?.forEach { candidates.add(it) }
        }
        val usbName = Regex("(?i).*(usb|udisk|usbotg|otg|removable|external_usb).*")
        for (base in listOf("/storage", "/mnt", "/mnt/media_rw")) {
            for (child in File(base).listFiles().orEmpty()) {
                if (!child.isDirectory) continue
                if (usbName.matches(child.name)) candidates.add(child)
                for (sub in child.listFiles().orEmpty()) {
                    if (sub.isDirectory && (usbName.matches(child.name) || usbName.matches(sub.name))) {
                        candidates.add(sub)
                    }
                }
            }
        }
        return candidates.filter { it.isDirectory && it.canWrite() }.distinctBy {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)
        }
    }
    /** Best-effort verified copies: report success only for a write that actually completed. */
    private fun exportCopiesToAccessibleStorage(context: Context, fileName: String, report: String, preferredRoot: File?): List<String> {
        val written = linkedSetOf<String>()
        val roots = linkedSetOf<File>()
        if (preferredRoot != null) roots.add(preferredRoot)
        runCatching { roots.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) }
        runCatching { roots.add(Environment.getExternalStorageDirectory()) }
        runCatching {
            context.getExternalFilesDirs(null)?.drop(1)?.filterNotNull()?.forEach { roots.add(it) }
        }
        // Android 4.4 factory ROMs frequently mount USB media under vendor-named directories.
        // Only inspect immediate children and one additional level, never traverse arbitrary trees.
        val basePaths = listOf("/storage", "/mnt", "/mnt/media_rw")
        val usbNames = Regex("(?i).*(usb|udisk|usbotg|otg|removable|external_usb).*")
        for (base in basePaths) {
            val dir = File(base)
            for (child in dir.listFiles().orEmpty()) {
                if (!child.isDirectory) continue
                if (usbNames.matches(child.name)) roots.add(child)
                for (sub in child.listFiles().orEmpty()) {
                    if (sub.isDirectory && (usbNames.matches(child.name) || usbNames.matches(sub.name))) {
                        roots.add(sub)
                    }
                }
            }
        }
        for (root in roots) {
            runCatching {
                if (!root.isDirectory || !root.canWrite()) return@runCatching
                val canonicalRoot = root.canonicalFile
                val dir = File(canonicalRoot, "DiPlay")
                if (!dir.isDirectory && !dir.mkdirs()) return@runCatching
                val destination = File(dir, fileName)
                // Do not overwrite an existing report or follow an unexpected symlink.
                if (destination.exists()) return@runCatching
                val bytes = report.toByteArray(Charsets.UTF_8)
                destination.outputStream().use { it.write(bytes); it.flush() }
                if (destination.length() != bytes.size.toLong()) {
                    destination.delete()
                    return@runCatching
                }
                written.add(destination.absolutePath)
            }
        }
        return written.toList()
    }
    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostic-reports", file)
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            return SavedReport(uri, savedInApp = savedInApp, savedPath = if (savedInApp) null else file.absolutePath)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(resolver: ContentResolver, fileName: String, report: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DiPlay")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the report")
        try {
            write(resolver, uri, report)
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the report")
            return uri
        } catch (error: Exception) {
            // Only remove the entry created by this call; never leave a partial report behind.
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
