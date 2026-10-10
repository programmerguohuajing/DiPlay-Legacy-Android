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
    private val onError: (Throwable) -> Unit,
) : Closeable {
    init {
        require(hostMac.size == EthernetIpv6Codec.MAC_BYTES) { "hostMac must be 6 bytes" }
    }

    @Volatile
    private var peerMac: ByteArray? = null
    private var loggedInbound = false
    private var loggedOutbound = false
    private var loggedWaitingForPeer = false
    private var inboundLogBudget = 16
    private var outboundLogBudget = 24
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
        sendUnsolicitedNeighborAdvertisement()
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
                val frame = ncm.recv(READ_TIMEOUT_MILLIS) ?: continue
                val ipv6 = EthernetIpv6Codec.parseIpv6View(frame) ?: continue
                peerMac = ipv6.sourceMac
                if (!loggedInbound) {
                    loggedInbound = true
                    Log.i(
                        TAG,
                        "ncm first inbound ipv6 bytes=${ipv6.payloadLength} " +
                            "peer=${ipv6.sourceMac.macString()}",
                    )
                }
                if (inboundLogBudget > 0) {
                    inboundLogBudget--
                    Log.i(TAG, "ncm inbound ${frame.summary(ipv6.payloadOffset)}")
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
                    if (!loggedWaitingForPeer) {
                        loggedWaitingForPeer = true
                        Log.i(TAG, "ncm deferred outbound unicast bytes=$length until peer MAC is learned")
                    }
                    continue
                }
                if (!loggedOutbound) {
                    loggedOutbound = true
                    Log.i(
                        TAG,
                        "ncm first outbound ipv6 bytes=$length destination=${mac.macString()} multicast=${multicastMac != null}",
                    )
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
    private fun sendUnsolicitedNeighborAdvertisement() {
        try {
            val src = InetAddress.getByName(hostLinkLocal).address
            // Destination: all-nodes multicast ff02::1
            val dst = byteArrayOf(
                0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01,
            )
            // ICMPv6 NA: type=136, code=0, checksum=0 (computed below), flags=Override(bit5 of byte4)
            // flags field: R=bit7, S=bit6, O=bit5 of the first byte after checksum → value 0x20000000
            val icmpv6 = ByteArray(32)
            icmpv6[0] = 136.toByte()     // type: Neighbor Advertisement
            icmpv6[1] = 0               // code
            icmpv6[2] = 0               // checksum high (computed below)
            icmpv6[3] = 0               // checksum low
            icmpv6[4] = 0x20.toByte()   // flags: Override bit set (0x20_00_00_00 big-endian)
            icmpv6[5] = 0
            icmpv6[6] = 0
            icmpv6[7] = 0
            // Target address (our link-local)
            src.copyInto(icmpv6, 8)
            // Option type=2 (Target Link-Layer Address), len=1 (units of 8 bytes), 6 bytes MAC
            icmpv6[24] = 2              // option type: Target Link-Layer Address
            icmpv6[25] = 1              // option length in units of 8 bytes
            hostMac.copyInto(icmpv6, 26)

            // IPv6 header (40 bytes)
            val ipv6 = ByteArray(40 + icmpv6.size)
            ipv6[0] = 0x60.toByte()    // version=6, traffic class, flow label
            // payload length = icmpv6.size
            ipv6[4] = (icmpv6.size ushr 8).toByte()
            ipv6[5] = icmpv6.size.toByte()
            ipv6[6] = 58               // next header: ICMPv6
            ipv6[7] = 255.toByte()     // hop limit
            src.copyInto(ipv6, 8)      // source
            dst.copyInto(ipv6, 24)     // destination
            icmpv6.copyInto(ipv6, 40)

            // Compute ICMPv6 checksum over pseudo-header + payload (RFC 2460 §8.1)
            var sum = 0L
            for (i in 8 until 40 step 2) {
                sum += ((ipv6[i].toInt() and 0xff) shl 8 or (ipv6[i + 1].toInt() and 0xff)).toLong()
            }
            sum += icmpv6.size.toLong()
            sum += 58L // next header
            val icmpStart = 40
            var j = icmpStart
            while (j + 1 < ipv6.size) {
                sum += ((ipv6[j].toInt() and 0xff) shl 8 or (ipv6[j + 1].toInt() and 0xff)).toLong()
                j += 2
            }
            while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
            val checksum = sum.inv().toInt() and 0xffff
            ipv6[42] = (checksum ushr 8).toByte()
            ipv6[43] = checksum.toByte()

            // Ethernet destination: 33:33:00:00:00:01 (all-nodes multicast)
            val dstMac = byteArrayOf(0x33, 0x33, 0, 0, 0, 0x01)
            val frame = EthernetIpv6Codec.build(hostMac, dstMac, ipv6)
            ncm.send(frame, WRITE_TIMEOUT_MILLIS)
            Log.i(TAG, "ncm sent unsolicited neighbor advertisement for $hostLinkLocal")
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
    }
}
