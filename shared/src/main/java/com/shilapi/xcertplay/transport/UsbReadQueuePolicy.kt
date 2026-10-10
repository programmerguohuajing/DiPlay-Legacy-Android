package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/**
 * Pre-Android-P usbfs single-URB ceiling. On API < 28, `UsbRequest.queue(ByteBuffer)` validates
 * `buffer.remaining()` against `[0, 16384]` and THROWS `IllegalArgumentException` above it (it does
 * not return false), so a read buffer larger than this must never be queued on those API levels.
 */
internal const val USBFS_BULK_URB_CEILING_BYTES = 16 * 1024

/**
 * A size-compatibility hypothesis for explicit vendor queue rejection, not an API 28 size limit.
 * Android 9 UsbRequest.queue(ByteBuffer) accepts any size and clears its queued state on false:
 * https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/hardware/usb/UsbRequest.java
 *
 * [maxQueueBytes] caps the queued size. Pre-Android-P callers pass [USBFS_BULK_URB_CEILING_BYTES],
 * because above it `queue()` throws rather than returning false, which no retry can recover.
 *
 * Some host controllers (observed on MUSB hosts) reject not only a large read but the 16 KiB read
 * too, which previously ended the session outright. When a full-size read is rejected we therefore
 * step down a halving ladder (16K → 8K → 4K → 2K), each a whole multiple of the 512-byte bulk
 * packet, and cache the first size the controller accepts so later reads start there. A read that
 * has an original remaining region at or below the compatibility ceiling is not laddered.
 * A large region capped by an old-platform ceiling still qualifies for smaller retries.
 *
 * Call under the pipe's state lock, including publication, queueing and the open-state checks.
 */
internal class UsbReadQueuePolicy(private val maxQueueBytes: Int = Int.MAX_VALUE) {
    init { require(maxQueueBytes > 0) { "USB queue ceiling must be positive" } }
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val originalBytes = buffer.remaining()
        val ceiling = minOf(originalBytes, maxQueueBytes)
        val firstBytes = minOf(ceiling, successfulLimit ?: ceiling)

        buffer.limit(position + firstBytes)
        if (submitUnchanged(buffer, position, firstBytes, submit)) {
            return UsbReadQueueResult(true, firstBytes)
        }
        if (originalBytes < COMPATIBILITY_BYTES || firstBytes <= MIN_COMPATIBILITY_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }

        // Descending compatibility ladder for controllers that reject mid-range sizes too.
        var size = if (firstBytes > COMPATIBILITY_BYTES) COMPATIBILITY_BYTES else firstBytes / 2
        var lastAttempt = firstBytes
        while (size >= MIN_COMPATIBILITY_BYTES) {
            checkOpen()
            buffer.limit(position + size)
            lastAttempt = size
            if (submitUnchanged(buffer, position, size, submit)) {
                successfulLimit = size
                return UsbReadQueueResult(true, firstBytes, size)
            }
            size /= 2
        }
        buffer.limit(originalLimit)
        return UsbReadQueueResult(false, firstBytes, lastAttempt)
    }

    /**
     * Submits the buffer at its current limit. AOSP guarantees an unchanged buffer on explicit
     * false; a request that threw or changed its buffer state is ambiguous and must not be retried.
     */
    private fun submitUnchanged(
        buffer: ByteBuffer,
        position: Int,
        size: Int,
        submit: (ByteBuffer) -> Boolean,
    ): Boolean {
        if (submit(buffer)) return true
        check(buffer.position() == position && buffer.limit() == position + size) {
            "Rejected USB queue changed its buffer state"
        }
        return false
    }

    private companion object {
        const val COMPATIBILITY_BYTES = 16 * 1024
        const val MIN_COMPATIBILITY_BYTES = 2 * 1024
    }
}
