package com.shilapi.xcertplay.bluetooth

data class CarPlayBluetoothDevice(
    val name: String,
    val address: String,
    val isConnected: Boolean = false
)
