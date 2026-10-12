package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for issue #94: the wireless iAP2 handshake diagnostic. */
class Iap2HandshakeProgressTest {
    @Test fun unacknowledgedWindowStartsEmpty() {
        val engine = Iap2LinkEngine(Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2))
        assertEquals(0, engine.unacknowledgedCount())
        assertEquals(0L, engine.unacknowledgedBytes())
    }

    @Test fun progressMessageReportsWritableWindowAndByteCounts() {
        val engine = Iap2LinkEngine(Iap2LinkConfig(maxOutgoing = 4, controlSessionVersion = 2))
        engine.start(wiredInitiator = false, nowMillis = 0)
        val message = Iap2LinkChannel.handshakeProgressMessage(
            engine, sentBytes = 128, receivedBytes = 0, startedAtMillis = 1000, nowMillis = 11000)
        assertTrue(message.startsWith("wireless iAP2 handshake "))
        assertTrue(message.contains("state=DETECTING"))
        assertTrue(message.contains("writable=false"))
        assertTrue(message.contains("unacked=0"))
        assertTrue(message.contains("unackedBytes=0"))
        assertTrue(message.contains("sentBytes=128"))
        assertTrue(message.contains("receivedBytes=0"))
        assertTrue(message.contains("elapsedMs=10000"))
    }

    @Test fun silentPeerStillGetsPeriodicHandshakeDiagnostics() {
        val transport = object : BlockingDuplexByteStream {
            override fun send(data: ByteArray) = Unit
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                Thread.sleep(timeoutMillis.coerceAtMost(50))
                return null
            }
            override fun close() = Unit
        }
        val progress = java.util.Collections.synchronizedList(mutableListOf<String>())
        val link = Iap2LinkChannel.openWirelessForTest(
            transport,
            onHandshakeProgress = { progress += it },
            handshakeProgressMillis = 150L,
        )
        try {
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline &&
                progress.count { it.startsWith("wireless iAP2 handshake ") } < 2) {
                Thread.sleep(50)
            }
            val messages = progress.filter { it.startsWith("wireless iAP2 handshake ") }
            assertTrue(
                "expected periodic handshake diagnostics while the link is not ready, got: $progress",
                messages.size >= 2,
            )
            assertTrue(messages.all { it.contains("state=") && it.contains("unacked=") })
        } finally {
            runCatching { link.close() }
        }
    }
}
