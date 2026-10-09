package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.compat.BluetoothCompat

internal object DiPlayBluetooth {
    @android.annotation.SuppressLint("MissingPermission")
    fun localAddress(context: Context): String? {
        val adapter = runCatching {
            BluetoothCompat.getAdapter(context)?.address
        }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }
}
