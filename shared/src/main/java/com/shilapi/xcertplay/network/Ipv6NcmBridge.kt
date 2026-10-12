package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import android.util.Log
import com.shilapi.xcertplay.transport.EthernetIpv6Codec
import com.shilapi.xcertplay.transport.NcmUsbBridge
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Moves IPv6 packets between an Android VpnService tun and the iPhone NCM Ethernet link.
 *
 * NCM carries Ethernet frames while the tun is a layer-3 device, so this bridge strips and
 * restores the Ethernet II header. Link-local neighbor discovery stays in the Android kernel,
 * mirroring LIVI's reliance on the host kernel for NDP on its TAP interface.
 */
class Ipv6NcmBridge(
    private val ncm: NcmUsbBridge,
    private val tun: ParcelFileDescriptor,
    private val hostMac: ByteArray,
    /** Link-local IPv6 address assigned to the Android TUN side (e.g. "fe80::2"). */
    private val hostLinkLocal: String,
    private val onDebugLog: (String) -> Unit = {},
    private val onError: (Throwable) -> Unit,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private val hostLinkLocalBytes = InetAddress.getByName(hostLinkLocal).address
    private val allNodesIpv6 = byteArrayOf(
        0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
    )
    private val allNodesMac = byteArrayOf(0x33, 0x33, 0, 0, 0, 0x01)
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 16
    /**
     * Issue #95: the report never shows whether the iPhone sends TCP SYN to port 7000 —
     * the L4 summary only reaches logcat. Mirror a redacted per-packet summary into the
     * diagnostic report (separate small budget) so the next report can distinguish
     * "iPhone never sent TCP" from "SYN lost in bridge/TUN/kernel".
     */
    private var reportInboundBudget = 8
    private var outboundLogBudget = 24
    @Volatile
    private var lastNaTimeNanos = 0L
    private val running = AtomicBoolean(false)
    private lateinit var ncmToTunThread: Thread
    private lateinit var tunToNcmThread: Thread

    fun start() {
        check(running.compareAndSet(false, true)) { "bridge is already started" }
        // Send an unsolicited Neighbor Advertisement (to all-nodes ff02::1) so the iPhone can
        // learn our MAC without completing a Solicitation → Advertisement round-trip first.
        // Without this, our unicast NA reply is silently dropped while peerMac is still null,
        // causing a deadlock: iPhone never learns fe80::2's MAC, never connects AirPlay TCP,
        // and the USB read queue times out with "FAILED: Android could not queue USBMUX read request".
        sendUnsolicitedNeighborAdvertisement(force = true)
        ncmToTunThread = Thread(::runNcmToTun, "ncm-ipv6-in").apply {
            isDaemon = true
            start()
        }
        tunToNcmThread = Thread(::runTunToNcm, "ncm-ipv6-out").apply {
            isDaemon = true
            start()
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        ncm.close()
        tun.close()
        join(ncmToTunThread)
        join(tunToNcmThread)
    }

    private fun runNcmToTun() {
        val output = FileOutputStream(tun.fileDescriptor)
        try {
            while (running.get()) {
                val frame = ncm.recv(READ_TIMEOUT_MILLIS)
                if (frame == null) {
                    if (peerMac == null) {
                        sendUnsolicitedNeighborAdvertisement()
                    }
                    continue
                }
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac

                if (EthernetIpv6Codec.isNeighborSolicitationFor(frame, ipv6.payloadOffset, ipv6.payloadLength, hostLinkLocalBytes)) {
                    val peerIp = frame.copyOfRange(ipv6.payloadOffset + 8, ipv6.payloadOffset + 24)
                    val isUnspecified = peerIp.all { it == 0.toByte() }
                    val replyDstMac = if (isUnspecified) allNodesMac else ipv6.sourceMac
                    val replyDstIp = if (isUnspecified) allNodesIpv6 else peerIp
                    val naFrame = EthernetIpv6Codec.buildNeighborAdvertisement(
                        hostMac = hostMac,
                        peerMac = replyDstMac,
                        hostIpv6 = hostLinkLocalBytes,
                        peerIpv6 = replyDstIp,
                        solicited = !isUnspecified,
                    )
                    ncm.send(naFrame, WRITE_TIMEOUT_MILLIS)
                    val naMsg = "ncm replied with solicited neighbor advertisement to peer ${ipv6.sourceMac.macString()}"
                    Log.i(TAG, naMsg)
                    runCatching { onDebugLog(naMsg) }
                    // This bridge owns NDP for hostLinkLocal. A TUN has no Ethernet
                    // neighbour entry: forwarding an already-answered NS into Android's
                    // layer-3 stack can trigger a second (or malformed) NA.
                    // Keep all other IPv6 packets on the ordinary TUN path.
                    continue
                }

                if (!loggedInbound) {
                    loggedInbound = true
                    val inMsg = "ncm first inbound ipv6 bytes=${ipv6.payloadLength} peer=${ipv6.sourceMac.macString()}"
                    Log.i(TAG, inMsg)
                    runCatching { onDebugLog(inMsg) }
                    sendUnsolicitedNeighborAdvertisement(force = true, targetMac = ipv6.sourceMac)
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(ipv6.payloadOffset)}")
                }
                if (reportInboundBudget > 0) {
                    reportInboundBudget--
                    runCatching { onDebugLog("ncm inbound diag ${frame.diagnosticSummary(ipv6.payloadOffset)}") }
                }
                output.write(frame, ipv6.payloadOffset, ipv6.payloadLength)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    private fun runTunToNcm() {
        val input = FileInputStream(tun.fileDescriptor)
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = input.read(buffer)
                if (length == -1) {
                    if (running.get()) onError(IOException("NCM IPv6 tunnel closed"))
                    return
                }
                // Android's TUN fd may transiently report a zero-byte read while its network is
                // being registered. It is neither EOF (-1) nor an IPv6 packet.
                if (length == 0) {
                    LockSupport.parkNanos(ZERO_READ_BACKOFF_NANOS)
                    continue
                }
                val tunPacket = buffer.copyOf(length)
                val ipv6 = EthernetIpv6Codec.addNeighborAdvertisementTargetMac(tunPacket, hostMac)
                if (ipv6.size != tunPacket.size) {
                    Log.i(TAG, "ncm added target-link-layer option to neighbor advertisement")
                }
                if (outboundLogBudget > 0) {
                    outboundLogBudget--
                    Log.i(TAG, "ncm outbound ${ipv6.summary(0)}")
                }
                val multicastMac = EthernetIpv6Codec.multicastDestinationMac(ipv6)
                val mac = multicastMac ?: peerMac
                if (mac == null) {
                    sendUnsolicitedNeighborAdvertisement()
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        Log.i(TAG, "ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    val outMsg = "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}"
                    Log.i(TAG, outMsg)
                    runCatching { onDebugLog(outMsg) }
                }
                val frame = EthernetIpv6Codec.build(hostMac, mac, ipv6)
                ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            }
        } catch (error: IOException) {
            if (running.get()) onError(error)
        } catch (error: RuntimeException) {
            if (running.get()) onError(error)
        }
    }

    /**
     * Sends an unsolicited Neighbor Advertisement for [hostLinkLocal] to the all-nodes multicast
     * group (ff02::1), with Override=1 and Source Link-Layer Address option, so the iPhone can
     * cache our MAC immediately without a Neighbor Solicitation round-trip.
     *
     * ICMPv6 NA wire format (RFC 4861 §4.4):
     *   IPv6 header (40 bytes)
     *   ICMPv6 type=136, code=0, checksum (4 bytes)
     *   flags R|S|O (4 bytes, bits 31-29)  — we set Override (bit 29)
     *   Target address (16 bytes)
     *   Option: Target Link-Layer Address (type=2, len=1, MAC: 6 bytes → 8 bytes total)
     */
    fun sendUnsolicitedNeighborAdvertisement(force: Boolean = false, targetMac: ByteArray? = null) {
        if (!running.get()) return
        val now = System.nanoTime()
        synchronized(this) {
            if (!force && now - lastNaTimeNanos < NA_RETRY_INTERVAL_NANOS) return
            lastNaTimeNanos = now
        }
        try {
            val dstMac = targetMac ?: peerMac ?: allNodesMac
            val frame = EthernetIpv6Codec.buildNeighborAdvertisement(
                hostMac = hostMac,
                peerMac = dstMac,
                hostIpv6 = hostLinkLocalBytes,
                peerIpv6 = allNodesIpv6,
                solicited = false,
            )
            ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            val naMsg = "ncm sent unsolicited neighbor advertisement for $hostLinkLocal"
            Log.i(TAG, naMsg)
            runCatching { onDebugLog(naMsg) }
        } catch (error: Exception) {
            Log.w(TAG, "ncm unsolicited NA send failed: ${error.message}")
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(JOIN_TIMEOUT_MILLIS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (thread.isAlive) thread.interrupt()
    }

    private fun ByteArray.macString(): String =
        joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ByteArray.summary(offset: Int): String {
        val payloadBytes = size - offset
        if (payloadBytes < 40) return "truncated bytes=$payloadBytes"
        val source = InetAddress.getByAddress(copyOfRange(offset + 8, offset + 24)).hostAddress
        val destination = InetAddress.getByAddress(copyOfRange(offset + 24, offset + 40)).hostAddress
        val nextHeader = this[offset + 6].toInt() and 0xff
        val detail = when {
            nextHeader == 6 && payloadBytes >= 44 -> " tcp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 17 && payloadBytes >= 44 -> " udp=${u16(offset + 40)}->${u16(offset + 42)}"
            nextHeader == 58 && payloadBytes >= 41 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
            else -> ""
        }
        return "bytes=$payloadBytes src=$source dst=$destination next=$nextHeader$detail"
    }

    private fun ByteArray.u16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private companion object {
        const val TAG = "xcertplay-usb"
        const val READ_TIMEOUT_MILLIS = 1_000L
        const val WRITE_TIMEOUT_MILLIS = 2_000
        const val TUN_READ_BYTES = 4_096
        const val ZERO_READ_BACKOFF_NANOS = 1_000_000L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
        const val NA_RETRY_INTERVAL_NANOS = 500_000_000L
    }
}

/**
 * IP-redacted L4 summary of one inbound IPv6 packet for the user-exported diagnostic
 * report (issue #95): enough to tell "TCP SYN to port 7000 arrived" from "no TCP at all",
 * without leaking addresses. [offset] is the IPv6 header start within the Ethernet frame.
 */
internal fun ByteArray.diagnosticSummary(offset: Int): String {
    fun u16(at: Int): Int = ((this[at].toInt() and 0xff) shl 8) or (this[at + 1].toInt() and 0xff)
    val payloadBytes = size - offset
    if (payloadBytes < 44) return "bytes=$payloadBytes"
    val nextHeader = this[offset + 6].toInt() and 0xff
    val detail = when (nextHeader) {
        6 -> " tcp=->${u16(offset + 42)}"
        17 -> " udp=->${u16(offset + 42)}"
        58 -> " icmp6=${this[offset + 40].toInt() and 0xff}"
        else -> ""
    }
    return "bytes=$payloadBytes next=$nextHeader$detail"
}
