package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDeviceConnection
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 28], manifest = Config.NONE, shadows = [LegacyUsbConfigurationTest.Connection::class])
class LegacyUsbConfigurationTest {
    private val connection = Shadow.newInstanceOf(UsbDeviceConnection::class.java)
    private val state = Shadow.extract<Connection>(connection)
    private val configuration = CarPlayUsbConfiguration(
        6, emptyList(), Shadow.newInstanceOf(UsbConfiguration::class.java),
    )

    @Test fun failedFrameworkSelectionUsesUsbfsBeforeProceeding() {
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { fd, target ->
            assertEquals(42, fd)
            assertEquals(6, target)
            state.active = target
            0
        }
        assertTrue(result.selected)
        assertEquals(6, result.activeConfiguration)
        assertEquals(1, state.frameworkCalls)
        assertEquals(0, state.rawSetCalls)
    }

    @Test fun frameworkSuccessWithConfigOneStillRequiresUsbfs() {
        state.frameworkResult = true
        var calls = 0
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { _, _ ->
            calls++
            state.active = 6
            0
        }
        assertTrue(result.selected)
        assertEquals(1, calls)
        assertEquals(0, state.rawSetCalls)
    }

    @Test fun phoneReadbackDoesNotSkipKernelConfigurationOnFreshConnection() {
        state.active = 6
        state.frameworkResult = true
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { _, _ ->
            fail("Successful framework selection does not need native fallback")
            -1
        }
        assertTrue(result.selected)
        assertEquals(1, state.frameworkCalls)
    }

    @Test fun reportsBusyAndRejectsMissingNcmConfiguration() {
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { _, _ -> 16 }
        assertFalse(result.selected)
        assertEquals(16, result.errno)
        assertEquals(1, result.activeConfiguration)
        assertEquals(0, state.rawSetCalls)
    }

    @Test fun transientBusyRetriesUsbfsAndAcceptsVerifiedSuccess() {
        var attempts = 0
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { _, target ->
            attempts++
            if (attempts == 1) 16 else {
                state.active = target
                0
            }
        }
        assertTrue(result.selected)
        assertEquals(2, attempts)
        assertEquals(6, result.activeConfiguration)
    }
    @Test fun nativeSuccessCannotOverrideAReadbackMismatch() {
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { _, _ -> 0 }
        assertFalse(result.selected)
        assertEquals(1, result.activeConfiguration)
    }

    @Test fun matchingPhoneReadbackCannotHideFailedKernelSelection() {
        state.frameworkActive = 6
        var calls = 0
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { _, _ ->
            calls++
            16
        }
        assertFalse(result.selected)
        assertEquals(2, calls)
        assertEquals(16, result.errno)
        assertEquals(6, result.activeConfiguration)
    }

    @Test fun nativeSelectionRepairsKernelEvenWhenPhoneAlreadyReportsTarget() {
        state.active = 6
        var kernelConfigured = false
        val result = LegacyUsbHostCompat.selectConfiguration(connection, configuration) { _, target ->
            kernelConfigured = target == 6
            0
        }
        assertTrue(result.selected)
        assertTrue("Phone readback is not proof that the host kernel was configured", kernelConfigured)
        assertEquals(1, state.frameworkCalls)
    }

    @Implements(UsbDeviceConnection::class)
    class Connection {
        var active = 1
        var frameworkActive: Int? = null
        var frameworkResult = false
        var frameworkCalls = 0
        var rawSetCalls = 0

        @Implementation fun getFileDescriptor(): Int = 42
        @Implementation fun setConfiguration(configuration: UsbConfiguration): Boolean {
            frameworkCalls++
            frameworkActive?.let { active = it }
            return frameworkResult
        }

        @Implementation fun controlTransfer(
            requestType: Int, request: Int, value: Int, index: Int,
            buffer: ByteArray?, length: Int, timeout: Int,
        ): Int = when (request) {
            8 -> { buffer!![0] = active.toByte(); 1 }
            9 -> { rawSetCalls++; 0 }
            else -> -1
        }
    }
}
