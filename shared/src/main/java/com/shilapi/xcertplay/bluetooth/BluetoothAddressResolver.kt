package com.shilapi.xcertplay.bluetooth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.shilapi.xcertplay.compat.BluetoothCompat
import java.io.File
import java.net.NetworkInterface
import java.util.Locale

/**
 * Robust multi-source resolver for the local head-unit Bluetooth hardware MAC address.
 *
 * On legacy Android car stereos (Android 4.4 - 5.1+, e.g. Intel SoFIA 3GR, Allwinner T3,
 * MediaTek, Rockchip, FYT), standard Android APIs often return `02:00:00:00:00:00`, `null`,
 * or throw security exceptions when Bluetooth is managed by an external MCU or vendor service.
 *
 * This resolver queries:
 * 1. User manual override (if provided).
 * 2. Hidden reflection on `BluetoothAdapter.mService.getAddress()`.
 * 3. Standard `BluetoothAdapter.address`.
 * 4. `Settings.Secure` and `Settings.System` ("bluetooth_address").
 * 5. System properties (`persist.sys.bluetooth.address`, `ro.bt.bdaddr_path`, etc.).
 * 6. Filesystem BDADDR paths (from `ro.bt.bdaddr_path`, `/data/misc/bluetooth/bdaddr`, etc.).
 * 7. Network interfaces (`bt-pan`, `bnep0`, `hci0`).
 */
object BluetoothAddressResolver {

    private val MAC_PATTERN = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val RAW_12_HEX_PATTERN = Regex("^[0-9A-Fa-f]{12}$")
    private const val PLACEHOLDER_PREFIX = "02:00:00:00:00:"
    private const val ZERO_MAC = "00:00:00:00:00:00"
    private const val BROADCAST_MAC = "FF:FF:FF:FF:FF:FF"

    private val SYSTEM_PROPERTIES = listOf(
        "persist.sys.bluetooth.address",
        "persist.service.bdroid.bdaddr",
        "persist.bt.mac",
        "ro.boot.btmacaddr",
        "sys.bt.mac",
        "persist.vendor.service.bdroid.bdaddr",
        "vendor.bluetooth.bdaddr",
        "ro.bluetooth.address",
        "net.bt.name",
    )

    private val CANDIDATE_FILES = listOf(
        "/data/misc/bluetooth/bdaddr",
        "/sys/class/bluetooth/hci0/address",
        "/persist/bluetooth/.bdaddr",
        "/efs/bluetooth/bt_addr",
        "/etc/bluetooth/bdaddr",
        "/system/etc/bluetooth/bdaddr",
        "/factory/bluetooth_address",
    )

    fun normalize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()

        val candidate = when {
            trimmed.contains(':') || trimmed.contains('-') -> {
                trimmed.replace('-', ':').uppercase(Locale.US)
            }
            RAW_12_HEX_PATTERN.matches(trimmed) -> {
                buildString(17) {
                    for (i in 0 until 12 step 2) {
                        if (i > 0) append(':')
                        append(trimmed.substring(i, i + 2).uppercase(Locale.US))
                    }
                }
            }
            else -> return null
        }

        if (!MAC_PATTERN.matches(candidate)) return null
        if (candidate.startsWith(PLACEHOLDER_PREFIX, ignoreCase = true)) return null
        if (candidate.equals(ZERO_MAC, ignoreCase = true)) return null
        if (candidate.equals(BROADCAST_MAC, ignoreCase = true)) return null

        return candidate
    }

    fun isValidMac(mac: String?): Boolean = normalize(mac) != null

    @SuppressLint("MissingPermission")
    fun resolve(context: Context, customOverride: String? = null): String? {
        normalize(customOverride)?.let { return it }

        // 1. Reflection on BluetoothAdapter.mService.getAddress()
        val reflectionAddr = runCatching {
            val adapter = BluetoothCompat.getAdapter(context) ?: return@runCatching null
            val mServiceField = adapter.javaClass.getDeclaredField("mService").apply { isAccessible = true }
            val mService = mServiceField.get(adapter) ?: return@runCatching null
            val getAddressMethod = mService.javaClass.getMethod("getAddress")
            normalize(getAddressMethod.invoke(mService) as? String)
        }.getOrNull()
        if (reflectionAddr != null) return reflectionAddr

        // 2. BluetoothCompat adapter address
        val adapterAddr = runCatching {
            normalize(BluetoothCompat.getAdapter(context)?.address)
        }.getOrNull()
        if (adapterAddr != null) return adapterAddr

        // 3. Settings.Secure / Settings.System
        val settingsAddr = runCatching {
            normalize(Settings.Secure.getString(context.contentResolver, "bluetooth_address"))
                ?: normalize(Settings.System.getString(context.contentResolver, "bluetooth_address"))
        }.getOrNull()
        if (settingsAddr != null) return settingsAddr

        // 4. System properties
        val propAddr = runCatching {
            val getMethod = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            for (key in SYSTEM_PROPERTIES) {
                val value = getMethod.invoke(null, key) as? String
                val normalized = normalize(value)
                if (normalized != null) return@runCatching normalized
            }
            null
        }.getOrNull()
        if (propAddr != null) return propAddr

        // 5. Filesystem BDADDR paths
        val bdaddrPath = runCatching {
            val getMethod = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            getMethod.invoke(null, "ro.bt.bdaddr_path") as? String
        }.getOrNull()

        val fileCandidates = if (!bdaddrPath.isNullOrBlank()) {
            listOf(bdaddrPath) + CANDIDATE_FILES
        } else {
            CANDIDATE_FILES
        }

        for (filePath in fileCandidates) {
            val fileMac = runCatching {
                val file = File(filePath)
                if (file.isFile && file.canRead()) {
                    file.readText().trim()
                } else null
            }.getOrNull()
            val normalized = normalize(fileMac)
            if (normalized != null) return normalized
        }

        // 6. Network interfaces
        val ifaceMac = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.name.startsWith("bt") || it.name.startsWith("bnep") || it.name.startsWith("hci") }
                .mapNotNull { it.hardwareAddress?.joinToString(":") { b -> "%02X".format(b) } }
                .firstNotNullOfOrNull(::normalize)
        }.getOrNull()
        if (ifaceMac != null) return ifaceMac

        return null
    }
}
