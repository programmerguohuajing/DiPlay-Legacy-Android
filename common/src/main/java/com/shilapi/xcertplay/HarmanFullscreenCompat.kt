package com.shilapi.xcertplay

import android.view.View
import android.view.Window

/** Opt-in legacy system-UI mode observed to reclaim the OEM side panel on Harman firmware. */
@Suppress("DEPRECATION")
internal object HarmanFullscreenCompat {
    internal const val OEM_FULLSCREEN_BIT = 0x8
    internal const val SYSTEM_UI_FLAGS =
        // Issue #363: IMMERSIVE_STICKY — without it, once a system overlay
        // (the OEM bottom bar after a reverse-camera cycle) is re-shown by
        // the system, the non-sticky mode never hides it again on its own.
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_IMMERSIVE or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            OEM_FULLSCREEN_BIT

    fun apply(window: Window) {
        window.decorView.systemUiVisibility = SYSTEM_UI_FLAGS
    }

    fun clear(window: Window) {
        window.decorView.systemUiVisibility =
            window.decorView.systemUiVisibility and OEM_FULLSCREEN_BIT.inv()
    }
}
