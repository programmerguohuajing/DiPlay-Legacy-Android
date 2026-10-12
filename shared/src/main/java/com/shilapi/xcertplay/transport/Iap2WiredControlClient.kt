package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.mfi.Iap2MfiAuthenticationClient
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress

/**
 * The wired LIVI control sequence after a CSM channel is ready:
 * Identification, MFi, power announcement, five update subscriptions, then CarPlay availability.
 *
 * The caller retains ownership of [session]. While [run] is active it is the only receiver and
 * forwards each non-availability CSM frame to [onIncoming]; it neither opens NCM nor implements
 * an AirPlay receiver.
 */
class Iap2WiredControlClient(
    private val session: Iap2Session,
    private val mfi: Iap2MfiAuthenticationClient,
    private val peerSilentResetMillis: Long = PEER_SILENT_RESET_MILLIS,
    private val peerIdleDiagnosticMillis: Long = PEER_IDLE_DIAGNOSTIC_MILLIS,
) {
    fun run(
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WiredCarPlayEndpoint,
        availableCurrentMilliAmps: Int,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        locationProvider: Iap2LocationProvider? = null,
        vehicleStatusProvider: VehicleStatusProvider? = null,
        onIncoming: (Iap2Frame) -> Unit = {},
        onProgress: (String) -> Unit = {},
        /**
         * Issue #95: the iPhone may stay alive on iAP2 after StartSession yet never open the
         * AirPlay TCP connection. When supplied, the live loop fails fast with NO_AIRPLAY_TCP
         * instead of idling until the USB stack wedges. Defaults to disabled.
         */
        airPlayTcpArrived: () -> Boolean = { true },
    ): Iap2WiredControlResult {
        require(availableCurrentMilliAmps in 0..0xffff) {
            "availableCurrentMilliAmps must be in 0..65535"
        }
        require(timeoutMillis == NO_TIMEOUT_MILLIS || timeoutMillis in 1..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1..$MAX_TIMEOUT_MILLIS or NO_TIMEOUT_MILLIS"
        }

        val deadlineNanos = Iap2ControlDeadline(timeoutMillis)
        val identified = identification.withVehicleStatusFrom(vehicleStatusProvider)
        if (identified.vehicleStatusEnabled != identification.vehicleStatusEnabled) {
            onProgress("iap2 no battery reading: not declaring an electric vehicle")
        }
        Iap2IdentificationClient(session).identify(identified, requireRemaining(deadlineNanos))
        onProgress("iap2 identification accepted")
        var stage = Iap2WiredControlStage.IDENTIFIED
        mfi.run(session, requireRemaining(deadlineNanos), onProgress)
        stage = Iap2WiredControlStage.AUTHENTICATED
        onProgress("iap2 authentication accepted")
        deadlineNanos.authenticated()

        send(powerSourceUpdate(availableCurrentMilliAmps), deadlineNanos)
        for (subscription in subscriptions()) send(subscription, deadlineNanos)
        stage = Iap2WiredControlStage.SUBSCRIBED
        onProgress("iap2 power/subscriptions sent")

        var forwardedFrames = 0
        var carPlayStartSessions = 0
        var startSessionNanos = 0L
        // Issue #84/#61: the live loop previously had no dead-link detection — once the
        // iPhone went quiet, recv kept timing out and the UI sat on "控制通道运行中" forever.
        val silenceMonitor = Iap2PeerSilenceMonitor(peerSilentResetMillis, peerIdleDiagnosticMillis)
        // Only the initial CarPlay availability handshake is time-bounded; live control stays open.
        val startupDeadlineNanos = System.nanoTime() + STARTUP_AVAILABILITY_TIMEOUT_MILLIS * NANOS_PER_MILLISECOND
        val location = Iap2LocationReporter(locationProvider, onProgress)
        val vehicleStatus = Iap2VehicleStatusReporter(vehicleStatusProvider, onProgress)
        try {
            while (true) {
                val remaining = remainingMillis(deadlineNanos)
                if (remaining == 0L) {
                    return Iap2WiredControlResult(Iap2WiredControlTerminal.TIMED_OUT, stage, forwardedFrames, carPlayStartSessions)
                }
                location.tick { send(it, deadlineNanos) }
                vehicleStatus.tick { send(it, deadlineNanos) }
                val startupRemaining = if (carPlayStartSessions == 0) {
                    ((startupDeadlineNanos - System.nanoTime()) / NANOS_PER_MILLISECOND).coerceAtLeast(0L)
                } else remaining
                if (startupRemaining == 0L) {
                    onProgress("iap2 wired startup timed out waiting for CarPlay availability")
                    return Iap2WiredControlResult(Iap2WiredControlTerminal.TIMED_OUT, stage, forwardedFrames, carPlayStartSessions)
                }
                val pollTimeout = vehicleStatus.pollTimeout(location.pollTimeout(minOf(remaining, startupRemaining)))
                val incoming = try {
                    session.recv(pollTimeout)
                } catch (failure: IOException) {
                    if (session.isClosed) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    throw failure
                }
                if (incoming == null) {
                    if (session.isClosed) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.CHANNEL_CLOSED,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    if (remainingMillis(deadlineNanos) == 0L) {
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.TIMED_OUT,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    // Issue #84/#61: surface peer silence in the report, and stop spinning
                    // forever once the iPhone is quiet long after StartSession was sent.
                    silenceMonitor.diagnosticIfDue()?.let { onProgress(it) }
                    if (carPlayStartSessions > 0 && silenceMonitor.shouldReset()) {
                        val idleMs = silenceMonitor.idleMillis()
                        onProgress("iap2 wired peer silent for ${idleMs}ms; resetting link")
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.PEER_SILENT,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    // Issue #95: iPhone alive on iAP2 but no AirPlay TCP — fail fast with a
                    // clear message instead of idling until the USB stack wedges.
                    if (carPlayStartSessions > 0 && !airPlayTcpArrived() &&
                        System.nanoTime() - startSessionNanos >= WIRED_FIRST_TCP_MILLIS * NANOS_PER_MILLISECOND
                    ) {
                        onProgress("iap2 wired no AirPlay TCP ${WIRED_FIRST_TCP_MILLIS}ms after carplay-start-session")
                        return Iap2WiredControlResult(
                            Iap2WiredControlTerminal.NO_AIRPLAY_TCP,
                            stage,
                            forwardedFrames,
                            carPlayStartSessions,
                        )
                    }
                    continue
                }
                silenceMonitor.onFrameReceived()

                when (incoming.messageId) {
                    CARPLAY_AVAILABILITY -> {
                        onProgress("iap2 rx=0x4300 carplay-availability")
                        onProgress(carPlayAvailabilitySummary(incoming.payload))
                        // LIVI sends its wired answer on every availability notification; do not gate it on
                        // the phone's advertised availability boolean.
                        send(carPlayStartSession(endpoint), deadlineNanos)
                        stage = Iap2WiredControlStage.CARPLAY_START_SENT
                        carPlayStartSessions++
                        if (startSessionNanos == 0L) startSessionNanos = System.nanoTime()
                        onProgress("iap2 tx=0x4301 carplay-start-session")
                    }

                    Iap2LocationMessages.START_LOCATION_INFORMATION, Iap2LocationMessages.STOP_LOCATION_INFORMATION -> {
                        location.handle(incoming) { send(it, deadlineNanos) }
                    }

                    Iap2VehicleStatus.START_VEHICLE_STATUS_UPDATES, Iap2VehicleStatus.STOP_VEHICLE_STATUS_UPDATES -> {
                        vehicleStatus.handle(incoming) { send(it, deadlineNanos) }
                    }

                    else -> {
                        onProgress("iap2 rx=0x${incoming.messageId.toString(16).padStart(4, '0')}")
                        onIncoming(incoming)
                        forwardedFrames++
                    }
                }
            }
        } finally {
            locationProvider?.stop()
        }
    }

    private fun send(frame: Iap2Frame, deadlineNanos: Iap2ControlDeadline) {
        session.send(frame, requireRemaining(deadlineNanos))
    }

    companion object {
        const val NO_TIMEOUT_MILLIS = Long.MAX_VALUE
        private const val CARPLAY_AVAILABILITY = 0x4300
        private const val CARPLAY_START_SESSION = 0x4301
        private const val STARTUP_AVAILABILITY_TIMEOUT_MILLIS = 60_000L
        private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MAX_TIMEOUT_MILLIS = 24 * 60 * 60 * 1_000L
        private const val MAX_RECV_TIMEOUT_MILLIS = 5 * 60 * 1_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        /** Idle diagnostic cadence for a quiet iAP2 peer (issue #84/#61, Fix 0). */
        private const val PEER_IDLE_DIAGNOSTIC_MILLIS = 30_000L
        /** Quiet long after StartSession: treat the link as dead and rebuild (issue #84/#61, Fix 1). */
        private const val PEER_SILENT_RESET_MILLIS = 90_000L
        /** No AirPlay TCP this long after StartSession: fail fast (issue #95). Mirrors wireless. */
        private const val WIRED_FIRST_TCP_MILLIS = 30_000L

        /** Exact LIVI wired PowerSourceUpdate encoding: current and the charge-if-powered flag. */
        fun powerSourceUpdate(availableCurrentMilliAmps: Int): Iap2Frame =
            Iap2ControlMessages.powerSourceUpdate(availableCurrentMilliAmps, charging = true)

        /** Exact five subscription requests emitted by LIVI's wired bring-up. */
        fun subscriptions(): List<Iap2Frame> = Iap2ControlMessages.subscriptions()

        /** Builds the wired-only CarPlayStartSession message; no NCM or AirPlay socket is opened. */
        fun carPlayStartSession(endpoint: Iap2WiredCarPlayEndpoint): Iap2Frame =
            Iap2CarPlayMessages.startSession(
                wiredIpv6Addresses = endpoint.ipv6Addresses,
                airPlayPort = endpoint.airPlayPort,
                deviceIdentifier = endpoint.deviceIdentifier,
                publicKey = endpoint.publicKey,
                sourceVersion = endpoint.sourceVersion,
            )

        fun carPlayAvailabilitySummary(payload: ByteArray): String {
            return try {
                val availability = Iap2CarPlayMessages.availability(payload).wired
                val available = availability?.available
                val transport = availability?.identifier
                "iap2 4300 wiredAvailable=$available usbTransport=${transport ?: "none"}"
            } catch (error: RuntimeException) {
                "iap2 4300 decode failed: ${error.message}"
            }
        }

        private fun requireRemaining(deadlineNanos: Iap2ControlDeadline): Long = remainingMillis(deadlineNanos).also {
            if (it == 0L) throw IphoneUsbException.TimedOut("Timed out during wired iAP2 control bring-up")
        }

        private fun remainingMillis(deadlineNanos: Iap2ControlDeadline): Long = deadlineNanos.remainingMillis()

    }
}

/** A configured wired AirPlay endpoint; configuration does not claim that either service is live. */
class Iap2WiredCarPlayEndpoint(
    ipv6Addresses: List<String>,
    val airPlayPort: Int,
    val publicKey: String,
    val sourceVersion: String,
    val deviceIdentifier: String? = null,
) {
    /** A stable copy so a caller cannot mutate a validated endpoint before it is encoded. */
    val ipv6Addresses: List<String> = ipv6Addresses.toList()

    init {
        require(ipv6Addresses.isNotEmpty()) { "At least one wired IPv6 address is required" }
        require(ipv6Addresses.all(::isIpv6Literal)) { "Every wired address must be an IPv6 text literal" }
        require(airPlayPort in 1..65535) { "airPlayPort must be in 1..65535" }
        require(publicKey.isNotEmpty()) { "publicKey is required and must not be empty" }
        require(sourceVersion.isNotEmpty()) { "sourceVersion is required and must not be empty" }
        require('\u0000' !in publicKey) { "publicKey must not contain U+0000" }
        require('\u0000' !in sourceVersion) { "sourceVersion must not contain U+0000" }
        deviceIdentifier?.let {
            require(it.isNotEmpty()) { "deviceIdentifier is optional, but must not be empty when provided" }
            require('\u0000' !in it) { "deviceIdentifier must not contain U+0000" }
        }
    }

    private companion object {
        fun isIpv6Literal(value: String): Boolean {
            if (value.contains('%') || '\u0000' in value || !value.contains(':')) return false
            return try {
                InetAddress.getByName(value) is Inet6Address
            } catch (_: Exception) {
                false
            }
        }
    }
}

enum class Iap2WiredControlStage {
    IDENTIFIED,
    AUTHENTICATED,
    SUBSCRIBED,
    CARPLAY_START_SENT,
}

enum class Iap2WiredControlTerminal { CHANNEL_CLOSED, TIMED_OUT, PEER_SILENT, NO_AIRPLAY_TCP }

/** End state of the control loop only; it is not evidence of NCM or AirPlay availability. */
data class Iap2WiredControlResult(
    val terminal: Iap2WiredControlTerminal,
    val stage: Iap2WiredControlStage,
    val forwardedFrames: Int,
    val carPlayStartSessionsSent: Int,
)
