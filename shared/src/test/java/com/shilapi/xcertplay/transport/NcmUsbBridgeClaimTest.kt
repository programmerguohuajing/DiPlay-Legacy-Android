package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

/** Framework transaction/cleanup tests, with no claim about real kernel behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], manifest = Config.NONE, shadows = [NcmUsbBridgeClaimTest.Connection::class])
class NcmUsbBridgeClaimTest {
    @Before fun reset() {
        Connection.events.clear()
        Connection.failInterface = -1
        Connection.select = true
    }

    private fun intf(id: Int, alt: Int, clazz: Int, subclass: Int): UsbInterface =
        Shadow.newInstanceOf(UsbInterface::class.java).also {
            ReflectionHelpers.setField(it, "mId", id)
            ReflectionHelpers.setField(it, "mAlternateSetting", alt)
            ReflectionHelpers.setField(it, "mClass", clazz)
            ReflectionHelpers.setField(it, "mSubclass", subclass)
            ReflectionHelpers.setField(it, "mProtocol", 0)
        }

    private fun open(log: (String) -> Unit = {}): NcmUsbBridge {
        val endpoint = Shadow.newInstanceOf(UsbEndpoint::class.java)
        return NcmUsbBridge.open(Shadow.newInstanceOf(UsbDeviceConnection::class.java),
            NcmFunctionDiscovery.NcmFunction(intf(3, 0, 2, 13), intf(4, 1, 10, 0), intf(4, 0, 10, 0), null, endpoint, endpoint),
            6, 0x05ac, log)
    }

    @Test fun standardPathClaimsControlThenDataThenSelectsAltAndClosesInReverse() {
        val log = mutableListOf<String>()
        val bridge = open { log += it }
        assertEquals(listOf("claim:3:true", "claim:4:true", "set:4:1"), Connection.events)
        assertTrue(log.any { "config=6 control=3/0 class=2 subclass=13 proto=0" in it })
        assertTrue(log.any { "iface=4/1 class=10 subclass=0 proto=0 framework force=true ok=true" in it })
        assertTrue(log.any { "setInterface iface=4/1" in it && "ok=true" in it })
        bridge.close()
        bridge.close()
        assertEquals(listOf("claim:3:true", "claim:4:true", "set:4:1", "release:4", "release:3", "close"), Connection.events)
    }

    @Test fun failedDataClaimReleasesControlAndClosesWithoutSelectingAlt() {
        Connection.failInterface = 4
        fails()
        assertEquals(listOf("claim:3:true", "claim:4:true", "release:3", "close"), Connection.events)
    }

    @Test fun failedAltSelectionRetriesThenReleasesBothAndCloses() {
        Connection.select = false
        fails()
        assertEquals(listOf("claim:3:true", "claim:4:true") + List(5) { "set:4:1" } +
            listOf("release:4", "release:3", "close"), Connection.events)
    }

    @Test fun failedFirstClaimClosesWithoutReleasingUnownedInterface() {
        Connection.failInterface = 3
        fails()
        assertEquals(listOf("claim:3:true", "close"), Connection.events)
    }

    private fun fails() {
        try { open(); fail("Expected failed open") }
        catch (_: IphoneUsbException.DeviceUnavailable) { }
    }

    @Implements(UsbDeviceConnection::class)
    class Connection {
        companion object {
            val events = mutableListOf<String>()
            var failInterface = -1
            var select = true
        }
        @Implementation fun getRawDescriptors(): ByteArray = byteArrayOf()
        @Implementation fun claimInterface(value: UsbInterface, force: Boolean): Boolean {
            events += "claim:${value.id}:$force"
            return value.id != failInterface
        }
        @Implementation fun setInterface(value: UsbInterface): Boolean {
            events += "set:${value.id}:${value.alternateSetting}"
            return select
        }
        @Implementation fun releaseInterface(value: UsbInterface): Boolean {
            events += "release:${value.id}"
            return true
        }
        @Implementation fun close() { events += "close" }
    }
}
