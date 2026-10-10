package com.shilapi.xcertplay.transport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Exact Apple USB identities allowed by the deployment configuration. */
class IphoneUsbMatcher private constructor(
    private val allowedDevices: Set<UsbDeviceId>?,
    private val allowAnyAppleProduct: Boolean,
) {
    constructor(allowedDevices: Collection<UsbDeviceId>) : this(
        allowedDevices.toSet(),
        allowAnyAppleProduct = false,
    )

    init {
        require(allowedDevices == null || allowedDevices.isNotEmpty()) {
            "At least one iPhone USB identity is required when not using Apple-vendor discovery"
        }
        require(allowedDevices == null || allowedDevices.all { it.vendorId == APPLE_VENDOR_ID }) {
            "iPhone USB identities must use Apple vendor ID 0x${APPLE_VENDOR_ID.toString(16)}"
        }
    }

    fun matches(vendorId: Int, productId: Int): Boolean =
        if (allowAnyAppleProduct) vendorId == APPLE_VENDOR_ID
        else UsbDeviceId(vendorId, productId) in allowedDevices.orEmpty()

    companion object {
        /** Apple VID used by LIVI commit 0a3dcaa0bf30d5319506d0e47c7b0d46bc942ec3. */
        const val APPLE_VENDOR_ID = 0x05ac

        /** Discovers every Apple device, matching only the vendor ID confirmed by LIVI. */
        fun appleVendor(): IphoneUsbMatcher = IphoneUsbMatcher(null, allowAnyAppleProduct = true)
    }
}

/**
 * Android USB Host bring-up boundary for a configured iPhone identity.
 *
 * LIVI's fixed commit uses Apple vendor request `0x52`, value `0`, index `4`, and then selects
 * configuration `6`. The vendor request can make the iPhone re-enumerate. Android does not offer
 * Linux sysfs configuration control or a synchronous re-enumeration primitive, so this class
 * closes the first connection and requires the caller to receive, re-authorize, and pass the new
 * [UsbDevice] to [selectCarPlayConfigurationAsync]. All opens run on the supplied executor.
 */
class IphoneUsbHost(
    context: Context,
    private val usbManager: UsbManager,
    private val matcher: IphoneUsbMatcher,
    private val permissionAction: String = "${context.packageName}.IPHONE_USB_PERMISSION",
    private val onDiagnostic: (String) -> Unit = {},
) {
    private val appContext = context.applicationContext

    sealed class PermissionRequest {
        data class AlreadyGranted(val device: UsbDevice) : PermissionRequest()
        data class Requested(val device: UsbDevice) : PermissionRequest()
    }

    sealed class PermissionResult {
        data class Granted(val device: UsbDevice) : PermissionResult()
        data class Denied(val device: UsbDevice) : PermissionResult()
    }

    sealed class TransitionResult {
        /** The connection was closed; wait for a new matching attached device before continuing. */
        data object ReenumerationRequested : TransitionResult()

        data class Failed(val error: IphoneUsbException) : TransitionResult()
    }

    sealed class Iap2SessionResult {
        data class Connected(val session: Iap2UsbSession) : Iap2SessionResult()
        data class Failed(val error: IphoneUsbException) : Iap2SessionResult()
    }

    fun discover(): List<UsbDevice> =
        usbManager.deviceList.values.filter { matcher.matches(it.vendorId, it.productId) }

    /** KitKat's UsbDevice snapshot may omit the CarPlay configuration; inspect endpoint 0 off-main. */
    internal fun inspectCarPlayConfigurationAsync(device: UsbDevice, executor: Executor,
        callback: (CarPlayUsbConfiguration?) -> Unit) {
        executor.execute {
            val configuration = try {
                val connection = usbManager.openDevice(device)
                if (connection != null) {
                    try { IphoneCarPlayConfiguration.find(device, connection) } finally { connection.close() }
                } else null
            } catch (_: Throwable) {
                null
            }
            callback(configuration)
        }
    }

    @Throws(IphoneUsbException::class)
    fun requestPermission(device: UsbDevice): PermissionRequest {
        requireConfiguredDevice(device)
        if (usbManager.hasPermission(device)) return PermissionRequest.AlreadyGranted(device)

        usbManager.requestPermission(device, permissionPendingIntent())
        return PermissionRequest.Requested(device)
    }

    /** Returns null for unrelated broadcasts, malformed results, or non-configured devices. */
    fun parsePermissionResult(intent: Intent): PermissionResult? {
        if (intent.action != permissionAction) return null
        val device = intent.usbDevice() ?: return null
        if (!matcher.matches(device.vendorId, device.productId)) return null
        return if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            PermissionResult.Granted(device)
        } else {
            PermissionResult.Denied(device)
        }
    }

    /** Returns the new matching device after the vendor request caused Android USB re-enumeration. */
    fun parseAttachedDevice(intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        val device = intent.usbDevice() ?: return null
        return device.takeIf { matcher.matches(it.vendorId, it.productId) }
    }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerPermissionReceiver(onResult: (PermissionResult) -> Unit): Closeable =
        registerReceiver(IntentFilter(permissionAction)) { parsePermissionResult(it)?.let(onResult) }

    /** Register once for this host instance and close the returned handle to unregister it. */
    fun registerAttachReceiver(onAttached: (UsbDevice) -> Unit): Closeable =
        registerReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
            parseAttachedDevice(it)?.let(onAttached)
        }

    /**
     * Sends the LIVI-evidenced vendor request then closes the connection before re-enumeration.
     * The callback is invoked from [executor].
     */
    fun requestCarPlayReenumerationAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (TransitionResult) -> Unit,
    ) {
        executor.execute {
            callback(runTransition(device) { connection ->
                val response = ByteArray(VENDOR_RESPONSE_LENGTH)
                var lastTransferred = -1
                var success = false
                val maxAttempts = 3
                for (attempt in 1..maxAttempts) {
                    lastTransferred = connection.controlTransfer(
                        USB_VENDOR_DEVICE_IN,
                        CARPLAY_CONFIGURATION_REQUEST,
                        0,
                        CARPLAY_CONFIGURATION_INDEX,
                        response,
                        response.size,
                        CONTROL_TRANSFER_TIMEOUT_MILLIS,
                    )
                    if (lastTransferred == response.size) {
                        success = true
                        break
                    }
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(200L * attempt)
                        } catch (ignored: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
                if (!success) {
                    throw IphoneUsbException.Protocol(
                        "CarPlay configuration request transferred $lastTransferred of ${response.size} bytes (after $maxAttempts attempts)",
                    )
                }
                TransitionResult.ReenumerationRequested
            })
        }
    }

    /**
     * Opens LIVI's USBMUX bulk pipe on the re-enumerated iPhone.
     *
     * This repeats CarPlay configuration selection on the newly opened Android connection and
     * claims the Apple USB Multiplexor interface, preferring the LIVI bulk pair 0x04/0x85.
     * After a successful callback, it owns the returned session and must close it. If the callback
     * throws, this method closes the session before propagating the callback failure.
     */
    fun openIap2UsbSessionAsync(
        device: UsbDevice,
        executor: Executor,
        callback: (Iap2SessionResult) -> Unit,
    ) {
        executor.execute {
            val result = try {
                Iap2SessionResult.Connected(openIap2UsbSession(device))
            } catch (error: IphoneUsbException) {
                Iap2SessionResult.Failed(error)
            } catch (error: SecurityException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.PermissionDenied("USB permission was denied", error),
                )
            } catch (error: LinkageError) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("Native USB library linkage failed", error),
                )
            } catch (error: RuntimeException) {
                Iap2SessionResult.Failed(
                    IphoneUsbException.DeviceUnavailable("iPhone USBMUX operation failed", error),
                )
            }
            try {
                callback(result)
            } catch (error: Throwable) {
                if (result is Iap2SessionResult.Connected) {
                    try {
                        result.session.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }
    }

    private fun runTransition(
        device: UsbDevice,
        operation: (UsbDeviceConnection) -> TransitionResult,
    ): TransitionResult = try {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        try {
            operation(connection)
        } finally {
            connection.close()
        }
    } catch (error: IphoneUsbException) {
        TransitionResult.Failed(error)
    } catch (error: SecurityException) {
        TransitionResult.Failed(IphoneUsbException.PermissionDenied("USB permission was denied", error))
    } catch (error: LinkageError) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("Native USB library linkage failed", error))
    } catch (error: RuntimeException) {
        TransitionResult.Failed(IphoneUsbException.DeviceUnavailable("iPhone USB operation failed", error))
    }

    private fun openIap2UsbSession(device: UsbDevice): Iap2UsbSession {
        requireConfiguredDevice(device)
        if (!usbManager.hasPermission(device)) {
            throw IphoneUsbException.PermissionDenied("USB permission has not been granted")
        }
        val connection = usbManager.openDevice(device)
            ?: throw IphoneUsbException.DeviceUnavailable("UsbManager could not open the iPhone")
        val sharedConnection = SharedUsbDeviceConnection.own(connection)
        var claimedInterface: UsbInterface? = null
        try {
            val configuration = IphoneCarPlayConfiguration.find(device, connection)
                ?: throw IphoneUsbException.Protocol(
                    "Re-enumerated iPhone exposes no USBMUX CarPlay configuration",
                )
            diagnosticLog(
                "usb open sdk=${Build.VERSION.SDK_INT} vid=${device.vendorId} pid=${device.productId} " +
                    "target=${configuration.id} interfaces=${IphoneCarPlayConfiguration.describe(configuration)}",
            )
            val selected = LegacyUsbHostCompat.selectConfiguration(
                connection, configuration, onDiagnostic = ::diagnosticLog,
            )
            diagnosticLog(
                "setConfiguration target=${configuration.id} ok=${selected.selected} " +
                    "active=${selected.activeConfiguration ?: "unknown"} errno=${selected.errno}",
            )
            if (!selected.selected) {
                throw IphoneUsbException.DeviceUnavailable(
                    "USB CarPlay configuration ${configuration.id} could not be selected; " +
                        "activeConfig=${selected.activeConfiguration ?: "unknown"}" +
                        (selected.errno?.let { " (usbfs errno $it)" } ?: ""),
                )
            }

            val usbMux = IphoneCarPlayConfiguration.usbMuxInterface(configuration)
                ?: throw IphoneUsbException.Protocol("CarPlay configuration exposes no USBMUX interface")
            val endpoints = IphoneCarPlayConfiguration.usbMuxEndpoints(usbMux)
                ?: throw IphoneUsbException.Protocol("USBMUX interface exposes no bulk endpoint pair")
            diagnosticLog(
                "usbmux iface=${usbMux.id} alt=${IphoneCarPlayConfiguration.alternateSetting(usbMux)} " +
                    "out=0x${endpoints.first.address.toString(16)} " +
                    "in=0x${endpoints.second.address.toString(16)}",
            )
            val muxClaim = LegacyUsbHostCompat.claim(connection, usbMux)
            val activeAfterClaim = LegacyUsbHostCompat.activeConfiguration(connection)
            diagnosticLog(
                "usb mux claim iface=${usbMux.id} ok=${muxClaim.claimed} errno=${muxClaim.errno} " +
                    "target=${configuration.id} active=${activeAfterClaim ?: "unknown"}",
            )
            if (!muxClaim.claimed) {
                throw IphoneUsbException.DeviceUnavailable(
                    "Android could not claim USBMUX interface ${usbMux.id}" +
                        (muxClaim.errno?.let { " (usbfs errno $it)" } ?: "") +
                        " targetConfig=${configuration.id} activeConfig=${activeAfterClaim ?: "unknown"}",
                )
            }
            claimedInterface = usbMux
            return Iap2UsbSession(
                sharedConnection = sharedConnection,
                claimedInterface = usbMux,
                outEndpoint = endpoints.first,
                inEndpoint = endpoints.second,
                onDiagnostic = onDiagnostic,
                carPlayConfiguration = configuration,
            )
        } catch (error: Throwable) {
            if (claimedInterface != null) {
                runCatching { connection.releaseInterface(claimedInterface) }
            }
            sharedConnection.release()
            throw error
        }
    }

    private fun diagnosticLog(message: String) {
        Log.i(IphoneCarPlayConfiguration.TAG, message)
        runCatching { onDiagnostic(message) }
    }

    private fun requireConfiguredDevice(device: UsbDevice) {
        if (!matcher.matches(device.vendorId, device.productId)) {
            throw IphoneUsbException.DeviceUnavailable("USB device is not a configured iPhone identity")
        }
    }

    private fun permissionPendingIntent(): PendingIntent {
        val intent = Intent(permissionAction).setPackage(appContext.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            flags,
        )
    }

    private fun registerReceiver(filter: IntentFilter, onReceive: (Intent) -> Unit): Closeable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    onReceive(intent)
                } catch (error: Throwable) {
                    Log.w(IphoneCarPlayConfiguration.TAG, "USB broadcast receiver handling failed", error)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        val registered = AtomicBoolean(true)
        return Closeable {
            if (registered.compareAndSet(true, false)) appContext.unregisterReceiver(receiver)
        }
    }

    private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    companion object {
        private const val USB_VENDOR_DEVICE_IN = 0xc0
        private const val CARPLAY_CONFIGURATION_REQUEST = 0x52
        private const val CARPLAY_CONFIGURATION_INDEX = 0x0004
        private const val VENDOR_RESPONSE_LENGTH = 1
        private const val CONTROL_TRANSFER_TIMEOUT_MILLIS = 1_000
    }

}

/**
 * A blocking, full-duplex USBMUX pipe. A null [read] result means only that its timeout elapsed.
 *
 * All operations must run off the Android main thread. The session does not parse iAP2 frames.
 */
class Iap2UsbSession internal constructor(
    private val sharedConnection: SharedUsbDeviceConnection,
    private val claimedInterface: UsbInterface,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val onDiagnostic: (String) -> Unit = {},
    internal val carPlayConfiguration: CarPlayUsbConfiguration? = null,
) : Closeable {
    private val connection: UsbDeviceConnection
        get() = sharedConnection.connection

    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val directReadBuffer = ByteBuffer.allocateDirect(
        usbTransferSize(Build.VERSION.SDK_INT, USBMUX_READ_CHUNK_BYTES)
    )
    private val readQueuePolicy = UsbReadQueuePolicy(
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) USBFS_BULK_URB_CEILING_BYTES else Int.MAX_VALUE,
    )

    fun write(data: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (data.isEmpty()) return@synchronized
        val transferred = writeUsbChunks(data.size, Build.VERSION.SDK_INT, timeoutMillis) { offset, count, timeout ->
            connection.bulkTransfer(outEndpoint, data, offset, count, timeout)
        }
        if (transferred != data.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "USBMUX write transferred $transferred of ${data.size} bytes",
            )
        }
    }

    /** Returns null only when no completed USB request arrives before [timeoutMillis]. */
    fun read(timeoutMillis: Long): ByteArray? = synchronized(readLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession(
                            "Android could not initialize USBMUX read request (${requestDiagnostics(timeoutMillis)})",
                        )
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queueResult = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) {
                        sharedConnection.queue(current, it)
                    }
                    if (!queueResult.queued) {
                        throw failSession(
                            "Android could not queue USBMUX read request (${requestDiagnostics(timeoutMillis)} " +
                                "firstBytes=${queueResult.firstBytes} fallbackBytes=${queueResult.fallbackBytes ?: "not_attempted"})",
                        )
                    }
                    readQueued = true
                    if (queueResult.fallbackBytes != null) acceptedFallback = queueResult
                }
                current
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }

        acceptedFallback?.let { queueResult ->
            runCatching {
                onDiagnostic(
                    "USBMUX read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queueResult.firstBytes} " +
                        "fallbackBytes=${queueResult.fallbackBytes}",
                )
            }
        }

        try {
            val completed = try {
                sharedConnection.await(request, timeoutMillis)
            } catch (_: TimeoutException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    drainCancelledRead(request)
                    readQueued = false
                }
                // Below Android 8 (API 26), request stays queued for the next poll because
                // cancel() is unreliable on legacy usbfs drivers and closing an in-flight
                // request causes SIGSEGV (Android 4.4) or NPE in dequeue (Android 5.1).
                return@synchronized null
            }
            if (completed !== request) {
                throw failSession("Android completed an unexpected USB request")
            }
            readQueued = false
            val position = directReadBuffer.position()
            if (position <= 0) return@synchronized null
            return@synchronized ByteArray(position).also {
                directReadBuffer.flip()
                directReadBuffer.get(it)
            }
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("USBMUX read failed", error)
        }
    }

    /**
     * Retains the already-authorized iPhone fd for NCM. Some Android 7/T3 USB host
     * stacks cannot claim another interface of the same composite device from a
     * second openDevice() fd (the usbfs fallback reports ENOENT). Sharing this fd
     * also matches the ownership model of SharedUsbDeviceConnection.
     */
    internal fun retainConnectionForNcm(): SharedUsbDeviceConnection = synchronized(stateLock) {
        checkOpenLocked()
        sharedConnection.retain()
    }

    override fun close() {
        val requestToCancel = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        runCatching { requestToCancel?.cancel() }
        runCatching { connection.releaseInterface(claimedInterface) }
        sharedConnection.release()
        requestToCancel?.let(sharedConnection::forget)
        runCatching { requestToCancel?.close() }
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("USBMUX session is closed")
    }

    private fun drainCancelledRead(request: UsbRequest) {
        if (!request.cancel()) {
            throw failSession("Android could not cancel timed out USBMUX read request")
        }
        val completed = try {
            sharedConnection.await(request, CANCEL_DRAIN_TIMEOUT_MILLIS)
        } catch (_: TimeoutException) {
            throw failSession("Timed out draining cancelled USBMUX read request")
        }
        if (completed !== request) {
            throw failSession("Android did not drain the cancelled USBMUX read request")
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun requestDiagnostics(timeoutMillis: Long): String = buildString {
        append("api=").append(Build.VERSION.SDK_INT)
        append(" endpoint=").append(describeUsbEndpoint(inEndpoint))
        append(" timeoutMs=").append(timeoutMillis)
    }

    private companion object {
        const val USBMUX_READ_CHUNK_BYTES = 65_536
        const val CANCEL_DRAIN_TIMEOUT_MILLIS = 1_000L
    }
}

internal fun describeUsbEndpoint(endpoint: UsbEndpoint): String =
    "0x${endpoint.address.toString(16)}(direction=${endpoint.direction}," +
        "type=${endpoint.type},maxPacket=${endpoint.maxPacketSize})"

/** USB bring-up failures that precede iAP2 and are distinct from MFi I2C failures. */
sealed class IphoneUsbException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class PermissionDenied(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class DeviceUnavailable(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class TimedOut(message: String, cause: Throwable? = null) : IphoneUsbException(message, cause)
    class Protocol(message: String) : IphoneUsbException(message)
}
