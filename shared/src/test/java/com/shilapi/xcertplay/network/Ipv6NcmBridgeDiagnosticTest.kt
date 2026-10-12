package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #95: the inbound L4 summary must identify TCP-to-7000 without leaking addresses. */
class Ipv6NcmBridgeDiagnosticTest {

    /** Minimal IPv6 packet: 40-byte header + [payload]; nextHeader at byte 6. */
    private fun ipv6Packet(nextHeader: Int, payload: ByteArray): ByteArray {
        val header = ByteArray(40)
        header[0] = 0x60.toByte() // version 6
        header[6] = nextHeader.toByte()
        // Fill src/dst with recognizable non-zero addresses to prove redaction.
        for (i in 8 until 40) header[i] = (i * 7).toByte()
        return header + payload
    }

    @Test
    fun tcpSynTo7000IsIdentified() {
        // TCP header: src port (2B) at +40, dst port (2B) at +42.
        val tcp = ByteArray(20)
        tcp[0] = 0x1f.toByte(); tcp[1] = 0x90.toByte() // src 8080
        tcp[2] = 0x1b.toByte(); tcp[3] = 0x58.toByte() // dst 7000
        val summary = ipv6Packet(6, tcp).diagnosticSummary(0)
        assertEquals("bytes=60 next=6 tcp=->7000", summary)
    }

    @Test
    fun udpPacketShowsDestinationPort() {
        val udp = ByteArray(8)
        udp[2] = 0x00.toByte(); udp[3] = 0x35.toByte() // dst 53
        val summary = ipv6Packet(17, udp).diagnosticSummary(0)
        assertEquals("bytes=48 next=17 udp=->53", summary)
    }

    @Test
    fun icmp6ShowsType() {
        val icmp = ByteArray(8)
        icmp[0] = 135.toByte() // Neighbor Solicitation
        val summary = ipv6Packet(58, icmp).diagnosticSummary(0)
        assertEquals("bytes=48 next=58 icmp6=135", summary)
    }

    @Test
    fun truncatedPacketOnlyReportsSize() {
        val summary = ByteArray(30).diagnosticSummary(0)
        assertEquals("bytes=30", summary)
    }

    @Test
    fun summaryNeverContainsIpLiterals() {
        val tcp = ByteArray(20)
        tcp[2] = 0x1b.toByte(); tcp[3] = 0x58.toByte()
        val summary = ipv6Packet(6, tcp).diagnosticSummary(0)
        assertFalse("must not leak addresses: $summary", summary.contains("fe80"))
        assertFalse("must not leak addresses: $summary", summary.contains(":"))
        assertTrue(summary.contains("tcp=->7000"))
    }

    @Test
    fun unknownNextHeaderOmitsDetail() {
        val summary = ipv6Packet(1, ByteArray(8)).diagnosticSummary(0)
        assertEquals("bytes=48 next=1", summary)
    }
}
