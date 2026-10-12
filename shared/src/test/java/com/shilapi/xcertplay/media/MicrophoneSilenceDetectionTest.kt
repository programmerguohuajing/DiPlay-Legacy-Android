package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #102 triage: the capture path must report silence (all-zero frames) and the delay
 * until the first audible frame, so "recorder yields zeroes" can be told apart from
 * "packets never sent" from a diagnostic report alone.
 */
class MicrophoneSilenceDetectionTest {
    private val config = MicrophoneConfig(
        "telephony", 8000, 1, 100, 20,
        InetAddress.getByName("198.51.100.20"), 54321, ByteArray(32) { 0x7f }, AudioCodecKind.OPUS,
    )

    @Test
    fun silentStreakEmitsOneNoticeAtThreshold() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.started(15)
        repeat(MicrophoneCaptureStats.SILENT_STREAK_REPORT_FRAMES - 1) { stats.frameSilent(true) }
        assertTrue(reports.none { it.startsWith("Microphone: silent ") })
        stats.frameSilent(true)
        val notice = reports.single { it.startsWith("Microphone: silent ") }
        assertTrue(notice.contains("frames=${MicrophoneCaptureStats.SILENT_STREAK_REPORT_FRAMES}"))
        // A continuing streak must not spam the log.
        repeat(200) { stats.frameSilent(true) }
        assertEquals(1, reports.count { it.startsWith("Microphone: silent ") })
    }

    @Test
    fun firstNonSilentFrameRecordsDelayAndResetsStreak() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.started(15)
        repeat(10) { stats.frameSilent(true) }
        now = 1_250_000_000L
        stats.frameSilent(false)
        now = 5_000_000_000L
        stats.flush()
        val line = reports.last()
        assertTrue(line.contains("firstAudioMs=1250"))
        assertTrue(line.contains("silentFrames=10"))
        // A new streak after audio emits again.
        repeat(MicrophoneCaptureStats.SILENT_STREAK_REPORT_FRAMES) { stats.frameSilent(true) }
        assertEquals(1, reports.count { it.startsWith("Microphone: silent ") })
    }

    @Test
    fun statsBeforeAnyAudioReportNegativeFirstAudio() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.started(15)
        repeat(3) { stats.frameSilent(true) }
        now = 5_000_000_000L
        stats.flush()
        val line = reports.last()
        assertTrue(line.contains("firstAudioMs=-1"))
        assertTrue(line.contains("silentFrames=3"))
    }
}
