package com.shilapi.xcertplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNoException
import org.junit.Test

/** Regression tests for issue #108: one USB volume seen through several mount points. */
class DiagnosticExportDedupeTest {
    @Test fun sameVolumeThroughSeveralMountPointsProducesOneRoot() {
        val volume = Files.createTempDirectory("usb-volume").toFile()
        val mountA = Files.createTempDirectory("mount-a").toFile()
        val linkA = File(mountA, "usb0")
        val mountB = Files.createTempDirectory("mount-b").toFile()
        val linkB = File(mountB, "usbhost0")
        try {
            try {
                Files.createSymbolicLink(linkA.toPath(), volume.toPath())
                Files.createSymbolicLink(linkB.toPath(), volume.toPath())
            } catch (e: Exception) {
                assumeNoException("Symlink creation not permitted in this environment", e)
            }
            val roots = DiagnosticExportStore.dedupeExportRoots(listOf(linkA, linkB, volume))
            assertEquals(1, roots.size)
            assertEquals(volume.canonicalPath, roots[0].canonicalPath)
        } finally {
            linkA.delete()
            linkB.delete()
            mountA.deleteRecursively()
            mountB.deleteRecursively()
            volume.deleteRecursively()
        }
    }

    @Test fun distinctVolumesAreKept() {
        val first = Files.createTempDirectory("vol-1").toFile()
        val second = Files.createTempDirectory("vol-2").toFile()
        try {
            val roots = DiagnosticExportStore.dedupeExportRoots(listOf(first, second))
            assertEquals(2, roots.size)
        } finally {
            first.deleteRecursively()
            second.deleteRecursively()
        }
    }

    @Test fun firstCandidateWinsAndOrderIsStable() {
        val volume = Files.createTempDirectory("usb-vol").toFile()
        val alias = File(volume.parentFile, "usb-alias")
        try {
            try {
                Files.createSymbolicLink(alias.toPath(), volume.toPath())
            } catch (e: Exception) {
                assumeNoException("Symlink creation not permitted in this environment", e)
            }
            val roots = DiagnosticExportStore.dedupeExportRoots(listOf(alias, volume))
            assertEquals(1, roots.size)
            assertEquals(volume.canonicalPath, roots[0].path)
        } finally {
            alias.delete()
            volume.deleteRecursively()
        }
    }
}
