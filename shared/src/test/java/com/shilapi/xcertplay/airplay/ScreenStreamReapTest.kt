package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression tests for issue #106: a dying screen stream must not kill the whole AirPlay
 * control session at once. The session gets a grace period so the iPhone can re-SETUP just
 * the video stream after a transient stall.
 */
@RunWith(RobolectricTestRunner::class)
class ScreenStreamReapTest {
    private val engine = CarPlayMediaEngine(object : MediaSink {}).apply {
        screenReapGraceMillis = 150L
    }

    @Test
    fun screenReapKeepsSessionOpenDuringGrace() {
        val session = testSession()
        try {
            scheduleReap(session)
            assertFalse(session.isClosed)
            assertTrue(hasPendingReap(session))
        } finally {
            cancelReap(session)
            session.close()
        }
    }

    @Test
    fun screenReapClosesSessionAfterGraceExpires() {
        val session = testSession()
        scheduleReap(session)
        Thread.sleep(600)
        assertTrue(session.isClosed)
        assertFalse(hasPendingReap(session))
    }

    @Test
    fun cancelledReapNeverClosesSession() {
        val session = testSession()
        try {
            scheduleReap(session)
            cancelReap(session)
            Thread.sleep(500)
            assertFalse(session.isClosed)
        } finally {
            session.close()
        }
    }

    @Test
    fun sessionTeardownCancelsPendingReap() {
        val session = testSession()
        scheduleReap(session)
        engine.onSessionClosed(session)
        assertFalse(hasPendingReap(session))
        Thread.sleep(500)
        assertFalse(session.isClosed)
        session.close()
    }

    private fun scheduleReap(session: AirPlaySession) {
        val method = CarPlayMediaEngine::class.java.getDeclaredMethod(
            "scheduleScreenReap",
            AirPlaySession::class.java,
        )
        method.isAccessible = true
        method.invoke(engine, session)
    }

    private fun cancelReap(session: AirPlaySession) {
        val method = CarPlayMediaEngine::class.java.getDeclaredMethod(
            "cancelScreenReap",
            AirPlaySession::class.java,
        )
        method.isAccessible = true
        method.invoke(engine, session)
    }

    @Suppress("UNCHECKED_CAST")
    private fun hasPendingReap(session: AirPlaySession): Boolean {
        val field = CarPlayMediaEngine::class.java.getDeclaredField("pendingScreenReaps")
        field.isAccessible = true
        val pending = field.get(engine) as Map<AirPlaySession, *>
        return pending.containsKey(session)
    }

    private fun testSession(): AirPlaySession = AirPlaySession(
        socket = Socket(),
        config = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            videoInCar = false,
        ),
        identity = AirPlayIdentity.generate(),
        pairings = PairingStore(),
        mfi = null,
        listener = object : AirPlaySessionListener {},
        media = object : AirPlayMediaHandler {},
    )
}
