package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmAlternateSelectionRetryTest {
    @Test fun retryResolvesTransientVendorNcmRebind() {
        var calls = 0
        val waits = mutableListOf<Long>()
        val seen = mutableListOf<Int>()
        val result = NcmUsbBridge.retryAlternateSelection(
            pause = waits::add,
            select = {
                calls++
                LegacyUsbHostCompat.SelectResult(calls == 3, if (calls == 3) null else 16)
            },
            onAttempt = { attempt, _ -> seen += attempt },
        )
        assertTrue(result.selected)
        assertEquals(3, calls)
        assertEquals(listOf(100L, 100L), waits)
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test fun permanentFailureStopsAfterFiveAttempts() {
        var calls = 0
        val result = NcmUsbBridge.retryAlternateSelection(
            pause = {},
            select = { calls++; LegacyUsbHostCompat.SelectResult(false, 22) },
        )
        assertFalse(result.selected)
        assertEquals(22, result.errno)
        assertEquals(5, calls)
    }
}