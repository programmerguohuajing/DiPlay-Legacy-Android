package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbRequest
import android.os.Build
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Compatibility wrapper used by the Android 7 port for UsbRequest queue/wait behavior.
 * API 26+ uses the platform timeout overload; API 24/25 run blocking requestWait()
 * on one daemon worker and impose the timeout through Future.get().
 */
internal class UsbRequestCompat : Closeable {
    private val waitExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "xcertplay-usb-wait").apply { isDaemon = true }
    }
    @Volatile
    private var inFlight: Future<UsbRequest?>? = null

    fun queue(request: UsbRequest, buffer: ByteBuffer): Boolean = queueUsbRequest(request, buffer)

    @Throws(TimeoutException::class)
    fun requestWait(connection: UsbDeviceConnection, timeoutMillis: Long): UsbRequest? {
        val timeout = timeoutMillis.coerceAtLeast(1L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return connection.requestWait(timeout)
        }

        var future = inFlight
        if (future == null) {
            future = waitExecutor.submit<UsbRequest?> { connection.requestWait() }
            inFlight = future
        }
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS).also { inFlight = null }
        } catch (error: java.util.concurrent.TimeoutException) {
            throw TimeoutException("USB request did not complete within ${timeout}ms")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TimeoutException("USB request wait was interrupted")
        } catch (error: ExecutionException) {
            // A failed Future is terminal; the next poll must submit a fresh request.
            inFlight = null
            when (val cause = error.cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw IllegalStateException("USB request wait failed", cause)
            }
        }
    }

    override fun close() {
        waitExecutor.shutdownNow()
    }
}
