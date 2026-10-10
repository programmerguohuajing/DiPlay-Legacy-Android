package com.shilapi.xcertplay.bluetooth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import com.hsae.bluetoothsdk.IBluetoothCallback
import com.hsae.bluetoothsdk.IBluetoothManager
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HsaeBluetoothAdapter(
    private val context: Context
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "HsaeBluetoothAdapter"
    }

    private val adapterName = "HsaeBluetoothAdapter"
    private val lock = Any()
    private var isBinding = false
    private var service: IBluetoothManager? = null
    private var initLatch = CountDownLatch(1)

    private val callback = object : IBluetoothCallback.Stub() {
        override fun onPowerStateChanged(state: Int) {
            Log.i(TAG, "HSAE onPowerStateChanged: $state")
        }

        override fun onConnectStateChanged(profile: Int, state: Int, reason: Int) {
            Log.i(TAG, "HSAE onConnectStateChanged profile=$profile state=$state reason=$reason")
        }

        override fun onPairStateChanged(address: String?, state: Int) {
            Log.i(TAG, "HSAE onPairStateChanged address=$address state=$state")
        }

        override fun onDeviceConnectRequest(address: String?, name: String?, cod: Int, profile: Int) {}
        override fun onDevicePairRequest(address: String?, name: String?, ssp: Boolean) {}
        override fun onDeviceInquiried(address: String?, name: String?, cod: Int, rssi: Int, complete: Boolean) {}
        override fun onDeviceNameChanged(name: String?) {}
        override fun onBatterLevelChanged(level: Int) {}
        override fun onSignelLevelChanged(level: Int) {}
        override fun onBtStateChanged(state: Int, address: String?) {}
        override fun onServiceConnected() {}
        override fun onServiceDisconnected() {}
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "HSAE BluetoothService connected")
            synchronized(lock) {
                val mgr = IBluetoothManager.Stub.asInterface(binder)
                service = mgr
                runCatching {
                    mgr?.registCallback(callback)
                }
                initLatch.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "HSAE BluetoothService disconnected")
            synchronized(lock) {
                service = null
                initLatch = CountDownLatch(1)
            }
        }
    }

    init {
        if (isSupported()) {
            bindService()
        }
    }

    override fun isSupported(): Boolean {
        return runCatching {
            val pm = context.packageManager
            pm.getPackageInfo("com.hsae.bluetoothservice", 0) != null
        }.getOrDefault(false)
    }

    private fun bindService() {
        synchronized(lock) {
            if (service != null || isBinding) {
                return
            }
            isBinding = true
        }
        try {
            val intent = Intent("com.hsae.bluetoothsdk.IBluetoothManager").apply {
                setPackage("com.hsae.bluetoothservice")
            }
            val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService to HSAE BluetoothService result=$bound")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to HSAE BluetoothService", e)
            synchronized(lock) {
                isBinding = false
            }
        }
    }

    private fun ensureService(timeoutMs: Long = 1500L): IBluetoothManager? {
        synchronized(lock) {
            service?.let { return it }
            if (!isBinding) {
                bindService()
            }
        }
        try {
            initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        synchronized(lock) {
            return service
        }
    }

    override fun isEnabled(): Boolean {
        val mgr = ensureService() ?: return false
        return try {
            val status = mgr.powerStatus
            Log.i(TAG, "HSAE getPowerStatus=$status")
            status == 1
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException reading HSAE power status", e)
            false
        }
    }

    override fun enable(): Boolean {
        val mgr = ensureService() ?: return false
        return try {
            mgr.btPowerOn()
            true
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException calling HSAE btPowerOn", e)
            false
        }
    }

    override fun getAdapterName(): String = adapterName

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        val mgr = ensureService() ?: return emptyList()
        return try {
            val paired = mgr.pairedDevices ?: emptyList()
            val connectedMac = runCatching { mgr.connectDeviceAddress }.getOrNull()
            Log.i(TAG, "HSAE getPairedDevices count=${paired.size} connectedMac=$connectedMac")
            paired.map { dev ->
                val mac = dev.mac ?: ""
                val name = dev.name?.takeIf { it.isNotBlank() } ?: "iPhone"
                val isConnected = dev.status == 5 || (!connectedMac.isNullOrBlank() && connectedMac.equals(mac, ignoreCase = true))
                CarPlayBluetoothDevice(
                    name = name,
                    address = mac,
                    isConnected = isConnected
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException calling HSAE getPairedDevices", e)
            emptyList()
        }
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        val mgr = ensureService() ?: return null
        return try {
            val addr = mgr.connectDeviceAddress?.takeIf { it.isNotBlank() } ?: return null
            val name = mgr.connectDeviceName?.takeIf { it.isNotBlank() } ?: "iPhone"
            CarPlayBluetoothDevice(
                name = name,
                address = addr,
                isConnected = true
            )
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException calling HSAE getConnectedDevice", e)
            null
        }
    }

    override fun localAddress(context: Context): String? {
        val settingAddr = runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        if (settingAddr != null && isValidMac(settingAddr)) {
            return settingAddr
        }
        val globalAddr = runCatching {
            Settings.Global.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        if (globalAddr != null && isValidMac(globalAddr)) {
            return globalAddr
        }
        return null
    }

    private fun isValidMac(mac: String): Boolean {
        return Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(mac) &&
            !mac.startsWith("02:00:00:00:00:", ignoreCase = false) &&
            mac != "00:00:00:00:00:00"
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        return HsaeRfcommStream.connect(context, deviceAddress, uuid)
    }
}
