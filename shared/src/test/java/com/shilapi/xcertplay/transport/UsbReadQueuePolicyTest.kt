package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbReadQueuePolicyTest {
    @Test fun acceptedPrimarySizeDoesNotRetryOrReduceLaterReads() {
        val policy = UsbReadQueuePolicy()
        val sizes = mutableListOf<Int>()
        repeat(2) {
            val buffer = ByteBuffer.allocateDirect(65_536)
            val result = policy.queue(buffer, {}) { sizes.add(it.remaining()); true }
            assertTrue(result.queued)
            assertEquals(null, result.fallbackBytes)
            assertEquals(65_536, buffer.limit())
        }
        assertEquals(listOf(65_536, 65_536), sizes)
    }

    @Test fun explicitRejectionRetriesSameBufferOnceAndRemembersOnlySuccessfulLimit() {
        val policy = UsbReadQueuePolicy()
        val buffer = ByteBuffer.allocateDirect(65_536)
        val sizes = mutableListOf<Int>()
        val result = policy.queue(buffer, {}) {
            assertSame(buffer, it)
            sizes.add(it.remaining())
            sizes.size == 2
        }
        assertTrue(result.queued)
        assertEquals(65_536, result.firstBytes)
        assertEquals(16_384, result.fallbackBytes)
        assertEquals(0, buffer.position())
        assertEquals(16_384, buffer.limit())
        policy.queue(ByteBuffer.allocateDirect(65_536), {}) { sizes.add(it.remaining()); true }
        assertEquals(listOf(65_536, 16_384, 16_384), sizes)
    }

    @Test fun fullSizeRejectionWalksTheCompatibilityLadderDownToTheFloorAndDoesNotPersist() {
        val policy = UsbReadQueuePolicy()
        val buffer = ByteBuffer.allocateDirect(32_768)
        val sizes = mutableListOf<Int>()
        val result = policy.queue(buffer, {}) { sizes.add(it.remaining()); false }
        assertFalse(result.queued)
        // Steps 32K → 16K → 8K → 4K → 2K (floor), reporting how far down it reached.
        assertEquals(32_768, result.firstBytes)
        assertEquals(2_048, result.fallbackBytes)
        assertEquals(listOf(32_768, 16_384, 8_192, 4_096, 2_048), sizes)
        assertEquals(32_768, buffer.limit())
        // A total failure caches nothing, so the next read starts again at full size.
        policy.queue(buffer, {}) { sizes.add(it.remaining()); true }
        assertEquals(listOf(32_768, 16_384, 8_192, 4_096, 2_048, 32_768), sizes)
    }

    @Test fun fullSizeRejectionStepsDownTheLadderUntilAcceptedAndRemembersThatSize() {
        val policy = UsbReadQueuePolicy()
        val buffer = ByteBuffer.allocateDirect(32_768)
        val sizes = mutableListOf<Int>()
        // A MUSB-style controller that rejects 32K/16K/8K but accepts 4K.
        val result = policy.queue(buffer, {}) { sizes.add(it.remaining()); it.remaining() <= 4_096 }
        assertTrue(result.queued)
        assertEquals(32_768, result.firstBytes)
        assertEquals(4_096, result.fallbackBytes)
        assertEquals(listOf(32_768, 16_384, 8_192, 4_096), sizes)
        assertEquals(4_096, buffer.limit())
        // The accepted size is cached, so the next read starts there without re-walking the ladder.
        policy.queue(ByteBuffer.allocateDirect(32_768), {}) { sizes.add(it.remaining()); true }
        assertEquals(listOf(32_768, 16_384, 8_192, 4_096, 4_096), sizes)
    }

    @Test fun exact16KiBBufferFallsBackOnLegacyControllerRejection() {
        val policy = UsbReadQueuePolicy(USBFS_BULK_URB_CEILING_BYTES)
        val attempts = mutableListOf<Int>()
        val buffer = ByteBuffer.allocateDirect(16_384)
        val result = policy.queue(buffer, {}) {
            attempts += it.remaining()
            it.remaining() <= 4_096
        }
        assertTrue(result.queued)
        assertEquals(16_384, result.firstBytes)
        assertEquals(4_096, result.fallbackBytes)
        assertEquals(listOf(16_384, 8_192, 4_096), attempts)
    }

    @Test fun exact16KiBTotalRejectionReportsActualFallback() {
        val policy = UsbReadQueuePolicy(USBFS_BULK_URB_CEILING_BYTES)
        val attempts = mutableListOf<Int>()
        val result = policy.queue(ByteBuffer.allocateDirect(16_384), {}) {
            attempts += it.remaining()
            false
        }
        assertFalse(result.queued)
        assertEquals(2_048, result.fallbackBytes)
        assertEquals(listOf(16_384, 8_192, 4_096, 2_048), attempts)
    }
    @Test fun ceilingCapsTheQueuedSizeSoPreApiPNeverExceedsTheUsbfsLimit() {
        // On API < 28 UsbRequest.queue THROWS above 16 KiB, so the cap must keep every attempt
        // within the usbfs ceiling rather than ever queueing the buffer's full remaining.
        val policy = UsbReadQueuePolicy(USBFS_BULK_URB_CEILING_BYTES)
        val buffer = ByteBuffer.allocateDirect(65_536)
        val sizes = mutableListOf<Int>()
        val result = policy.queue(buffer, {}) { sizes.add(it.remaining()); true }
        assertTrue(result.queued)
        assertEquals(16_384, result.firstBytes)
        assertEquals(null, result.fallbackBytes)
        assertEquals(listOf(16_384), sizes)
        assertEquals(16_384, buffer.limit())
    }

    @Test fun cappedLegacyReadRetriesSmallerSizesAndCachesTheAcceptedSize() {
        val policy = UsbReadQueuePolicy(USBFS_BULK_URB_CEILING_BYTES)
        val sizes = mutableListOf<Int>()
        repeat(2) {
            val buffer = ByteBuffer.allocateDirect(65_536)
            val result = policy.queue(buffer, {}) {
                sizes += it.remaining()
                assertTrue(it.remaining() <= USBFS_BULK_URB_CEILING_BYTES)
                it.remaining() <= 4_096
            }
            assertTrue(result.queued)
            assertEquals(4_096, buffer.limit())
        }
        assertEquals(listOf(16_384, 8_192, 4_096, 4_096), sizes)
    }

    @Test fun cappedLegacyTotalRejectionRestoresTheBufferAndCachesNothing() {
        val policy = UsbReadQueuePolicy(USBFS_BULK_URB_CEILING_BYTES)
        val buffer = ByteBuffer.allocateDirect(65_536)
        val sizes = mutableListOf<Int>()
        repeat(2) {
            val result = policy.queue(buffer, {}) { sizes += it.remaining(); false }
            assertFalse(result.queued)
            assertEquals(2_048, result.fallbackBytes)
            assertEquals(65_536, buffer.limit())
        }
        assertEquals(listOf(16_384, 8_192, 4_096, 2_048, 16_384, 8_192, 4_096, 2_048), sizes)
    }

    @Test fun cachedLegacyLimitCanRecoverOnceMoreWhenExplicitlyRejected() {
        val policy = UsbReadQueuePolicy(USBFS_BULK_URB_CEILING_BYTES)
        val sizes = mutableListOf<Int>()
        assertTrue(policy.queue(ByteBuffer.allocateDirect(65_536), {}) {
            it.remaining() <= 4_096
        }.queued)
        val result = policy.queue(ByteBuffer.allocateDirect(65_536), {}) {
            sizes += it.remaining()
            it.remaining() <= 2_048
        }
        assertTrue(result.queued)
        assertEquals(2_048, result.fallbackBytes)
        assertEquals(listOf(4_096, 2_048), sizes)
    }

    @Test fun queueExceptionNeverPermitsAnotherAttempt() {
        val policy = UsbReadQueuePolicy()
        var calls = 0
        try {
            policy.queue(ByteBuffer.allocateDirect(65_536), {}) { calls++; error("ambiguous queue failure") }
            throw AssertionError("Expected queue exception")
        } catch (error: IllegalStateException) {
            assertEquals("ambiguous queue failure", error.message)
        }
        assertEquals(1, calls)
    }

    @Test fun smallerRemainingBytesKeepTheirPositionAndDoNotRetry() {
        val buffer = ByteBuffer.allocateDirect(65_536).apply { position(5_000); limit(15_000) }
        var calls = 0
        val result = UsbReadQueuePolicy().queue(buffer, {}) {
            calls++
            assertEquals(5_000, it.position())
            assertEquals(10_000, it.remaining())
            false
        }
        assertEquals(1, calls)
        assertFalse(result.queued)
        assertEquals(null, result.fallbackBytes)
        assertEquals(5_000, buffer.position())
        assertEquals(15_000, buffer.limit())
    }

    @Test fun successfulLimitNeverEnlargesASmallerRemainingRegionOrRewindsPosition() {
        val policy = UsbReadQueuePolicy()
        var calls = 0
        policy.queue(ByteBuffer.allocateDirect(65_536), {}) { ++calls == 2 }
        val buffer = ByteBuffer.allocateDirect(32_768).apply { position(5_000); limit(11_000) }
        policy.queue(buffer, {}) {
            assertEquals(5_000, it.position())
            assertEquals(6_000, it.remaining())
            true
        }
        assertEquals(5_000, buffer.position())
        assertEquals(11_000, buffer.limit())
    }

    @Test fun closeBetweenExplicitRejectionAndRetryStopsTheRetry() {
        var closed = false
        var calls = 0
        try {
            UsbReadQueuePolicy().queue(ByteBuffer.allocateDirect(65_536), { check(!closed) }) {
                calls++; closed = true; false
            }
            throw AssertionError("Expected closed-state check")
        } catch (_: IllegalStateException) { }
        assertEquals(1, calls)
    }

    @Test fun explicitFalseWithChangedBufferStateIsAmbiguousAndNotRetried() {
        var calls = 0
        try {
            UsbReadQueuePolicy().queue(ByteBuffer.allocateDirect(65_536), {}) {
                calls++; it.position(1); false
            }
            throw AssertionError("Expected ambiguous buffer-state failure")
        } catch (_: IllegalStateException) { }
        assertEquals(1, calls)
    }
}
