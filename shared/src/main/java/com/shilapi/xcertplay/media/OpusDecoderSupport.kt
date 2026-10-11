package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Whether this head unit can decode Opus for CarPlay audio playback.
 *
 * Android guarantees an Opus decoder in MediaCodec from Android 5.0 (API 21), but Android 4.4.2
 * (API 19) lacks built-in Opus decoding. Note that Apple Wireless CarPlay protocol strictly mandates
 * Opus (0x70000000) to be advertised in audioFormats (type 100 default); omitting it causes iOS to
 * refuse wireless audio stream negotiation and route audio through iPhone speakers.
 */
object OpusDecoderSupport {
    fun isAvailable(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)
                }
            }
        } else if (Build.VERSION.SDK_INT >= 21) {
            @Suppress("DEPRECATION")
            (0 until MediaCodecList.getCodecCount()).any { i ->
                @Suppress("DEPRECATION")
                val info = MediaCodecList.getCodecInfoAt(i)
                !info.isEncoder && info.supportedTypes.any {
                    it.equals("audio/opus", ignoreCase = true)
                }
            }
        } else {
            false
        }
    } catch (_: Throwable) {
        false
    }
}
