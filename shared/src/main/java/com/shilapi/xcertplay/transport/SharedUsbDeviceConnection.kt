package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer

/**
 * Reference-counted owner for one Android USB device connection.
 *
 * Legacy Android head units can reject claims made from a second file descriptor for another
 * interface of the same composite iPhone. USBMUX and NCM can therefore share one connection while
 * keeping independent lifetimes; the underlying connection closes only after both users release it.
 */
internal class SharedUsbDeviceConnection private constructor(
    val connection: UsbDeviceConnection,
) {
    private var references = 1
    private var closed = false
    private val usbCompat = UsbRequestCompat()
    private val completions = UsbCompletionRouter<UsbRequest> { usbCompat.requestWait(connection, it) }

    fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean {
        completions.register(request)
        return try {
            usbCompat.queue(request, buffer).also { if (!it) completions.forget(request) }
        } catch (failure: Throwable) {
            completions.forget(request)
            throw failure
        }
    }

    fun await(request: UsbRequest, timeoutMillis: Long): UsbRequest = completions.await(request, timeoutMillis)
    fun forget(request: UsbRequest) = completions.forget(request)

    @Synchronized
    fun retain(): SharedUsbDeviceConnection {
        check(!closed) { "USB device connection is already closed" }
        references += 1
        return this
    }

    @Synchronized
    fun release() {
        if (closed) return
        references -= 1
        check(references >= 0) { "USB device connection released too many times" }
        if (references == 0) {
            closed = true
            usbCompat.close()
            connection.close()
        }
    }

    companion object {
        fun own(connection: UsbDeviceConnection): SharedUsbDeviceConnection =
            SharedUsbDeviceConnection(connection)
    }
}
