package com.shilapi.xcertplay.compat

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build

/**
 * Compatibility helper for Bluetooth APIs across Android versions.
 *
 * Isolates [BluetoothManager] and [BluetoothProfile.GATT] to prevent Dalvik
 * class verification errors (NoClassDefFoundError) on legacy platforms like Android 4.2.2 (API 17).
 */
object BluetoothCompat {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun getAdapter(context: Context): BluetoothAdapter? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            BluetoothCompatApi18.getAdapter(context)
        } else {
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    @SuppressLint("MissingPermission")
    fun getConnectedGattDevices(context: Context): List<BluetoothDevice> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            BluetoothCompatApi18.getConnectedGattDevices(context)
        } else {
            emptyList()
        }
    }
}

@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
private object BluetoothCompatApi18 {
    @SuppressLint("MissingPermission")
    fun getAdapter(context: Context): BluetoothAdapter? {
        val bm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.getSystemService(BluetoothManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        }
        return bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    @SuppressLint("MissingPermission")
    fun getConnectedGattDevices(context: Context): List<BluetoothDevice> {
        return runCatching {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bm?.getConnectedDevices(BluetoothProfile.GATT).orEmpty()
        }.getOrDefault(emptyList())
    }
}
