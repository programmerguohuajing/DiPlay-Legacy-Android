package com.shilapi.xcertplay.transport

/**
 * Tracks iAP2 peer silence on a live control loop (issue #84/#61).
 *
 * The wired live loop previously had no dead-link detection: once the iPhone went quiet,
 * `recv` kept timing out and the loop spun forever while the UI showed
 * "CarPlay 控制通道运行中". This monitor distinguishes "peer quiet" (diagnostic) from
 * "peer gone" (reset), with an injectable clock for tests.
 */
internal class Iap2PeerSilenceMonitor(
    private val silentResetMillis: Long,
    private val diagnosticMillis: Long,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var lastRxNanos: Long = nowNanos()
    private var lastReportNanos: Long = 0L

    fun onFrameReceived() {
        lastRxNanos = nowNanos()
    }

    fun idleMillis(): Long = (nowNanos() - lastRxNanos) / 1_000_000L

    /**
     * A diagnostic line when the peer has been quiet for at least [diagnosticMillis]
     * and the previous report is old enough; null otherwise (rate-limited).
     */
    fun diagnosticIfDue(): String? {
        val idleMs = idleMillis()
        if (idleMs < diagnosticMillis) return null
        if (nowNanos() - lastReportNanos < diagnosticMillis * 1_000_000L) return null
        lastReportNanos = nowNanos()
        return "iap2 wired control idle idleMs=$idleMs (no iAP2 frame from iPhone)"
    }

    /** True once the peer has been quiet for at least [silentResetMillis]. */
    fun shouldReset(): Boolean = idleMillis() >= silentResetMillis
}
