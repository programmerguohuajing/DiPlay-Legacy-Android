package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val sharedConnection: SharedUsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    private val useAsyncRead: Boolean,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
    private val legacyRestore: (() -> Unit)? = null,
) : Closeable {
    private val connection: UsbDeviceConnection
        get() = sharedConnection.connection
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private var inboundFrameSeen = false
    private var consecutiveWriteFailures = 0
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private var optionalShortPacketPad = false
    // Use the actual IN endpoint packet size; some head units expose 64-byte full-speed USB.
    private val usbPacketSize = inEndpoint.maxPacketSize.takeIf { it > 0 } ?: USB_PACKET_SIZE
    private val resyncTimes = ArrayDeque<Long>()
    private var resyncCount = 0
    private var resyncReason: String? = null
    private var resyncSkipped = 0
    private val readBuffer = ByteArray(READ_CHUNK_BYTES)
    // Bulk IN uses one persistent async request: bulkTransfer() pins its byte[] in a JNI critical
    // section for the whole wait, which blocks ART's GC thread flip and, with it, every other USB
    // transfer (seen as ~0.8 s stalls of video and audio). A timed-out request stays queued, so no
    // data is lost between calls. SharedUsbDeviceConnection routes USBMUX/NCM completions.
    private val directReadBuffer = ByteBuffer.allocateDirect(usbTransferSize(Build.VERSION.SDK_INT, READ_CHUNK_BYTES))
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy(
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) USBFS_BULK_URB_CEILING_BYTES else Int.MAX_VALUE,
    )
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        val transferred = writeUsbChunks(block.size, Build.VERSION.SDK_INT, timeoutMillis) { offset, count, timeout ->
            connection.bulkTransfer(outEndpoint, block, offset, count, timeout)
        }
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        if (transferred <= 0) {
            if (!inboundFrameSeen) {
                if (!loggedWriteTimeout) {
                    loggedWriteTimeout = true
                    Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
                }
                return@synchronized
            }
            // The phone only sends once its data path is up, so from here a run of failures means bulk
            // OUT alone is gone: inbound video keeps arriving while touch, return audio and TCP ACKs
            // are dropped, and the user watches a frozen screen with no error. Android reports a NAK,
            // a timeout and a latched endpoint halt with the same failed result, and a halt stays
            // latched, so try to release one before counting this as a fault.
            clearOutEndpointHalt()
            consecutiveWriteFailures += 1
            Log.i(
                IphoneCarPlayConfiguration.TAG,
                "ncm bulk-out failed $consecutiveWriteFailures/$MAX_CONSECUTIVE_WRITE_FAILURES while the data path was live",
            )
            if (consecutiveWriteFailures >= MAX_CONSECUTIVE_WRITE_FAILURES) {
                // USBMUX tears its whole session down for the same class of failure.
                throw failSession(
                    "NCM write failed $consecutiveWriteFailures times while the data path was live",
                )
            }
            return@synchronized
        }
        if (transferred != block.size) {
            // The phone holds a truncated NTB16 block, so this frame stream cannot be resumed in
            // place; record the failure so the read side stops alongside this writer.
            throw failSession("NCM write transferred $transferred of ${block.size} bytes")
        }
        consecutiveWriteFailures = 0
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        val requestToClose = synchronized(stateLock) {
            if (closed) return
            closed = true
            readRequest
        }
        // Wakes a reader blocked in requestWait(); it then observes the closed state.
        runCatching { requestToClose?.cancel() }
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        runCatching { legacyRestore?.invoke() }
        sharedConnection.release()
        requestToClose?.let(sharedConnection::forget)
        runCatching { requestToClose?.close() }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) {
                try {
                    Thread.sleep(STATUS_POLL_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            // wBlockLength describes the complete NTB. A packet-aligned USB transfer may end
            // with a ZLP (no byte in the buffer), or carry one zero byte to force a short packet.
            // Do not wait for that optional byte or consume the next NTB's header as padding.
            // Keep this state when the pad/header arrives in a later USB completion.
            if (optionalShortPacketPad && bufferedSize > 0) {
                optionalShortPacketPad = false
                when (buffered[0].toInt() and 0xff) {
                    0 -> discard(1)
                    Ntb16Codec.NTH16_SIG and 0xff -> Unit // The next header is validated below.
                    else -> if (!resynchronize("Invalid NTB16 short-packet pad")) return
                }
            }
            if (bufferedSize < NTH16_LENGTH) return
            val blockLength = readU16(buffered, 8)
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                if (!resynchronize("NCM read buffer does not begin with an NTB16 header")) return
                continue
            }
            if (blockLength < 28) {
                if (!resynchronize("Invalid NTB16 block length $blockLength")) return
                continue
            }
            if (bufferedSize < blockLength) return
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            discard(blockLength)
            optionalShortPacketPad = blockLength % usbPacketSize == 0
            finishResync()
        }
    }

    /**
     * Android reaps a bulk transfer that failed part way (babble, CRC, a dropped packet) as a
     * normal completion with whatever bytes arrived, so a damaged NTB can leave the stream
     * misaligned. Like Linux cdc_ncm, drop the damaged bytes and continue at the next valid NTB16
     * header; TCP resends what was lost. Returns false while no header is buffered yet. Damage
     * that keeps recurring still fails the session, as before.
     */
    private fun resynchronize(reason: String): Boolean {
        if (resyncReason == null) {
            val now = System.nanoTime()
            while (resyncTimes.isNotEmpty() && now - resyncTimes.first() > RESYNC_WINDOW_NANOS) resyncTimes.removeFirst()
            if (resyncTimes.size >= MAX_RESYNCS_PER_WINDOW) throw failSession("$reason (repeated)")
            resyncTimes.addLast(now)
            resyncCount++
            resyncReason = reason
            resyncSkipped = 0
        }
        val next = (1..bufferedSize - NTH16_LENGTH).firstOrNull(::plausibleHeaderAt)
        // Without a header, keep the bytes that could still be the start of one.
        val skipped = next ?: (bufferedSize - (NTH16_LENGTH - 1)).coerceAtLeast(0)
        discard(skipped)
        resyncSkipped += skipped
        return next != null
    }

    private fun finishResync() {
        val reason = resyncReason ?: return
        resyncReason = null
        if (resyncCount <= LOGGED_RESYNCS || resyncCount % 50 == 0) {
            runCatching { onDiagnostic("NCM resynchronized after $reason; skipped=$resyncSkipped resyncs=$resyncCount") }
        }
    }

    /** Stricter than the normal path, so payload bytes are unlikely to pass as a header. */
    private fun plausibleHeaderAt(offset: Int): Boolean {
        if (readU32(buffered, offset) != Ntb16Codec.NTH16_SIG) return false
        val headerLength = readU16(buffered, offset + 4)
        val blockLength = readU16(buffered, offset + 8)
        val ndpIndex = readU16(buffered, offset + 10)
        return headerLength == NTH16_LENGTH && blockLength >= 28 &&
            ndpIndex >= NTH16_LENGTH && ndpIndex % 4 == 0 && ndpIndex + 8 <= blockLength
    }

    private fun discard(count: Int) {
        if (count <= 0) return
        buffered.copyInto(buffered, 0, count, bufferedSize)
        bufferedSize -= count
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        // Marks the point the phone's data path is up; the write side keys its failure policy off it.
        inboundFrameSeen = true
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        if (!useAsyncRead) return readChunkSynchronously(timeoutMillis)
        checkOpen()
        var acceptedFallback: UsbReadQueueResult? = null
        val request = try {
            // Publish and queue atomically with close(), so detach cannot miss a new request.
            synchronized(stateLock) {
                checkOpenLocked()
                val current = readRequest ?: UsbRequest().also {
                    if (!it.initialize(connection, inEndpoint)) {
                        it.close()
                        throw failSession("Android could not initialize the NCM read request")
                    }
                    readRequest = it
                }
                if (!readQueued) {
                    directReadBuffer.clear()
                    val queued = readQueuePolicy.queue(directReadBuffer, ::checkOpenLocked) {
                        sharedConnection.queue(current, it)
                    }
                    if (!queued.queued) throw failSession(
                        "Android could not queue the NCM read request (api=${Build.VERSION.SDK_INT} " +
                            "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                            "fallbackBytes=${queued.fallbackBytes ?: "not_attempted"})",
                    )
                    readQueued = true
                    if (queued.fallbackBytes != null) acceptedFallback = queued
                }
                current
            }
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Emit outside stateLock; diagnostic callbacks must not affect queue or close behavior.
        acceptedFallback?.let { queued ->
            runCatching {
                onDiagnostic(
                    "NCM read queue compatibility fallback api=${Build.VERSION.SDK_INT} " +
                        "endpoint=${describeUsbEndpoint(inEndpoint)} firstBytes=${queued.firstBytes} " +
                        "fallbackBytes=${queued.fallbackBytes}",
                )
            }
        }
        try {
            val completed = try {
                sharedConnection.await(request, timeoutMillis)
            } catch (_: TimeoutException) {
                // Nothing arrived yet; the request stays queued for the next call. USBMUX owns
                // authoritative detach/failure detection for the same phone.
                return null
            }
            if (completed !== request) throw failSession("Android completed an unexpected NCM request")
            readQueued = false
            val transferred = directReadBuffer.position()
            if (transferred <= 0) return null
            directReadBuffer.flip()
            directReadBuffer.get(readBuffer, 0, transferred)
            return transferred
        } catch (error: IphoneUsbException) {
            throw error
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
    }

    private fun readChunkSynchronously(timeoutMillis: Long): Int? {
        checkOpen()
        val timeout = timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        return try {
            connection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, timeout)
                .takeIf { it > 0 }
        } catch (error: RuntimeException) {
            throw failSession("NCM synchronous read failed", error)
        }
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun clearOutEndpointHalt() {
        val result = clearUsbEndpointHalt(connection, outEndpoint)
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "ncm bulk-out failed; clear-halt on endpoint 0x${outEndpoint.address.toString(16)} returned $result",
        )
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val NTH16_LENGTH = 12
        private const val MAX_RESYNCS_PER_WINDOW = 16
        private const val RESYNC_WINDOW_NANOS = 10_000_000_000L
        private const val LOGGED_RESYNCS = 5
        private const val STATUS_POLL_TIMEOUT_MILLIS = 20
        private const val STATUS_POLL_INTERVAL_MILLIS = 500L
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val MAX_CONSECUTIVE_WRITE_FAILURES = 25
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        private const val ALT_SETTING_ATTEMPTS = 5
        private const val ALT_SETTING_RETRY_MILLIS = 100L

        /** Vendor cdc_ncm drivers can rebind between a successful claim and SET_INTERFACE. */
        internal fun retryAlternateSelection(
            attempts: Int = ALT_SETTING_ATTEMPTS,
            pause: (Long) -> Unit = Thread::sleep,
            select: () -> LegacyUsbHostCompat.SelectResult,
            onAttempt: (Int, LegacyUsbHostCompat.SelectResult) -> Unit = { _, _ -> },
        ): LegacyUsbHostCompat.SelectResult {
            require(attempts > 0)
            var last = LegacyUsbHostCompat.SelectResult(false, null)
            for (attempt in 1..attempts) {
                last = select()
                onAttempt(attempt, last)
                if (last.selected) return last
                if (attempt < attempts) pause(ALT_SETTING_RETRY_MILLIS)
            }
            return last
        }
        /** Claims NCM while retaining the caller's shared USB connection. */
        internal fun open(
            sharedConnection: SharedUsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            expectedConfiguration: Int,
            vendorId: Int = 0x05ac,
            useAsyncRead: Boolean = true,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            val connection = sharedConnection.connection
            val claimed = ArrayList<UsbInterface>(2)
            var legacyRestore: (() -> Unit)? = null
            fun log(message: String) {
                onDiagnostic(message)
            }
            try {
                val activeConfiguration = LegacyUsbHostCompat.activeConfiguration(connection)
                if (activeConfiguration != null && activeConfiguration != expectedConfiguration) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "USB configuration changed before NCM open: expected=$expectedConfiguration " +
                            "activeConfig=$activeConfiguration",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm opening activeConfig=${activeConfiguration ?: "unknown"}",
                )
                @Suppress("DEPRECATION")
                val legacyEligible = LegacyNcmClaim.eligible(
                    Build.VERSION.SDK_INT, Build.BOARD, Build.HARDWARE, Build.CPU_ABI, vendorId,
                    function.control.interfaceClass, function.control.interfaceSubclass,
                    function.data.interfaceClass,
                )
                fun describe(value: UsbInterface) =
                    "${value.id}/${IphoneCarPlayConfiguration.alternateSetting(value)}" +
                        " class=${value.interfaceClass} subclass=${value.interfaceSubclass} proto=${value.interfaceProtocol}"
                log("ncm claim config=$expectedConfiguration control=${describe(function.control)} data=${describe(function.data)}" +
                    " api=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER}" +
                    " board=${Build.BOARD} hardware=${Build.HARDWARE} legacyEligible=$legacyEligible")
                var lastClaimErrno: Int? = null
                fun claim(value: UsbInterface): Boolean {
                    var nativeOps: LegacyNcmClaim.Native? = null
                    val trace: (String) -> Unit = { log("ncm claim iface=${describe(value)} $it") }
                    return LegacyNcmClaim.claim(
                        eligible = legacyEligible,
                        framework = { force ->
                            val res = LegacyUsbHostCompat.claim(connection, value)
                            lastClaimErrno = res.errno
                            trace("framework claim force=$force ok=${res.claimed} errno=${res.errno ?: "none"}")
                            res.claimed
                        },
                        native = {
                            try {
                                LegacyNcmNative.operations(connection, value.id,
                                    listOf(function.control.id, function.data.id), expectedConfiguration, trace).also { nativeOps = it }
                            } catch (error: LinkageError) {
                                trace("native unavailable: ${error.javaClass.simpleName}")
                                null
                            }
                        },
                        owned = {
                            claimed.add(value)
                            nativeOps?.let { ops -> legacyRestore = { ops.restore() } }
                        },
                        log = trace,
                    )
                }
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // The Android 7 compatible build claims the control interface first and then
                // claims the endpoint-bearing data alternate directly. Keep that as the primary
                // path; alt=0 is retained only as a fallback for broken vendor USB stacks.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = claim(first)
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}" +
                            (lastClaimErrno?.let { " (usbfs errno $it)" } ?: "") +
                            " activeConfig=${activeConfiguration ?: "unknown"}",
                    )
                }
                if (!sameInterface) {
                    val dataClaimed = claim(function.data)
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}" +
                                (lastClaimErrno?.let { " (usbfs errno $it)" } ?: "") +
                                " activeConfig=${activeConfiguration ?: "unknown"}",
                        )
                    }
                }
                val altSelection = retryAlternateSelection(
                    select = { LegacyUsbHostCompat.select(connection, function.data) },
                    onAttempt = { attempt, result ->
                        log("ncm setInterface iface=${describe(function.data)} attempt=$attempt " +
                            "ok=${result.selected} errno=${result.errno ?: "none"}")
                    },
                )
                log("ncm setInterface iface=${describe(function.data)} ok=${altSelection.selected} errno=${altSelection.errno ?: "none"}")
                if (!altSelection.selected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting" +
                            (altSelection.errno?.let { " (usbfs errno $it)" } ?: ""),
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                return NcmUsbBridge(
                    sharedConnection = sharedConnection,
                    outEndpoint = function.bulkOut,
                    inEndpoint = function.bulkIn,
                    statusEndpoint = function.statusIn,
                    claimedInterfaces = claimed,
                    useAsyncRead = useAsyncRead,
                    descriptorHostMac = descriptorHostMac,
                    onDiagnostic = onDiagnostic,
                    legacyRestore = legacyRestore,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                runCatching { legacyRestore?.invoke() }
                sharedConnection.release()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(
            connection: UsbDeviceConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            configurationId: Int,
            vendorId: Int = 0x05ac,
            diagnostics: (String) -> Unit = { Log.i(IphoneCarPlayConfiguration.TAG, it) },
        ): NcmUsbBridge = open(
            sharedConnection = SharedUsbDeviceConnection.own(connection),
            function = function,
            expectedConfiguration = configurationId,
            vendorId = vendorId,
            useAsyncRead = false,
            onDiagnostic = diagnostics,
        )

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
