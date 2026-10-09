package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class LegacyUsbTransferTest {
    @Test fun usbmuxAndNcmReadSizesFitAndroidEightLimit() {
        for (sdk in listOf(19, 24, 26, 27)) {
            assertEquals(16384, usbTransferSize(sdk, 65536))
            assertEquals(16384, usbTransferSize(sdk, 32768))
            assertEquals(512, usbTransferSize(sdk, 512))
        }
        assertEquals(65536, usbTransferSize(28, 65536))
    }

    @Test fun largeWritesPreserveEveryByteAndOffsetOnLegacyAndroid() {
        val data = ByteArray(65573) { (it % 251).toByte() }
        for (sdk in listOf(19, 24, 25, 26, 27, 28)) {
            val output = java.io.ByteArrayOutputStream()
            val sizes = mutableListOf<Int>()
            assertEquals(data.size, writeUsbChunks(data.size, sdk, 1000) { offset, count, timeout ->
                assertTrue(timeout in 1..1000)
                if (sdk < 28) assertTrue(count <= 16384)
                sizes += count
                output.write(data, offset, count)
                count
            })
            assertArrayEquals(data, output.toByteArray())
            assertEquals(if (sdk < 28) listOf(16384, 16384, 16384, 16384, 37) else listOf(data.size), sizes)
        }
    }

    @Test fun failedOrShortWritesAreReportedWithoutRetryingCorruptFrames() {
        assertEquals(-1, writeUsbChunks(20000, 27, 1000) { _, _, _ -> -1 })
        assertEquals(16384, writeUsbChunks(20000, 27, 1000) { offset, count, _ -> if (offset == 0) count else -1 })
        var calls = 0
        assertEquals(100, writeUsbChunks(20000, 27, 1000) { _, _, _ -> calls++; 100 })
        assertEquals(1, calls)
    }
}
