package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()
    private var pendingFrames = 0
    private var pendingBytes = 0L

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            if (pendingFrames >= maxFrames || pendingBytes + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // An oversized frame is an unusable reference chain, not retained in the queue.
            if (job.nalus.size > maxBytes) return
            pendingFrames++
            pendingBytes += job.nalus.size
        }
        jobs.offer(job)
    }

    @Synchronized fun discardFrames() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item is VideoJob.Frame || item is VideoJob.Resync) iterator.remove()
        }
        pendingFrames = 0
        pendingBytes = 0L
    }

    // Waiting must never hold the queue monitor: USB receive callbacks enqueue video frames.
    fun poll(timeoutMillis: Long): VideoJob? {
        val job = (if (timeoutMillis > 0) jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS) else jobs.poll())
            ?: return null
        return synchronized(this) {
            if (job is VideoJob.Frame) {
                pendingFrames = maxOf(0, pendingFrames - 1)
                pendingBytes = maxOf(0L, pendingBytes - job.nalus.size)
            }
            job
        }
    }

    @Synchronized fun drain(): List<VideoJob> = ArrayList<VideoJob>().also {
        jobs.drainTo(it)
        pendingFrames = 0
        pendingBytes = 0L
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
