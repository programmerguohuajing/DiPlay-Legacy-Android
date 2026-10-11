package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class UsbCompletionRouterTest {
    @Test fun usbmuxReaderPreservesNcmCompletionAndViceVersa() {
        for (reverse in listOf(false, true)) {
            val usbmux = Any()
            val ncm = Any()
            val queue = LinkedBlockingQueue<Any>()
            val router = UsbCompletionRouter<Any> { queue.poll(it, TimeUnit.MILLISECONDS) ?: throw TimeoutException() }
            router.register(usbmux)
            router.register(ncm)
            val first = if (reverse) ncm else usbmux
            val second = if (reverse) usbmux else ncm
            queue.add(second)
            queue.add(first)
            assertSame(first, router.await(first, 1000))
            assertSame(second, router.await(second, 1000))
        }
    }

    @Test fun concurrentReadersReceiveOnlyTheirOwnCompletions() {
        val queue = LinkedBlockingQueue<Any>()
        val router = UsbCompletionRouter<Any> { queue.poll(it, TimeUnit.MILLISECONDS) ?: throw TimeoutException() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(30) {
                val usbmux = Any()
                val ncm = Any()
                router.register(usbmux)
                router.register(ncm)
                val ready = CountDownLatch(2)
                val a = executor.submit<Any> { ready.countDown(); router.await(usbmux, 2000) }
                val b = executor.submit<Any> { ready.countDown(); router.await(ncm, 2000) }
                assertTrue(ready.await(1, TimeUnit.SECONDS))
                queue.add(ncm)
                queue.add(usbmux)
                assertSame(usbmux, a.get(3, TimeUnit.SECONDS))
                assertSame(ncm, b.get(3, TimeUnit.SECONDS))
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun timeoutPreservesPendingRequestForCancellationDrainOrRetry() {
        val queue = LinkedBlockingQueue<Any>()
        val router = UsbCompletionRouter<Any> { queue.poll(it, TimeUnit.MILLISECONDS) ?: throw TimeoutException() }
        val request = Any()
        router.register(request)
        assertThrows(TimeoutException::class.java) { router.await(request, 10) }
        queue.add(request)
        assertSame(request, router.await(request, 1000))
    }

    @Test fun duplicateRegistrationMustNotOverwriteAlreadyDeliveredCompletion() {
        val queue = LinkedBlockingQueue<Any>()
        val request = Any()
        val other = Any()
        val router = UsbCompletionRouter<Any> { queue.poll(it, TimeUnit.MILLISECONDS) ?: throw TimeoutException() }
        router.register(request)
        router.register(other)
        queue.add(request)
        queue.add(other)
        assertSame(other, router.await(other, 1_000))
        assertThrows(IllegalStateException::class.java) { router.register(request) }
        assertSame(request, router.await(request, 1_000))
        router.register(request)
        queue.add(request)
        assertSame(request, router.await(request, 1_000))
    }

    @Test fun closedRequestsAreDiscardedAndPersistentRequestCanBeRequeued() {
        val queue = LinkedBlockingQueue<Any>()
        val router = UsbCompletionRouter<Any> { queue.poll(it, TimeUnit.MILLISECONDS) ?: throw TimeoutException() }
        val closed = Any()
        val active = Any()
        router.register(closed)
        router.forget(closed)
        queue.add(closed)
        repeat(2) {
            router.register(active)
            queue.add(active)
            assertSame(active, router.await(active, 1000))
        }
    }

    @Test fun cancelAllTouchesOnlyStillRegisteredRequests() {
        val router = UsbCompletionRouter<Any> { null }
        val cancelled = mutableListOf<Any>()
        val queued = Any()
        val forgotten = Any()
        router.register(queued)
        router.register(forgotten)
        router.forget(forgotten)
        router.cancelAll { cancelled.add(it) }
        assertEquals(listOf(queued), cancelled)
    }

    @Test fun cancelAllOnEmptyRegistryIsANoOp() {
        val router = UsbCompletionRouter<Any> { null }
        val cancelled = mutableListOf<Any>()
        router.cancelAll { cancelled.add(it) }
        assertTrue(cancelled.isEmpty())
    }
}
