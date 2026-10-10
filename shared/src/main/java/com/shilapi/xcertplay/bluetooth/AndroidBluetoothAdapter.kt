package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.compat.BluetoothCompat
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class AndroidBluetoothAdapter(
    private val context: Context
) : CarPlayBluetooth {

    private val adapterName: String = "AndroidBluetoothAdapter"

    private val adapter: BluetoothAdapter?
        get() = runCatching { BluetoothCompat.getAdapter(context) }.getOrNull()

    override fun isSupported(): Boolean = adapter != null

    override fun isEnabled(): Boolean = runCatching {
        adapter?.isEnabled == true
    }.getOrDefault(false)

    override fun enable(): Boolean = runCatching {
        val a = adapter ?: return false
        if (a.isEnabled) {
            return true
        }
        a.enable()
    }.getOrDefault(false)

    override fun getAdapterName(): String = adapterName

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        val a = adapter ?: return emptyList()
        return runCatching {
            (a.bondedDevices ?: emptySet()).map { device ->
                CarPlayBluetoothDevice(
                    name = device.name ?: "Unknown",
                    address = device.address,
                    isConnected = isDeviceConnected(device)
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        val paired = getPairedDevices()
        return paired.firstOrNull { it.isConnected }
    }

    override fun localAddress(context: Context): String? {
        return BluetoothAddressResolver.resolve(context)
    }

    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return runCatching {
            val method = BluetoothDevice::class.java.getMethod("isConnected")
            (method.invoke(device) as? Boolean) == true
        }.getOrDefault(false)
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        val a = adapter ?: throw IOException("Bluetooth adapter is unavailable")
        val device = a.getRemoteDevice(deviceAddress)
        val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
        val result = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        Thread({
            try {
                runCatching { a.cancelDiscovery() }
                socket.connect()
            } catch (t: Throwable) {
                result.set(t)
            } finally {
                latch.countDown()
            }
        }, "aosp-rfcomm-connect").apply {
            isDaemon = true
            start()
        }

        val completed = latch.await(10000L, TimeUnit.MILLISECONDS)
        if (!completed) {
            runCatching { socket.close() }
            throw IOException("Timed out connecting AOSP RFCOMM to $deviceAddress")
        }

        val err = result.get()
        if (err != null) {
            runCatching { socket.close() }
            if (err is IOException) throw err
            throw IOException("Could not connect RFCOMM", err)
        }

        return BluetoothRfcommDuplexStream(socket)
    }
}
