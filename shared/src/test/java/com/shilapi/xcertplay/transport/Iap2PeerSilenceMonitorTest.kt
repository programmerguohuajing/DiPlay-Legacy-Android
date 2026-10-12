package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #84/#61: the wired live loop must report peer silence and eventually give up
 * instead of spinning forever on "控制通道运行中".
 */
class Iap2PeerSilenceMonitorTest {
    private var nowNanos = 0L
    private fun monitor(
        silentResetMillis: Long = 90_000L,
        diagnosticMillis: Long = 30_000L,
    ) = Iap2PeerSilenceMonitor(silentResetMillis, diagnosticMillis) { nowNanos }

    private fun advance(millis: Long) {
        nowNanos += millis * 1_000_000L
    }

    @Test
    fun quietPeerProducesNoDiagnosticBeforeThreshold() {
        val monitor = monitor()
        advance(29_999L)
        assertNull(monitor.diagnosticIfDue())
        assertFalse(monitor.shouldReset())
        assertEquals(29_999L, monitor.idleMillis())
    }

    @Test
    fun diagnosticFiresAtThresholdAndIsRateLimited() {
        val monitor = monitor()
        advance(30_000L)
        val first = monitor.diagnosticIfDue()
        assertTrue(first!!.contains("idleMs=30000"))

        // Immediate second call: rate-limited.
        advance(1_000L)
        assertNull(monitor.diagnosticIfDue())

        // After another diagnostic interval: fires again.
        advance(30_000L)
        assertTrue(monitor.diagnosticIfDue()!!.contains("idleMs=61000"))
    }

    @Test
    fun frameResetsIdleTimer() {
        val monitor = monitor()
        advance(80_000L)
        assertTrue(monitor.diagnosticIfDue() != null)
        monitor.onFrameReceived()
        assertEquals(0L, monitor.idleMillis())
        assertNull(monitor.diagnosticIfDue())
        assertFalse(monitor.shouldReset())
    }

    @Test
    fun resetFiresAtSilentThreshold() {
        val monitor = monitor(silentResetMillis = 5_000L, diagnosticMillis = 60_000L)
        advance(4_999L)
        assertFalse(monitor.shouldReset())
        advance(1L)
        assertTrue(monitor.shouldReset())
    }

    @Test
    fun customThresholdsAreHonored() {
        val monitor = monitor(silentResetMillis = 200L, diagnosticMillis = 100L)
        advance(100L)
        assertTrue(monitor.diagnosticIfDue()!!.contains("idleMs=100"))
        assertFalse(monitor.shouldReset())
        advance(100L)
        assertTrue(monitor.shouldReset())
    }
}
