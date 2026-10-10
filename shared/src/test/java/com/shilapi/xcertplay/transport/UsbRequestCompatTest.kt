package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 28], manifest = Config.NONE, shadows = [UsbRequestCompatTest.Request::class])
class UsbRequestCompatTest {
    @Test fun largeReadBuffersAreBoundedBeforeCallingAndroidQueue() {
        val request = UsbRequest()
        val shadow = Shadow.extract<Request>(request)
        val compat = UsbRequestCompat()
        try {
            for (capacity in listOf(65536, 32768)) {
                val buffer = ByteBuffer.allocateDirect(capacity)
                repeat(2) {
                    buffer.clear()
                    assertTrue(compat.queue(request, buffer))
                    assertEquals(if (Build.VERSION.SDK_INT < 28) 16384 else capacity, shadow.length)
                    buffer.position(shadow.length)
                }
            }
        } finally {
            compat.close()
            request.close()
        }
    }

    @Test @Config(sdk = [24])
    fun failedLegacyWaitClearsFutureForNextPoll() {
        val compat = UsbRequestCompat()
        val future = java.util.concurrent.FutureTask<UsbRequest?> {
            throw IllegalStateException("simulated USB wait failure")
        }
        future.run()
        val field = UsbRequestCompat::class.java.getDeclaredField("inFlight").apply {
            isAccessible = true
        }
        field.set(compat, future)
        val connection = org.robolectric.util.ReflectionHelpers.newInstance(UsbDeviceConnection::class.java)
        try {
            try {
                compat.requestWait(connection, 100)
                fail("Expected USB wait exception")
            } catch (error: IllegalStateException) {
                assertEquals("simulated USB wait failure", error.message)
            }
            assertNull("Completed failed task must not be reused", field.get(compat))
        } finally {
            compat.close()
        }
    }
    @Implements(UsbRequest::class)
    class Request {
        var length = 0
        @Implementation fun queue(buffer: ByteBuffer): Boolean {
            length = buffer.remaining()
            require(Build.VERSION.SDK_INT >= 28 || length <= 16384)
            return true
        }
        @Implementation fun close() = Unit
    }
}
