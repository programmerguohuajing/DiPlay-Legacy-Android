package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Old Android USB-host kernels sometimes leave a class driver attached even when
 * UsbDeviceConnection.claimInterface(..., true) is requested. Fall back to usbfs on Android 9
 * and older, using the already-authorized device fd returned by UsbManager.
 */
internal object LegacyUsbHostCompat {
    private const val USBFS_EBUSY = 16
    data class ConfigurationResult(
        val selected: Boolean,
        val errno: Int?,
        val activeConfiguration: Int?,
    )
    data class ClaimResult(val claimed: Boolean, val errno: Int?)
    data class SelectResult(val selected: Boolean, val errno: Int?)

    fun activeConfiguration(connection: UsbDeviceConnection): Int? {
        val value = ByteArray(1)
        val read = runCatching {
            connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_CONFIGURATION,
                0,
                0,
                value,
                value.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
        }.getOrDefault(-1)
        return if (read == 1) value[0].toInt() and 0xff else null
    }

    fun selectConfiguration(
        connection: UsbDeviceConnection,
        configuration: CarPlayUsbConfiguration,
        onDiagnostic: (String) -> Unit = {},
        nativeSelect: (Int, Int) -> Int = { fd, target -> LegacyUsbHostNative.setConfiguration(fd, target) },
    ): ConfigurationResult {
        val target = configuration.id
        val legacy = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        val before = activeConfiguration(connection)
        if (!legacy && before == target) {
            return ConfigurationResult(true, null, before)
        }

        // On a fresh legacy connection, GET_CONFIGURATION only describes the phone.
        // As in the Android-8 reference APK, always ask the host kernel to select it
        // before claiming interfaces. A matching phone value cannot mask a failed ioctl.
        val frameworkSelected = runCatching {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                selectUsbConfiguration(connection, configuration)
        }.getOrDefault(false)
        SystemClock.sleep(CONFIG_SETTLE_MILLIS)
        val frameworkActive = activeConfiguration(connection)
        onDiagnostic(
            "usb configuration target=$target before=${before ?: "unknown"} " +
                "frameworkOk=$frameworkSelected after=${frameworkActive ?: "unknown"}",
        )
        if (frameworkActive == target && (frameworkSelected || !legacy)) {
            return ConfigurationResult(true, null, frameworkActive)
        }
        if (frameworkSelected && frameworkActive == null && Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            return ConfigurationResult(true, null, null)
        }

        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            return ConfigurationResult(false, null, activeConfiguration(connection))
        }

        // A raw control SET_CONFIGURATION updates the phone but not Linux's active interface
        // table. Use the usbfs configuration ioctl so NCM claims see the new interfaces too.
        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) return ConfigurationResult(false, null, activeConfiguration(connection))
        var errno = try {
            nativeSelect(fd, target)
        } catch (error: LinkageError) {
            Log.w(TAG, "usbfs set-configuration unavailable config=$target", error)
            return ConfigurationResult(false, null, activeConfiguration(connection))
        }
        // EBUSY can be transient while a vendor USB host driver finishes releasing
        // the prior interface table. Retry once; do not treat GET_CONFIGURATION == target
        // as proof of success, because that readback describes the phone, not the kernel.
        if (errno == USBFS_EBUSY) {
            onDiagnostic("usb configuration busy target=$target retrying once after settle")
            SystemClock.sleep(CONFIG_SETTLE_MILLIS)
            errno = try {
                nativeSelect(fd, target)
            } catch (error: LinkageError) {
                Log.w(TAG, "usbfs set-configuration retry unavailable config=$target", error)
                return ConfigurationResult(false, null, activeConfiguration(connection))
            }
            onDiagnostic("usb configuration retry target=$target errno=$errno")
        }
        SystemClock.sleep(CONFIG_SETTLE_MILLIS)
        val active = activeConfiguration(connection)
        Log.i(TAG, "usbfs set-configuration config=$target errno=$errno active=${active ?: "unknown"}")
        onDiagnostic("usb configuration native target=$target errno=$errno after=${active ?: "unknown"}")
        return ConfigurationResult(errno == 0 && (active == null || active == target), errno, active)
    }

    fun claim(connection: UsbDeviceConnection, usbInterface: UsbInterface): ClaimResult {
        if (connection.claimInterface(usbInterface, true)) return ClaimResult(true, null)
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return ClaimResult(false, null)

        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) {
            Log.w(TAG, "usbfs fallback unavailable: invalid device fd for iface=${usbInterface.id}")
            return ClaimResult(false, null)
        }

        val errno = try {
            LegacyUsbHostNative.forceClaim(fd, usbInterface.id)
        } catch (error: LinkageError) {
            Log.w(TAG, "usbfs fallback unavailable for iface=${usbInterface.id}", error)
            return ClaimResult(false, null)
        }
        Log.i(TAG, "usbfs force-claim iface=${usbInterface.id} errno=$errno")
        return ClaimResult(errno == 0, errno)
    }

    fun select(connection: UsbDeviceConnection, usbInterface: UsbInterface): SelectResult {
        if (Build.VERSION.SDK_INT >= 21 && connection.setInterface(usbInterface)) return SelectResult(true, null)
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return SelectResult(false, null)

        val fd = runCatching { connection.fileDescriptor }.getOrDefault(-1)
        if (fd < 0) {
            Log.w(TAG, "usbfs set-interface unavailable: invalid fd for iface=${usbInterface.id}")
            return SelectResult(false, null)
        }
        val alt = IphoneCarPlayConfiguration.alternateSetting(usbInterface)
        val errno = try {
            LegacyUsbHostNative.setInterface(fd, usbInterface.id, alt)
        } catch (error: LinkageError) {
            Log.w(TAG, "usbfs set-interface unavailable for iface=${usbInterface.id}/$alt", error)
            return SelectResult(false, null)
        }
        Log.i(TAG, "usbfs set-interface iface=${usbInterface.id}/$alt errno=$errno")
        return SelectResult(errno == 0, errno)
    }

    private const val TAG = "xcertplay-usb"
    private const val USB_REQUEST_GET_CONFIGURATION = 8
    private const val USB_CONTROL_TIMEOUT_MILLIS = 2_000
    private const val CONFIG_SETTLE_MILLIS = 150L
}

private object LegacyUsbHostNative {
    init { System.loadLibrary("usb_host_compat") }
    external fun setConfiguration(fileDescriptor: Int, configurationId: Int): Int
    external fun forceClaim(fileDescriptor: Int, interfaceId: Int): Int
    external fun setInterface(fileDescriptor: Int, interfaceId: Int, alternateSetting: Int): Int
}
