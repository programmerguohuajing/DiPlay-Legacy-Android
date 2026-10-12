package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #88: a near-zero STREAM_MUSIC volume must produce a loud, greppable warning while
 * healthy volumes stay silent.
 */
class MusicVolumeWarningTest {
    @Test
    fun lowVolumeProducesWarning() {
        val warning = musicVolumeWarning(volume = 1, maxVolume = 30)
        assertTrue(warning!!.contains("1/30"))
        assertTrue(warning.contains("WARNING"))
    }

    @Test
    fun boundaryVolumeStaysSilent() {
        // Exactly 10% is not "near zero".
        assertNull(musicVolumeWarning(volume = 3, maxVolume = 30))
        assertNull(musicVolumeWarning(volume = 10, maxVolume = 15))
        assertNull(musicVolumeWarning(volume = 15, maxVolume = 15))
    }

    @Test
    fun degenerateMaxVolumeStaysSilent() {
        assertNull(musicVolumeWarning(volume = 0, maxVolume = 0))
        assertNull(musicVolumeWarning(volume = 1, maxVolume = -1))
    }

    @Test
    fun nullAudioManagerStaysSilent() {
        assertNull(musicVolumeWarning(null))
    }

    @Test
    fun warningTextIsStable() {
        assertEquals(
            "Audio: WARNING STREAM_MUSIC volume is 1/30 - CarPlay audio will be " +
                "silent or barely audible; raise the head unit's media volume",
            musicVolumeWarning(volume = 1, maxVolume = 30),
        )
    }
}
