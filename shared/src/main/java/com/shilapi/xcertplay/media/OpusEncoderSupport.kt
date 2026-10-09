package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * Android has guaranteed an Opus *decoder* since API 21 but only guarantees an Opus *encoder*
 * from API 29, so an Android 8 or 9 unit plays CarPlay audio yet cannot send the microphone as
 * Opus. The accessory advertises its input formats in the info plist, long before any stream is
 * set up, so the answer has to come from the codec list rather than from a failed encoder.
 */
object OpusEncoderSupport {
    /** Treats an unreadable codec list as no encoder: PCM uplink works everywhere, Opus does not. */
    fun isAvailable(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 21) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
                }
            }
        } else {
            false
        }
    } catch (error: Throwable) {
        false
    }
}
