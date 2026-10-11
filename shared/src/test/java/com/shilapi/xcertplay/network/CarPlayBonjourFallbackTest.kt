package com.shilapi.xcertplay.network

import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBonjourFallbackTest {
    @Test fun kitkatAndModernAndroidUseBundledMdns() {
        assertTrue(useBundledCarPlayMdns(19))
        assertTrue(useBundledCarPlayMdns(20))
        assertFalse(useBundledCarPlayMdns(21))
        assertFalse(useBundledCarPlayMdns(28))
        assertTrue(useBundledCarPlayMdns(29))
    }

    @Test fun oneFamilyBindFailurePreservesOtherFamily() {
        val ipv4 = InetAddress.getByName("192.168.43.1")
        val ipv6 = InetAddress.getByName("fe80::2")
        val failures = mutableListOf<InetAddress>()
        val bound = bindAvailableMdnsFamilies<String>(
            listOf(ipv4, ipv6),
            bind = { addr ->
                if (addr == ipv6) throw IOException("EADDRINUSE")
                "bound-${addr.hostAddress}"
            },
            onFailure = { addr, _ -> failures += addr },
        )
        assertEquals(listOf(ipv4), bound.map { it.first })
        assertEquals(listOf(ipv6), failures)
    }

    @Test fun bothFamiliesFailReturnsEmptyAndBothReasons() {
        val addresses = listOf(
            InetAddress.getByName("192.168.43.1"),
            InetAddress.getByName("fe80::2"),
        )
        val failures = mutableListOf<InetAddress>()
        val bound = bindAvailableMdnsFamilies<String>(
            addresses,
            bind = { _: InetAddress -> throw IOException("port unavailable") },
            onFailure = { address, _ -> failures += address },
        )
        assertTrue(bound.isEmpty())
        assertEquals(addresses, failures)
    }
}
