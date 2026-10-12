package com.shilapi.xcertplay.media

import android.media.AudioManager

/**
 * Issue #88: on many head units the Android STREAM_MUSIC volume domain is independent of the
 * MCU/knob volume domain, so CarPlay audio rendered through AudioTrack can be inaudible while
 * everything else sounds fine. The app never changes the volume itself; this only produces a
 * warning so the report (and the user) can see that the head unit's media volume is the
 * likely culprit.
 */
internal fun musicVolumeWarning(volume: Int, maxVolume: Int): String? =
    if (maxVolume > 0 && volume * 10 < maxVolume) {
        "Audio: WARNING STREAM_MUSIC volume is $volume/$maxVolume - CarPlay audio will be " +
            "silent or barely audible; raise the head unit's media volume"
    } else {
        null
    }

internal fun musicVolumeWarning(audio: AudioManager?): String? = runCatching {
    val manager = audio ?: return@runCatching null
    musicVolumeWarning(
        manager.getStreamVolume(AudioManager.STREAM_MUSIC),
        manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
    )
}.getOrNull()
