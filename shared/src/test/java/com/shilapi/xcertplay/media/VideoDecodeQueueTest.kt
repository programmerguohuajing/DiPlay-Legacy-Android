package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun dequeueThenOverflowUsesOnlyPendingFrames() {
        val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(2)))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertEquals(2, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertNull(queue.poll(0))
    }

    @Test fun drainingResetsFrameAndByteBudgets() {
        val queue = VideoDecodeQueue(maxFrames = 1, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(5)))
        assertEquals(1, queue.drain().size)
        queue.offer(VideoJob.Frame(ByteArray(5)))
        assertEquals(5, (queue.poll(0) as VideoJob.Frame).nalus.size)
        assertNull(queue.poll(0))
    }
    @Test fun waitingDecoderNeverBlocksFrameProducer() {
        val queue = VideoDecodeQueue()
        val waiting = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            waiting.countDown()
            queue.poll(200)
        }
        worker.start()
        assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(30)
        val started = System.nanoTime()
        queue.offer(VideoJob.Frame(byteArrayOf(7)))
        val offerMs = (System.nanoTime() - started) / 1_000_000
        worker.join(1000)
        assertFalse(worker.isAlive)
        assertTrue("queue offer should not wait for decoder's poll timeout: ${'$'}offerMs ms", offerMs < 100)
        // Worker may have consumed the frame on waking; accounting must still be valid.
        queue.offer(VideoJob.Frame(byteArrayOf(8)))
        queue.drain()
        queue.offer(VideoJob.Frame(byteArrayOf(9)))
        assertEquals(9.toByte(), (queue.poll(0) as VideoJob.Frame).nalus[0])
    }
    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }
}
