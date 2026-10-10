package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.bluetooth.BluetoothAddressResolver

internal object DiPlayBluetooth {
    @android.annotation.SuppressLint("MissingPermission")
    fun localAddress(context: Context): String? {
        val custom = AirPlayPersistence.loadCustomBluetoothMac(context)
        return BluetoothAddressResolver.resolve(context, custom)
    }
}
