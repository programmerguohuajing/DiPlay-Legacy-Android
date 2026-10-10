package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

class HybridBluetoothAdapter(
    val hsae: HsaeBluetoothAdapter,
    val android: AndroidBluetoothAdapter
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "HybridBluetoothAdapter"
    }

    override fun isSupported(): Boolean = hsae.isSupported() || android.isSupported()

    override fun isEnabled(): Boolean {
        if (hsae.isSupported() && hsae.isEnabled()) return true
        return android.isEnabled()
    }

    override fun enable(): Boolean {
        var ok = false
        if (hsae.isSupported()) {
            ok = hsae.enable()
        }
        return android.enable() || ok
    }

    override fun getAdapterName(): String {
        return if (hsae.isSupported()) "Hybrid(HSAE+Android)" else android.getAdapterName()
    }

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        if (hsae.isSupported()) {
            val hsaeList = hsae.getPairedDevices()
            if (hsaeList.isNotEmpty()) return hsaeList
        }
        return android.getPairedDevices()
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        if (hsae.isSupported()) {
            val hsaeConn = hsae.getConnectedDevice()
            if (hsaeConn != null) return hsaeConn
        }
        return android.getConnectedDevice()
    }

    override fun localAddress(context: Context): String? {
        if (hsae.isSupported()) {
            val hsaeAddr = hsae.localAddress(context)
            if (!hsaeAddr.isNullOrBlank()) return hsaeAddr
        }
        return android.localAddress(context)
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        if (hsae.isSupported()) {
            val result = runCatching {
                hsae.openRfcommStream(deviceAddress, uuid)
            }
            val stream = result.getOrNull()
            if (stream != null) return stream
            val hsaeError = result.exceptionOrNull()!!
            Log.w(TAG, "HSAE RFCOMM stream failed, trying Android", hsaeError)
            if (android.isSupported() && android.isEnabled()) {
                return android.openRfcommStream(deviceAddress, uuid)
            }
            throw hsaeError
        }
        return android.openRfcommStream(deviceAddress, uuid)
    }
}
