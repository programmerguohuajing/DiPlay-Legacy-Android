package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbRequest
import android.os.Build
import android.util.Log
import com.shilapi.xcertplay.compat.realSdkInt
import java.io.Closeable
import java.io.File
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
    private val sharedConnection: SharedUsbConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    private val useAsyncRead: Boolean,
    descriptorHostMac: ByteArray?,
    private val onDiagnostic: (String) -> Unit = {},
    private val legacyRestore: (() -> Unit)? = null,
    /** Issue #279: the data interface, kept so a wedged OUT endpoint can be reset in place. */
    private val dataInterface: UsbInterface? = null,
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    @Volatile
    private var isAbortedOrDetached = false
    private var failure: IphoneUsbException? = null

    /** Marks the bridge aborted (e.g. iPhone detached) to suppress blocking writes and interface release ioctls. */
    fun markAborted() {
        isAbortedOrDetached = true
    }
    private var sequence = 0
    private var loggedWriteTimeout = false
    /** Issue #137: in-flight write start (nanoTime; 0 = idle), read by the wedge watchdog. */
    @Volatile private var writeInFlightSinceNs = 0L
    private val writeWatchdogStarted = AtomicBoolean(false)
    private val writeWatchdogFired = AtomicBoolean(false)
    private var inboundFrameSeen = false
    private var consecutiveWriteFailures = 0
    /** Issue #352-Q4: bulk-OUT queue-vs-wire split (frames in, chunks out, bytes out). */
    private var ncmWriteEnqueuedFrames = 0
    private var ncmWriteSubmittedChunks = 0
    private var ncmWriteCompletedChunks = 0
    private var ncmWriteSuccessBytes = 0
    /** Issue #279: the data-interface reset is attempted once per failure run. */
    private var interfaceResetAttempted = false
    private var interfaceReclaimAttempted = false
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
    private val directReadBuffer = ByteBuffer.allocateDirect(usbTransferSize(realSdkInt(), READ_CHUNK_BYTES))
    private var readRequest: UsbRequest? = null
    private var readQueued = false
    private val readQueuePolicy = UsbReadQueuePolicy(
        if (realSdkInt() < Build.VERSION_CODES.P) USBFS_BULK_URB_CEILING_BYTES else Int.MAX_VALUE,
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
        if (closed || isAbortedOrDetached) return@synchronized
        ncmWriteEnqueuedFrames++
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.build(frame, sequence)
        ensureWriteWatchdog()
        writeInFlightSinceNs = System.nanoTime()
        var failedAfterNanos = 0L
        var errno = 0
        val transferred = try {
            writeUsbChunks(block.size, realSdkInt(), timeoutMillis) { offset, count, timeout ->
                val started = System.nanoTime()
                ncmWriteSubmittedChunks++
                val written = sharedConnection.bulkTransfer(outEndpoint, block, offset, count, timeout)
                if (written > 0) {
                    ncmWriteCompletedChunks++
                    ncmWriteSuccessBytes += written
                    return@writeUsbChunks written
                }
                failedAfterNanos = System.nanoTime() - started
                // The framework collapses every failure into -1, which hides the one fact that
                // decides the fix: a phone that NAKs (ETIMEDOUT) needs the link brought up, a
                // stale endpoint (ENODEV/EPIPE) needs it re-acquired. A transfer that consumed
                // its timeout may still be on the wire and must never be re-sent; one that failed
                // at once never left the host, so the usbfs ioctl can be asked for the same write
                // to recover the errno (and keep the data if it lands).
                if (failedAfterNanos < timeout.toLong() * SLOW_FAILURE_NANOS_PER_MILLIS) {
                    val probed = sharedConnection.bulkTransferErrno(outEndpoint, block, offset, count, timeout)
                    if (probed > 0) {
                        ncmWriteCompletedChunks++
                        ncmWriteSuccessBytes += probed
                        return@writeUsbChunks probed
                    }
                    errno = if (probed < 0) -probed else 0
                }
                written
            }
        } finally {
            writeInFlightSinceNs = 0L
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
            val failureLine = "ncm bulk-out failed $consecutiveWriteFailures/$MAX_CONSECUTIVE_WRITE_FAILURES " +
                "while the data path was live: endpoint=0x${outEndpoint.address.toString(16)} " +
                "requested=${block.size} transferred=$transferred errno=${errnoLabel(errno)} " +
                "elapsedMs=${failedAfterNanos / NANOS_PER_MILLISECOND} timeoutMs=$timeoutMillis " +
                "ncmCounts=enqueued=$ncmWriteEnqueuedFrames submitted=$ncmWriteSubmittedChunks " +
                "completed=$ncmWriteCompletedChunks successBytes=$ncmWriteSuccessBytes"
            Log.i(IphoneCarPlayConfiguration.TAG, failureLine)
            // Reports only carry the diagnostic channel; the probe lines below
            // were logcat-only, so a report could never show the halt state
            // they exist to capture (issue #137).
            onDiagnostic(failureLine)
            if (consecutiveWriteFailures == 1) {
                // Issue #279: the framework transfer result carries no errno, so probe
                // the endpoint status word instead — halt=1 means the device latched a
                // stall; halt=0 with failing transfers points at the host side.
                val statusLine = "ncm bulk-out endpoint status: ${probeOutEndpointStatus()}; " +
                    "${describeLinkState()}; ntb=[${probeNtbParameters()}]"
                Log.i(IphoneCarPlayConfiguration.TAG, statusLine)
                onDiagnostic(statusLine)
            }
            if (consecutiveWriteFailures == INTERFACE_RESET_AT_FAILURE && !interfaceResetAttempted) {
                interfaceResetAttempted = true
                resetDataInterface()
            }
            if (consecutiveWriteFailures == INTERFACE_RECLAIM_AT_FAILURE && !interfaceReclaimAttempted) {
                interfaceReclaimAttempted = true
                reclaimDataInterface()
            }
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
        interfaceResetAttempted = false
        interfaceReclaimAttempted = false
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /**
     * Issue #137: on some kernels (the reporter's i.MX6/API 19 unit, the T3 class
     * from issue #168) a bulk-OUT transfer never returns at all — it ignores its
     * timeout, so the failure counting, halt clearing and interface reset in
     * [send] (all gated on a transfer result) never run, the write lock stays
     * held, and even close() queues behind it until the session's teardown
     * abandonment. Watch the in-flight write from a side thread: one send far
     * beyond its own deadline means the write path is wedged. Record the session
     * failure, stop further writes, and close the framework connection — that is
     * what makes the stuck transfer come back with an error, so the session
     * tears down and reconnects instead of waiting out the 30 s no-TCP timer.
     * Raw usbfs connections have no framework connection to close; their
     * failure is still recorded and reported.
     */
    private fun ensureWriteWatchdog() {
        if (!writeWatchdogStarted.compareAndSet(false, true)) return
        Thread({
            while (!closed) {
                try {
                    Thread.sleep(WATCHDOG_POLL_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
                val since = writeInFlightSinceNs
                if (since == 0L || closed) continue
                if (System.nanoTime() - since < WRITE_WEDGE_NANOS) continue
                if (!writeWatchdogFired.compareAndSet(false, true)) break
                if (closed) break
                onDiagnostic(
                    "ncm bulk-out wedged: one send in flight over " +
                        "${WRITE_WEDGE_NANOS / 1_000_000}ms; failing the session",
                )
                failSession("NCM bulk-out transfer did not return; the USB write path is wedged")
                markAborted()
                runCatching { sharedConnection.frameworkConnection?.close() }
                break
            }
        }, "ncm-write-watchdog").apply { isDaemon = true; start() }
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
        val cancelled = runCatching { requestToClose?.cancel() }.getOrNull() == true
        statusThread?.let { thread ->
            thread.interrupt()
            try {
                thread.join(STATUS_POLL_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (!isAbortedOrDetached) synchronized(readLock) {
            synchronized(writeLock) {
                if (!isAbortedOrDetached) {
                    for (usbInterface in claimedInterfaces.asReversed()) {
                        try {
                            sharedConnection.releaseInterface(usbInterface)
                        } catch (_: RuntimeException) {
                            // Best-effort release; connection close is authoritative.
                        }
                    }
                }
            }
        }
        runCatching { legacyRestore?.invoke() }
        // Android 4.x: a cancelled URB is reaped by the wait worker asynchronously.
        // Freeing the request before that completion hands freed memory to REAPURB
        // and libc aborts. Wait for the completion; if it is not observed on legacy
        // Android, leak the request instead of crashing the process.
        var reaped = false
        if (requestToClose != null && cancelled) {
            reaped = try {
                sharedConnection.await(requestToClose, REQUEST_DRAIN_TIMEOUT_MILLIS)
                true
            } catch (_: Exception) {
                false
            }
        }
        sharedConnection.release()
        requestToClose?.let(sharedConnection::forget)
        if (requestToClose == null || reaped || !cancelled ||
            realSdkInt() >= Build.VERSION_CODES.O
        ) {
            runCatching { requestToClose?.close() }
        }
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            // Short synchronous polls keep this thread out of JNI critical sections most of the time;
            // notifications are rare, small interrupt packets that are only logged.
            val transferred = try {
                sharedConnection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_POLL_TIMEOUT_MILLIS)
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
                    val framework = sharedConnection.frameworkConnection
                        ?: throw failSession("async NCM read requires a framework USB connection")
                    if (!it.initialize(framework, inEndpoint)) {
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
            sharedConnection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, timeout)
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
        val result = clearUsbEndpointHalt(sharedConnection, outEndpoint)
        val line = "ncm bulk-out failed; clear-halt on endpoint 0x${outEndpoint.address.toString(16)} returned $result"
        Log.i(IphoneCarPlayConfiguration.TAG, line)
        onDiagnostic(line)
    }

    /**
     * Issue #279: GET_STATUS on the OUT endpoint. The framework bulk result is a bare
     * -1 (no errno), so this is the obtainable discriminator: halt=1 means the device
     * latched a stall on the endpoint; halt=0 while transfers keep failing points at
     * a wedged host-side queue instead.
     */
    private fun probeOutEndpointStatus(): String {
        val buffer = ByteArray(2)
        val transferred = try {
            sharedConnection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD or 0x02,
                0,
                0,
                outEndpoint.address,
                buffer,
                buffer.size,
                ENDPOINT_STATUS_TIMEOUT_MILLIS,
            )
        } catch (_: RuntimeException) {
            -1
        }
        if (transferred < 2) return "unavailable (control transferred=$transferred)"
        return "halt=${buffer[0].toInt() and 1} raw=0x${(buffer[0].toInt() and 0xff).toString(16)}"
    }

    /**
     * Issue #279: when clear-halt does not revive the OUT endpoint, re-select the data
     * interface's alternate setting — the same SET_INTERFACE the open path performs,
     * which resets the endpoint data toggles on both sides — then clear the halt once
     * more. Runs at most once per failure run, inside the write lock.
     */
    private fun resetDataInterface() {
        val usbInterface = dataInterface
        if (usbInterface == null) {
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out recovery: no data interface recorded; skipping reset")
            return
        }
        val result = runCatching { LegacyUsbHostCompat.select(sharedConnection, usbInterface) }.getOrNull()
        // The target alt setting is logged because usbfs may short-circuit SET_INTERFACE to the
        // setting the interface is already on: ok=true then proves the call was accepted, not
        // that the phone rebuilt its data pipes.
        val targetAlternate = runCatching { IphoneCarPlayConfiguration.alternateSetting(usbInterface) }.getOrNull()
        Log.i(
            IphoneCarPlayConfiguration.TAG,
            "ncm bulk-out recovery: re-selected data interface ${usbInterface.id} " +
                "targetAlt=${targetAlternate ?: "unknown"} " +
                "ok=${result?.selected} errno=${result?.errno ?: "none"}; endpoint status: ${probeOutEndpointStatus()}",
        )
        clearOutEndpointHalt()
    }

    /**
     * Issue #75: the heavier recovery rung, held back until a report carried
     * the counter evidence — a full session whose submitted chunks never
     * complete (completed stays 0 while the session otherwise reaches
     * SUCCESS and inbound keeps flowing). Re-selecting the interface
     * (resetDataInterface) cannot fix that class: usbfs may short-circuit
     * SET_INTERFACE to the setting already in effect, and it never resets
     * the host-side endpoint state. Releasing the data interface and
     * claiming it again (forced), then re-selecting its alternate setting,
     * rebuilds both sides' data pipes in place. Deliberately NOT a bus
     * reset (issue #243: resets can wedge these kernels) and not a session
     * teardown — the 25-failure failSession rung remains the last resort.
     * Once per failure run; every step is best-effort and logged with the
     * counters so a retest shows whether completions resume afterwards.
     */
    private fun reclaimDataInterface() {
        val usbInterface = dataInterface
        if (usbInterface == null) {
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out recovery: no data interface recorded; skipping re-claim")
            return
        }
        val released = runCatching { sharedConnection.releaseInterface(usbInterface) }.getOrDefault(false)
        val claimed = runCatching { sharedConnection.claimInterface(usbInterface, true) }.getOrDefault(false)
        val reselected = if (claimed) {
            runCatching { LegacyUsbHostCompat.select(sharedConnection, usbInterface) }.getOrNull()
        } else {
            null
        }
        val line = "ncm bulk-out recovery: re-claimed data interface ${usbInterface.id} " +
            "released=$released claimed=$claimed reselected=${reselected?.selected} " +
            "errno=${reselected?.errno ?: "none"}; endpoint status: ${probeOutEndpointStatus()}; " +
            "ncmCounts=enqueued=$ncmWriteEnqueuedFrames submitted=$ncmWriteSubmittedChunks " +
            "completed=$ncmWriteCompletedChunks successBytes=$ncmWriteSuccessBytes"
        Log.i(IphoneCarPlayConfiguration.TAG, line)
        onDiagnostic(line)
        clearOutEndpointHalt()
    }

    /**
     * The facts that decide which side owns a bulk-OUT failure: the alt setting the transfers
     * were queued against, the endpoint geometry the phone reported, and whether the kernel
     * exposes a netdev for the link at all.
     *
     * This stack drives NCM from userspace lwIP, so cdc_ncm never registers "usb0" and the
     * usual "100 mbit/s downlink 100 mbit/s uplink" line never appears — listing the netdev
     * states removes the guesswork about whether a link was negotiated somewhere else.
     */
    private fun describeLinkState(): String {
        val kernel = KERNEL_NETDEV_NAMES.mapNotNull { name ->
            val carrier = runCatching { File("/sys/class/net/$name/carrier").readText().trim() }.getOrNull()
            val state = runCatching { File("/sys/class/net/$name/operstate").readText().trim() }.getOrNull()
            if (carrier == null && state == null) null else "$name carrier=${carrier ?: "?"} state=${state ?: "?"}"
        }
        val data = dataInterface
        // Go through the compat helper: UsbInterface.getAlternateSetting() is
        // API 21+, and a direct call throws NoSuchMethodError on API 19 units,
        // killing the TX thread this diagnostic runs on (issue #137).
        return "data iface=${data?.id ?: "none"} " +
            "alt=${data?.let { IphoneCarPlayConfiguration.alternateSetting(it) } ?: -1} " +
            "in=0x${inEndpoint.address.toString(16)}@${inEndpoint.maxPacketSize} " +
            "out=0x${outEndpoint.address.toString(16)}@${outEndpoint.maxPacketSize} " +
            "status=${statusEndpoint?.let { "0x${it.address.toString(16)}" } ?: "none"} " +
            "dataIfaceClaimed=${data != null} inboundSeen=$inboundFrameSeen " +
            "netdev=[${if (kernel.isEmpty()) "no kernel netdev (userspace lwIP)" else kernel.joinToString()}]"
    }

    /**
     * CDC NCM GET_NTB_PARAMETERS (class request 0x80) on the control interface.
     *
     * This is the negotiation the kernel's cdc_ncm driver performs and a userspace stack can
     * skip: the phone advertises the largest NTB it accepts on bulk OUT, plus the NDP alignment
     * rules its parser enforces. A block that breaks those rules is rejected on the wire, which
     * looks exactly like a bulk-OUT-only failure with a clean endpoint. Read-only, so it is safe
     * to issue while diagnosing.
     */
    private fun probeNtbParameters(): String {
        val control = claimedInterfaces.firstOrNull { it.interfaceClass == CDC_CONTROL_CLASS }?.id
            ?: dataInterface?.let { it.id - 1 }
            ?: return "unavailable (no control interface)"
        val buffer = ByteArray(NTB_PARAMETERS_LENGTH)
        val read = runCatching {
            sharedConnection.controlTransfer(
                // 0xA1: device-to-host, class, interface. UsbConstants carries no recipient bits.
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_CLASS or INTERFACE_RECIPIENT,
                GET_NTB_PARAMETERS,
                0,
                control,
                buffer,
                buffer.size,
                ENDPOINT_STATUS_TIMEOUT_MILLIS,
            )
        }.getOrDefault(-1)
        return describeNtbParameters(buffer, read)
    }

    /** Linux errno values, spelled out so a log line alone identifies the fault. */
    private fun errnoLabel(errno: Int): String = when (errno) {
        0 -> "none"
        2 -> "ENOENT(2)"
        11 -> "EAGAIN(11)"
        19 -> "ENODEV(19)"
        32 -> "EPIPE(32)"
        71 -> "EPROTO(71)"
        108 -> "ESHUTDOWN(108)"
        110 -> "ETIMEDOUT(110)"
        else -> "raw($errno)"
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (isAbortedOrDetached) throw IphoneUsbException.DeviceUnavailable("NCM USB detached")
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    companion object {
        private fun readU16(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

        private fun readU32(source: ByteArray, offset: Int): Int =
            (source[offset].toInt() and 0xff) or
                ((source[offset + 1].toInt() and 0xff) shl 8) or
                ((source[offset + 2].toInt() and 0xff) shl 16) or
                ((source[offset + 3].toInt() and 0xff) shl 24)

        /**
         * Formats a GET_NTB_PARAMETERS reply for the diagnostic log.
         *
         * CDC NCM NTB parameter block layout: wLength@0, formats@2,
         * dwNtbInMaxSize@4, IN divisor/remainder/alignment@8/10/12, a 2-byte
         * reserved field@14, then the OUT block — dwNtbOutMaxSize@16,
         * wNdpOutDivisor@20, wNdpOutPayloadRemainder@22, wNdpOutAlignment@24,
         * wNtbOutMaxDatagrams@26. This probe previously read the OUT fields
         * 2 bytes early (skipping the reserved field), which made every
         * healthy phone report divisor=0 and a garbage max size (issue #307).
         */
        internal fun describeNtbParameters(buffer: ByteArray, read: Int): String {
            if (read <= 0) return "unavailable (transferred=$read)"
            val hex = buffer.take(minOf(read, NTB_PARAMETERS_LENGTH))
                .joinToString(" ") { "%02x".format(it) }
            if (read < NTB_PARAMETERS_LENGTH) return "truncated (transferred=$read) raw=$hex"
            val formats = readU16(buffer, 2)
            val outMax = readU32(buffer, 16).toLong() and 0xffff_ffffL
            val outDivisor = readU16(buffer, 20)
            val parsed = "formats=0x${formats.toString(16)} outMaxBytes=$outMax " +
                "outDivisor=$outDivisor outRemainder=${readU16(buffer, 22)} " +
                "outAlignment=${readU16(buffer, 24)} maxDatagrams=${readU16(buffer, 26)}"
            val implausible = outDivisor == 0 || outMax == 0L || outMax > MAX_PLAUSIBLE_NTB_BYTES
            return if (implausible) "$parsed IMPLAUSIBLE raw=$hex" else "$parsed raw=$hex"
        }

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
        /** Issue #137: one send may legitimately run to its 2 s deadline; 10 s in flight means wedged. */
        private const val WRITE_WEDGE_NANOS = 10_000_000_000L
        private const val WATCHDOG_POLL_MILLIS = 500L
        /** Issue #279: consecutive failures at which the one-shot interface reset runs. */
        private const val INTERFACE_RESET_AT_FAILURE = 5
        /** Issue #75: consecutive failures at which the one-shot interface re-claim runs. */
        private const val INTERFACE_RECLAIM_AT_FAILURE = 12
        private const val ENDPOINT_STATUS_TIMEOUT_MILLIS = 500
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /** A failure that used less than 90% of its timeout never waited on the phone. */
        private const val SLOW_FAILURE_NANOS_PER_MILLIS = 900_000L
        /** Netdev names a kernel cdc_ncm driver would create; this stack runs lwIP instead. */
        private val KERNEL_NETDEV_NAMES = listOf("usb0", "usb1", "usb2", "ncm0")
        private const val CDC_CONTROL_CLASS = 0x02
        private const val INTERFACE_RECIPIENT = 0x01
        private const val GET_NTB_PARAMETERS = 0x80
        private const val NTB_PARAMETERS_LENGTH = 28
        /** Phones advertise NTB sizes in the KB range; anything past 1 MB is a parse/peer error. */
        private const val MAX_PLAUSIBLE_NTB_BYTES = 1_048_576L
        private const val REQUEST_DRAIN_TIMEOUT_MILLIS = 2_000L

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
            sharedConnection: SharedUsbConnection,
            function: NcmFunctionDiscovery.NcmFunction,
            expectedConfiguration: Int,
            vendorId: Int = 0x05ac,
            useAsyncRead: Boolean = true,
            onDiagnostic: (String) -> Unit = {},
        ): NcmUsbBridge {
            val connection = sharedConnection
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
                    useAsyncRead = useAsyncRead && sharedConnection.supportsAsyncRequests,
                    descriptorHostMac = descriptorHostMac,
                    onDiagnostic = onDiagnostic,
                    legacyRestore = legacyRestore,
                    dataInterface = function.data,
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

        /**
         * The descriptor bytes [ethernetMacStringIndex] walks. Framework connections
         * expose them directly; a raw connection (issue #266) re-reads the same layout
         * over control transfers: device descriptor followed by every config blob.
         */
        private fun rawDescriptorBytes(connection: SharedUsbConnection): ByteArray {
            connection.frameworkConnection?.rawDescriptors?.let { return it }
            val device = ByteArray(18)
            val deviceRead = connection.controlTransfer(
                UsbConstants.USB_DIR_IN, USB_REQUEST_GET_DESCRIPTOR, 0x0100, 0,
                device, device.size, USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (deviceRead < 18) return ByteArray(0)
            val count = device[17].toInt() and 0xff
            val out = java.io.ByteArrayOutputStream()
            out.write(device)
            for (index in 0 until count.coerceAtMost(8)) {
                val header = ByteArray(9)
                val headerRead = connection.controlTransfer(
                    UsbConstants.USB_DIR_IN, USB_REQUEST_GET_DESCRIPTOR, 0x0200 or index, 0,
                    header, header.size, USB_CONTROL_TIMEOUT_MILLIS,
                )
                if (headerRead != 9) continue
                val total = (header[2].toInt() and 0xff) or ((header[3].toInt() and 0xff) shl 8)
                if (total !in 9..65535) continue
                val blob = ByteArray(total)
                val blobRead = connection.controlTransfer(
                    UsbConstants.USB_DIR_IN, USB_REQUEST_GET_DESCRIPTOR, 0x0200 or index, 0,
                    blob, blob.size, USB_CONTROL_TIMEOUT_MILLIS,
                )
                if (blobRead == total) out.write(blob)
            }
            return out.toByteArray()
        }

        private fun readNcmHostMac(connection: SharedUsbConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(rawDescriptorBytes(connection), controlInterfaceId) ?: return null
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
