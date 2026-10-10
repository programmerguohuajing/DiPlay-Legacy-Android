package com.shilapi.xcertplay.transport

import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** requestWait is connection-wide, even when readers use different USB interfaces. */
internal class UsbCompletionRouter<T : Any>(private val poll: (Long) -> T?) {
    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private val requests = IdentityHashMap<T, Boolean>()
    private var poller: Thread? = null

    fun register(request: T) = lock.withLock {
        requests[request] = false
    }

    fun forget(request: T) = lock.withLock {
        requests.remove(request)
        condition.signalAll()
    }

    fun await(request: T, timeoutMillis: Long): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.coerceAtLeast(1))
        lock.withLock {
            while (true) {
                if (!requests.containsKey(request)) throw IOException("USB request was closed")
                if (requests[request] == true) {
                    requests.remove(request)
                    return request
                }
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) throw TimeoutException("USB request completion timed out")

                if (poller == null) {
                    poller = Thread.currentThread()
                    val pollMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceIn(1, 50)
                    val completed = try {
                        lock.unlock()
                        try {
                            poll(pollMillis)
                        } catch (_: TimeoutException) {
                            null
                        }
                    } finally {
                        lock.lock()
                        poller = null
                        condition.signalAll()
                    }
                    if (completed != null && requests.containsKey(completed)) {
                        requests[completed] = true
                        condition.signalAll()
                    }
                } else {
                    try {
                        condition.await(remainingNanos, TimeUnit.NANOSECONDS)
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("USB completion wait interrupted", interrupted)
                    }
                }
            }
        }
    }
}
