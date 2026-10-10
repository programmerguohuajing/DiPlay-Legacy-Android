package com.shilapi.xcertplay.transport

import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class Iap2WirelessPrematureCloseTest {
    @Test fun peerMarkerStartsNormalSynchronizationWithoutFallback() {
        val engine = Iap2LinkEngine(Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2))
        engine.start(wiredInitiator = false, nowMillis = 0)
        assertTrue(engine.state() == Iap2LinkEngine.State.DETECTING)
        engine.feed(Iap2LinkEngine.IAP2_MARKER, nowMillis = 20)
        assertTrue(engine.state() == Iap2LinkEngine.State.NEGOTIATING)
        assertTrue(!engine.initiateSynchronizationIfDetecting(1_000))
    }
    @Test fun silentWirelessPeerGetsDelayedAccessorySynchronization() {
        val writes = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
        val transport = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) { writes.add(data.copyOf()) }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                Thread.sleep(timeoutMillis.coerceAtMost(100))
                return null
            }
            override fun close() = Unit
        }
        val link = Iap2LinkChannel.openWireless(transport)
        try {
            Thread.sleep(1250L)
            assertTrue("expect marker and a synchronization packet", writes.any { it.size > 6 })
        } finally {
            runCatching { link.close() }
        }
    }
    @Test fun peerEofBeforeNegotiationHasStageAndByteCounts() {
        val transport = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = Unit
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray = byteArrayOf()
            override fun close() = Unit
        }
        val link = Iap2LinkChannel.openWireless(transport)
        try {
            val failure = assertThrows(IOException::class.java) { link.awaitReady(2000L) }
            assertTrue(failure.message.orEmpty().contains("peer closed before link ready"))
            assertTrue(failure.message.orEmpty().contains("state=DETECTING"))
            assertTrue(failure.message.orEmpty().contains("sentBytes="))
            assertTrue(failure.message.orEmpty().contains("receivedBytes=0"))
        } finally {
            runCatching { link.close() }
        }
    }

    @Test fun rfcommResetBeforeNegotiationPreservesTransportCause() {
        val transport = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = Unit
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray =
                throw IOException("Connection reset by peer")
            override fun close() = Unit
        }
        val link = Iap2LinkChannel.openWireless(transport)
        try {
            val failure = assertThrows(IOException::class.java) { link.awaitReady(2000L) }
            assertTrue(failure.message.orEmpty().contains("handshake interrupted"))
            assertTrue(failure.message.orEmpty().contains("state=DETECTING"))
            assertTrue(failure.cause?.message == "Connection reset by peer")
        } finally {
            runCatching { link.close() }
        }
    }
}
