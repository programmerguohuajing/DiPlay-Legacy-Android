package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Whether this head unit can decode Opus for CarPlay audio playback.
 *
 * Android guarantees an Opus decoder in MediaCodec from Android 5.0 (API 21), but Android 4.4.2
 * (API 19) lacks built-in Opus decoding. On units without Opus decoder support, AirPlay advertising
 * must omit Opus so that the iPhone sends uncompressed PCM or AAC-LC instead.
 */
object OpusDecoderSupport {
    fun isAvailable(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 21) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any {
                        it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
                    }
                }
            } else {
                true
            }
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }
}
