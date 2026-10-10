package com.shilapi.xcertplay.bluetooth

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BluetoothAddressResolverTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun testNormalizeValidMacFormats() {
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddressResolver.normalize("AA:BB:CC:DD:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddressResolver.normalize("aa:bb:cc:dd:ee:ff"))
        assertEquals("12:34:56:78:9A:BC", BluetoothAddressResolver.normalize("12-34-56-78-9a-bc"))
        assertEquals("A1:B2:C3:D4:E5:F6", BluetoothAddressResolver.normalize("a1b2c3d4e5f6"))
        assertEquals("A1:B2:C3:D4:E5:F6", BluetoothAddressResolver.normalize("  a1:b2:c3:d4:e5:f6  "))
    }

    @Test
    fun testNormalizeRejectsInvalidAndDummyMacs() {
        assertNull(BluetoothAddressResolver.normalize(null))
        assertNull(BluetoothAddressResolver.normalize(""))
        assertNull(BluetoothAddressResolver.normalize("   "))
        assertNull(BluetoothAddressResolver.normalize("invalid-mac"))
        assertNull(BluetoothAddressResolver.normalize("00:11:22:33:44"))
        assertNull(BluetoothAddressResolver.normalize("00:11:22:33:44:55:66"))
        assertNull(BluetoothAddressResolver.normalize("GG:HH:II:JJ:KK:LL"))

        // Dummy/placeholder MACs
        assertNull(BluetoothAddressResolver.normalize("02:00:00:00:00:00"))
        assertNull(BluetoothAddressResolver.normalize("02:00:00:00:00:12"))
        assertNull(BluetoothAddressResolver.normalize("00:00:00:00:00:00"))
        assertNull(BluetoothAddressResolver.normalize("FF:FF:FF:FF:FF:FF"))
    }

    @Test
    fun testIsValidMac() {
        assertTrue(BluetoothAddressResolver.isValidMac("AA:BB:CC:DD:EE:FF"))
        assertTrue(BluetoothAddressResolver.isValidMac("aabbccddeeff"))
        assertFalse(BluetoothAddressResolver.isValidMac("02:00:00:00:00:00"))
        assertFalse(BluetoothAddressResolver.isValidMac(null))
        assertFalse(BluetoothAddressResolver.isValidMac("invalid"))
    }

    @Test
    fun testResolveWithCustomOverride() {
        val result = BluetoothAddressResolver.resolve(context, "11:22:33:44:55:66")
        assertEquals("11:22:33:44:55:66", result)
    }

    @Test
    fun testResolveIgnoresInvalidCustomOverride() {
        val result = BluetoothAddressResolver.resolve(context, "invalid-override")
        // If system has no valid Bluetooth MAC on Robolectric, it returns null without crashing
        assertTrue(result == null || BluetoothAddressResolver.isValidMac(result))
    }
}
