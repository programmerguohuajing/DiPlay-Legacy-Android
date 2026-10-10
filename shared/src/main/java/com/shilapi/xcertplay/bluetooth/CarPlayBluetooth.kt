package com.shilapi.xcertplay.bluetooth

import android.content.Context
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

interface CarPlayBluetooth {
    fun isSupported(): Boolean
    fun isEnabled(): Boolean
    fun enable(): Boolean
    fun getAdapterName(): String
    fun getPairedDevices(): List<CarPlayBluetoothDevice>
    fun getConnectedDevice(): CarPlayBluetoothDevice?
    fun localAddress(context: Context): String?
    fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream
}
