package com.shilapi.xcertplay.media

import android.content.Context
import com.shilapi.xcertplay.compat.realSdkInt
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        try {
            track.audioAttributes
        } catch (_: Throwable) {
            configured
        }
    } else {
        configured
    }

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
    private val nowNs: () -> Long = System::nanoTime,
    /** Issue #290: true requests AUDIOFOCUS_GAIN for media; false keeps MAY_DUCK. */
    private val mediaFocusExclusive: Boolean = false,
    /** Issue #364: duck media on announcement frame activity (experimental). */
    private val announcementDuckEnabled: Boolean = false,
) {
    private data class Entry(val channel: AudioChannel, val attributes: Any?, var appliedVolume: Float = FULL_VOLUME)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: Any? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    /** Issue #364: the volume the focus side last asked for, before the
     * announcement duck is composed in; [mediaVolume] stays the effective one. */
    private var focusVolume = FULL_VOLUME
    private var announcementDucking = false
    private var announcementChannel: AudioChannel? = null
    /** Wall clock of the last announcement-channel PCM write; only read by the release check. */
    @Volatile private var lastAnnouncementNanos = 0L
    @Volatile private var announcementTimerArmed = false
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    /** Wall clock of the last accepted PCM write; only read by the idle check. */
    @Volatile private var lastRenderNanos = 0L
    @Volatile private var idleTimerArmed = false
    private val idleHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile var onAudioFocusGain: (() -> Unit)? = null
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener
    /** Diagnostic only: an AudioTrack playing PCM does not prove the vendor audio source is selected. */
    @Suppress("DEPRECATION")
    internal fun legacyOutputSnapshot(streamType: Int): String = runCatching {
        val audio = manager ?: return@runCatching "audioManager=unavailable"
        val activeStream = streamType.takeIf { it in 0..10 } ?: AudioManager.STREAM_MUSIC
        "a2dpOn=${audio.isBluetoothA2dpOn} musicActive=${audio.isMusicActive} " +
            "selectedStream=$activeStream volume=${audio.getStreamVolume(activeStream)}/" +
            "${audio.getStreamMaxVolume(activeStream)} " +
            "musicVolume=${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
            "${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
    }.getOrElse { "audioRouteDiagnostics=${it.javaClass.simpleName}" }

    /** Warns when the head unit's media volume would mute CarPlay audio. See issue #88. */
    internal fun musicVolumeWarning(): String? = musicVolumeWarning(manager)

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            if (change == AudioManager.AUDIOFOCUS_GAIN) {
                try { onAudioFocusGain?.invoke() } catch (_: Exception) {}
            }
            // Android may have queued callbacks before a request was abandoned or replaced.
            if (generation != focusGeneration || requestedChannel == null || active.isEmpty()) {
                // Dropping this silently hides the one window that matters: between a telephony
                // track closing and media reopening, a head unit that keeps focus looks like
                // nothing at all. Say the callback arrived and why it was not acted on.
                runCatching {
                    report("Audio: focus change=$change dropped" +
                        " stale=${generation != focusGeneration} noRequest=${requestedChannel == null}" +
                        " activeTracks=${active.size}")
                }
                return@synchronized
            }
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setFocusVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        // This reports a temporary Android focus owner, not a confirmed call.
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setFocusVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setFocusVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: Any?) {
        // Issue #364: the announcement duck needs the per-track volume
        // bookkeeping even when system focus requests are off, so track
        // registration also runs for the duck alone; refreshRequest() itself
        // stays gated on the focus setting and issues no request then.
        if (closed || manager == null) return
        if (!enabled && !announcementDuckEnabled) return
        active[track] = Entry(channel, attributes)
        // Issue #214: navigation alone gets no sound on some head units because the audio
        // output stays muted without focus. Let a lone navigation track take focus to open
        // the path; when media/phone is already active, keep track in active set so media idle
        // does not abandon focus and mute guidance.
        if (channel == AudioChannel.NAVIGATION &&
            active.values.any { it.channel != AudioChannel.NAVIGATION }) {
            applyMediaVolume(track, active.getValue(track))
            return
        }
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        announcementDucking = false
        announcementTimerArmed = false
        announcementChannel = null
        refreshRequest()
    }

    private fun refreshRequest() {
        if (!enabled) return
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            focusVolume = FULL_VOLUME
            mediaVolume = FULL_VOLUME
            abandonFocusLocked()
            return
        }
        if (requestedChannel == primary.channel) return
        focusGeneration += 1
        abandonFocusLocked()
        val gain = when (primary.channel) {
            // Issue #290: MAY_DUCK is the default because a permanent request on
            // STREAM_MUSIC made one head unit treat DiPlay as the sole owner of
            // the music stream (its own players denied output instead of ducking
            // CarPlay). But another unit's vendor stack answers MAY_DUCK with a
            // permanent duck to silence — the exclusive switch restores GAIN
            // for those units, at the cost of possibly muting other players.
            AudioChannel.MEDIA -> if (mediaFocusExclusive) AudioManager.AUDIOFOCUS_GAIN
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            // Lone navigation takes transient focus so the head unit opens its audio path
            // (#214); it never ducks because nothing else is playing.
            AudioChannel.NAVIGATION -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        currentListener = listenerFor(focusGeneration)
        val next: Any? = if (realSdkInt() >= 26) AudioFocusRequest.Builder(gain)
            .setAudioAttributes(checkNotNull(primary.attributes) as AudioAttributes)
            .setOnAudioFocusChangeListener(currentListener, Handler(Looper.getMainLooper()))
            .build() else null
        request = next
        requestedChannel = primary.channel
        val result = if (realSdkInt() >= 26 && next != null) manager?.requestAudioFocus(next as AudioFocusRequest)
        else manager?.requestAudioFocus(currentListener, AudioManager.STREAM_MUSIC, gain)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) setFocusVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    /** Abandons the live request without touching the per-track volume bookkeeping. */
    private fun abandonFocusLocked() {
        val abandoned = request
        request = null
        requestedChannel = null
        if (realSdkInt() >= 26) abandoned?.let { manager?.abandonAudioFocusRequest(it as AudioFocusRequest) }
        else manager?.abandonAudioFocus(currentListener)
    }

    /**
     * Called from the audio writer on every accepted PCM write.
     *
     * CarPlay keeps its AudioTracks open for a whole session, so track lifetime is the wrong
     * signal for focus: a permanent request held while the phone played nothing took the head
     * unit's music stream away from its own players (issue report: 汽水音乐 and WeChat silent,
     * guidance fine because it uses a separate vendor stream). The request is therefore dropped
     * after a silent window and taken again by the next sample.
     */
    fun onPcmRendered() {
        lastRenderNanos = nowNs()
        synchronized(this) {
            if (closed || !enabled) return
            // Focus was given back while the tracks stayed open; the phone is audible again.
            if (requestedChannel == null && active.isNotEmpty()) refreshRequest()
            // Only a silent media stream is worth giving back: a call, a spoken assistant
            // reply or a guidance prompt is legitimately silent between utterances, and the
            // lease must survive that silence.
            if (requestedChannel != AudioChannel.MEDIA) return
            if (idleTimerArmed) return
            idleTimerArmed = true
        }
        idleHandler.postDelayed(idleRelease, IDLE_RELEASE_MILLIS)
    }

    private val idleRelease: Runnable = Runnable {
        val recheckMillis: Long
        synchronized(this) {
            idleTimerArmed = false
            if (closed || requestedChannel == null) return@Runnable
            // The primary channel may have changed while this was queued (a call started,
            // guidance began): only a silent media stream gives the lease back.
            if (requestedChannel != AudioChannel.MEDIA) return@Runnable
            // If navigation or telephony is active, keep focus lease alive so guidance is not muted.
            if (active.values.any { it.channel != AudioChannel.MEDIA }) return@Runnable
            val idleNanos = nowNs() - lastRenderNanos
            if (idleNanos < IDLE_RELEASE_NANOS) {
                recheckMillis = (IDLE_RELEASE_NANOS - idleNanos) / 1_000_000L + IDLE_RECHECK_MILLIS
                idleTimerArmed = true
            } else {
                focusGeneration += 1
                abandonFocusLocked()
                val line = "Audio: focus released after ${idleNanos / 1_000_000L}ms without PCM"
                Log.i(TAG, line)
                runCatching { report(line) }
                return@Runnable
            }
        }
        idleHandler.postDelayed(idleRelease, recheckMillis)
    }

    /**
     * Issue #364: focus events state the volume they want in [focusVolume];
     * the effective media volume composes that with the announcement duck,
     * so neither side can undo the other early. A focus gain during an
     * announcement does not restore full volume before the announcement
     * ends, and a transient-loss mute (0f) always wins over the duck level.
     * While the duck experiment is off the announcement flag is never set
     * and this is exactly the old direct setMediaVolume call.
     */
    private fun setFocusVolume(volume: Float) {
        focusVolume = volume
        setMediaVolume(if (announcementDucking) minOf(volume, DUCKED_VOLUME) else volume)
    }

    /**
     * Issue #364 (experimental, off by default): called from an announcement
     * channel's audio writer on every accepted PCM write. CarPlay keeps the
     * navigation track resident for a whole session, so track lifetime is
     * the wrong signal — only frames actually flowing count as an
     * announcement. While frames flow, media is ducked to [DUCKED_VOLUME]
     * (composed with the focus side via [setFocusVolume]); about
     * [ANNOUNCEMENT_RELEASE_MILLIS] after the last frame, the release check
     * restores whatever volume the focus side currently wants.
     */
    fun onAnnouncementFrame(channel: AudioChannel) {
        if (!announcementDuckEnabled || closed) return
        if (channel == AudioChannel.MEDIA) return
        lastAnnouncementNanos = nowNs()
        synchronized(this) {
            if (closed) return
            if (!announcementDucking) {
                announcementDucking = true
                announcementChannel = channel
                val line = "Audio: announcement duck engaged channel=$channel"
                Log.i(TAG, line)
                runCatching { report(line) }
                setMediaVolume(minOf(focusVolume, DUCKED_VOLUME))
            }
            if (announcementTimerArmed) return
            announcementTimerArmed = true
        }
        idleHandler.postDelayed(announcementRelease, ANNOUNCEMENT_RELEASE_MILLIS)
    }

    private val announcementRelease: Runnable = Runnable {
        val recheckMillis: Long
        synchronized(this) {
            announcementTimerArmed = false
            if (closed || !announcementDucking) return@Runnable
            val idleNanos = nowNs() - lastAnnouncementNanos
            if (idleNanos < ANNOUNCEMENT_RELEASE_NANOS) {
                recheckMillis = (ANNOUNCEMENT_RELEASE_NANOS - idleNanos) / 1_000_000L + IDLE_RECHECK_MILLIS
                announcementTimerArmed = true
            } else {
                announcementDucking = false
                val line = "Audio: announcement duck released channel=$announcementChannel" +
                    " after ${idleNanos / 1_000_000L}ms without announcement PCM"
                announcementChannel = null
                Log.i(TAG, line)
                runCatching { report(line) }
                setMediaVolume(focusVolume)
                return@Runnable
            }
        }
        idleHandler.postDelayed(announcementRelease, recheckMillis)
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        // Telephony and assistant speech remain audible, and navigation never enters this map.
        // This changes only the renderer's relative gain, never Android's user stream volume.
        if (entry.channel != AudioChannel.MEDIA || entry.appliedVolume == mediaVolume) return
        val applied = runCatching { track.setStereoVolume(mediaVolume, mediaVolume) == AudioTrack.SUCCESS }.getOrDefault(false)
        if (applied) entry.appliedVolume = mediaVolume
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
        /** Issue #364: hold the duck briefly past the last announcement frame
         * so a prompt split into bursts does not pump the media volume. */
        const val ANNOUNCEMENT_RELEASE_MILLIS = 1_500L
        const val ANNOUNCEMENT_RELEASE_NANOS = ANNOUNCEMENT_RELEASE_MILLIS * 1_000_000L
        /** CarPlay media may pause between songs; only a long silence gives focus back. */
        const val IDLE_RELEASE_MILLIS = 10_000L
        const val IDLE_RELEASE_NANOS = IDLE_RELEASE_MILLIS * 1_000_000L
        const val IDLE_RECHECK_MILLIS = 500L
    }
}

/**
 * Issue #309: peak |sample| of a PCM16-LE chunk (the decoder output format
 * this file already assumes in its fade/gain passes). Silence reads 0, full
 * scale reads 32768. A trailing odd byte is ignored, like the fade pass.
 */
internal fun pcm16Peak(data: ByteArray, offset: Int, length: Int): Int {
    var peak = 0
    var i = offset
    val end = offset + length - 1
    while (i < end) {
        val sample = (data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)
        val magnitude = if (sample == -32768) 32768 else if (sample < 0) -sample else sample
        if (magnitude > peak) peak = magnitude
        i += 2
    }
    return peak
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    /** Issue #343-Q1: route the call downlink through the music stream (experimental). */
    private val telephonyDownlinkViaMusic: Boolean = false,
    /** Issue #364: duck media while announcement audio flows (experimental). */
    private val announcementDuck: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val audioFocusAutoYield: Boolean = true,
    /** Issue #290: media focus exclusive mode; read per session, default keeps MAY_DUCK. */
    private val mediaFocusExclusive: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    /** Issues #253/#263: per-stream PCM volume in percent (100 = unchanged). */
    private val mediaVolumePercent: Int = 100,
    private val navigationVolumePercent: Int = 100,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    /** Opt-in call echo cancellation, replacing a controllable platform canceller while available. */
    private val callEchoCancellation: Boolean = false,
    /** Cut the bass that head units add when they play a call as music. */
    private val callVoiceFilter: Boolean = false,
    private val callMicCompatibility: Boolean = false,
    /**
     * Smooth video: show main-screen frames at the iPhone's frame time plus a delay that starts here and
     * then follows how late the decoder releases frames ([PacingDelay]); 0 shows each as soon as it is
     * decoded. Only a SurfaceView honours the timestamps, so the host sets it with one.
     */
    private val videoPacingDelayMillis: Int = 0,
) : MediaSink {
    // Each downlink publishes its own reference; a mic must match that stream and sample rate.
    private val callEchoReferences = ConcurrentHashMap<AudioStreamId, EchoReference>()
    private val appContext = context?.applicationContext
    private val audioManager = if (realSdkInt() >= Build.VERSION_CODES.M) {
        appContext?.getSystemService(AudioManager::class.java)
    } else {
        @Suppress("DEPRECATION")
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        audioFocusAutoYield,
        onAudioDiagnostic,
        mediaFocusExclusive = mediaFocusExclusive,
        announcementDuckEnabled = announcementDuck,
    )

    /** The media-key session may own Android's current focus request for this same sink. */
    fun onMediaAudioFocusChanged(change: Int) {
        audioFocusCoordinator.onExternalFocusChange(change)
    }

    fun setOnAudioFocusGainListener(listener: (() -> Unit)?) {
        audioFocusCoordinator.onAudioFocusGain = listener
    }

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    // Surface capture, worker publication, retirement and detach snapshots share one ownership boundary.
    // Native codec work and waits never run under this lock.
    private val videoOwnershipLock = Any()
    private var videoClosed = false
    private val videoReleasedListeners = ArrayList<() -> Unit>()
    // Closed decoders whose workers may still hold a codec on a surface until they exit; a surface
    // detach also waits for them.
    private val closingVideoDecoders = java.util.Collections.newSetFromMap(ConcurrentHashMap<VideoDecoder, Boolean>())
    // How long a surface detach waits for decoders to confirm, and then for an unconfirmed one to exit
    // after it is closed. Variables so tests can shorten them.
    internal var detachTimeoutNanos = 2_000_000_000L
    internal var detachGraceNanos = 500_000_000L
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
        }
    }

    /**
     * Starts moving every decoder of this sink off [surface], which a SurfaceHolder destroy callback must
     * not return before. Each worker decides from the surface its codec actually renders to, after any
     * surface change queued before this: one already elsewhere has nothing to do. With [parkMain] the
     * main-screen decoder moves to an offscreen consumer it owns and keeps its state (smooth video, so the
     * picture can return at once); other decoders are released. Decoders closed earlier whose workers have
     * not exited yet are waited for too. [SurfaceDetach.await] returns the result.
     */
    fun beginSurfaceDetach(surface: Surface, parkMain: Boolean): SurfaceDetach {
        val deadline = System.nanoTime() + detachTimeoutNanos
        val requests = ArrayList<SurfaceDetachRequest>()
        val steps = ArrayList<() -> Boolean>()
        synchronized(videoOwnershipLock) {
            surfaces.entries.removeIf { it.value === surface } // future workers must not start on it
            if (defaultSurface === surface) defaultSurface = null
            for ((type, decoder) in videoDecoders.entries.toList()) {
                val request = decoder.requestDetach(surface, park = parkMain && type == MAIN_SCREEN_TYPE)
                requests += request
                steps += { settleDetach(type, decoder, request, deadline) }
            }
            closingVideoDecoders.removeIf { it.exited }
            closingVideoDecoders.toList().forEach { closing -> steps += { closing.awaitExitUntil(deadline + detachGraceNanos) } }
        }
        return SurfaceDetach(requests, steps)
    }

    /** A surface detach in flight across one sink's decoders; see [beginSurfaceDetach]. */
    class SurfaceDetach internal constructor(
        internal val requests: List<SurfaceDetachRequest>,
        private val steps: List<() -> Boolean>,
    ) {
        /**
         * Waits until every decoder has let go of the surface (moved, parked, released, or its worker
         * exited). False when one did not confirm in time and its worker, closed then, did not exit within
         * the grace period either; nothing more can be done from here, as with any native call that hangs.
         */
        fun await(): Boolean = steps.map { it() }.all { it }
    }

    private fun settleDetach(type: Int, decoder: VideoDecoder, request: SurfaceDetachRequest, deadline: Long): Boolean {
        if (decoder.awaitDetach(request, deadline)) return true
        Log.w("xcertplay-usb", "Video detach not confirmed in time type=$type; closing that decoder")
        videoDiagnosticHandlers[type]?.invoke("surface detach not confirmed; decoder closed")
        // The stream's next frame starts a fresh decoder on no surface, so it cannot compete for this one.
        retire(type, decoder)
        return decoder.awaitExitUntil(deadline + detachGraceNanos)
    }

    /** Closes a stream's decoder and keeps it visible to surface detaches until its worker exits. */
    private fun retire(type: Int, decoder: VideoDecoder) {
        synchronized(videoOwnershipLock) {
            // A timed-out detach can reach here after the worker has already completed cleanup. Its
            // onExit removed both registrations; do not republish it after its sole release notification.
            if (videoDecoders[type] !== decoder && decoder !in closingVideoDecoders) return
            closingVideoDecoders += decoder
            videoDecoders.remove(type, decoder)
            decoder.close()
        }
    }

    /** After [close], waits up to [timeoutMillis] for this sink's decoders to release their codecs. */
    fun awaitVideoReleased(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val closing = synchronized(videoOwnershipLock) { closingVideoDecoders.toList() }
        return closing.map { it.awaitExitUntil(deadline) }.all { it }
    }

    /** After [close], observes actual release of the tracked stream codecs, outside ownership locks. */
    fun whenVideoReleased(onReleased: () -> Unit) {
        val alreadyReleased = synchronized(videoOwnershipLock) {
            check(videoClosed) { "close before observing video release" }
            if (videoDecoders.isEmpty() && closingVideoDecoders.size == 0) true
            else {
                videoReleasedListeners += onReleased
                false
            }
        }
        if (alreadyReleased) runCatching(onReleased)
    }

    private fun onVideoDecoderExit(decoder: VideoDecoder) {
        val listeners = synchronized(videoOwnershipLock) {
            closingVideoDecoders -= decoder
            videoDecoders.entries.removeIf { it.value === decoder }
            if (videoClosed && videoDecoders.isEmpty() && closingVideoDecoders.size == 0) {
                videoReleasedListeners.toList().also { videoReleasedListeners.clear() }
            } else emptyList()
        }
        listeners.forEach { runCatching(it) }
    }

    /** True when this sink paces main-screen frames (smooth video); fixed for its lifetime. */
    val videoPacingEnabled: Boolean get() = videoPacingDelayMillis > 0

    /** Requests a keyframe for stream [type], e.g. after its surface came back. */
    fun refreshPicture(type: Int) {
        videoDecoders[type]?.refreshPicture()
    }

    /** Issue #315: legacy reverse strategy — release stream [type]'s decoder and stop its input pump. */
    fun suspendVideoForReverse(type: Int) {
        videoDecoders[type]?.suspendForReverse()
    }

    /** Issue #315: legacy reverse strategy — cold-start stream [type]'s decoder after the exit delay. */
    fun resumeVideoFromReverse(type: Int) {
        videoDecoders[type]?.resumeFromReverse()
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val id = type to key
            synchronized(mirrorLock) {
                mirrorDecoders.remove(id)?.close()
                if (surface == null) {
                    mirrorSurfaces.remove(id)
                    return
                }
                mirrorSurfaces[id] = surface
            }
            lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
        }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(videoOwnershipLock) {
        if (videoClosed) return emptyList()
        synchronized(mirrorLock) {
            if (mirrorSurfaces.isEmpty()) return emptyList()
            mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
                mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
            }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoOwnershipLock) {
            if (!videoClosed) pendingVideoCodec[type] = codec
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            lastVideoConfig[type] = codec to codecData
            videoDecoder(type).configure(codec, codecData)
            mirrorDecoders(type).forEach { it.configure(codec, codecData) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes)
            mirrorDecoders(type).forEach { it.submit(naluBytes) }
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes, senderNanos, arrivalNanos)
            mirrorDecoders(type).forEach { it.submit(naluBytes) }
        }
    }

    override fun onVideoFrameDropped(type: Int) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoders[type]?.notifyReferenceLost()
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                videoDecoders[type]?.let { retire(type, it) }
                synchronized(mirrorLock) {
                    mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
                }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        synchronized(this) {
            audioRenderers.remove(id)?.close()
            callEchoReferences.remove(id)
        }
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            if (config.audioType == "telephony") {
                // Issue #290: the call volume boost does not need the communication
                // mode, so it runs even when mic compatibility skips the mode.
                boostCallStreamVolume()
                // Issue #343: with the downlink on the music stream, entering the
                // communication mode is the one remaining difference from the
                // audible media path on firmware that takes over output routing
                // in that mode — skip it while the experiment is on.
                if (telephonyDownlinkViaMusic) {
                    runCatching {
                        onAudioDiagnostic("Audio: communication mode skipped for telephony; downlink via music stream (experimental)")
                    }
                } else if (!callMicCompatibility) {
                    enterCommunicationMode(id)
                }
            }
            // Issue #272 follow-up: call echo complaints were undiagnosable because a
            // missing echo reference was silent — the uplink only logs when one is
            // present. State all three inputs whenever a call microphone starts.
            if (config.audioType == TELEPHONY_AUDIO_TYPE) {
                runCatching {
                    onAudioDiagnostic(
                        "Microphone: echo state aecSetting=$callEchoCancellation micCompatibility=$callMicCompatibility " +
                            "referencePresent=${callEchoReferences[id] != null}",
                    )
                }
            }
            val uplink = microphoneUplinks.computeIfAbsent(id) {
                MicrophoneUplink(
                    config = config,
                    onDiagnostic = onAudioDiagnostic,
                    echoReference = if (config.audioType == TELEPHONY_AUDIO_TYPE && !callMicCompatibility) callEchoReferences[id] else null,
                    callMicCompatibility = callMicCompatibility,
                    context = appContext,
                )
            }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            restoreAudioMode(id)
        }
    }

    /**
     * Issue #290: raise the telephony stream to max for the call. This does not
     * need the communication mode, so it also runs when mic compatibility (or
     * the music-stream experiment) skips entering that mode; restoreAudioMode
     * puts the saved volume back when the call ends.
     */
    private fun boostCallStreamVolume() {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (savedCallStreamVolume != null) return
            val callMax = runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) }.getOrNull()
            val callNow = runCatching { manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrNull()
            if (callMax != null && callNow != null && callNow < callMax) {
                savedCallStreamVolume = callNow
                runCatching { manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, callMax, 0) }
                runCatching { onAudioDiagnostic("Audio: call volume boosted stream=VOICE_CALL from=$callNow to=$callMax") }
            }
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            savedAudioMode = manager.mode
            // Issue #343-Q2: the remedy below may turn the speakerphone on; remember
            // the pre-call state so teardown restores it instead of leaking it on.
            savedSpeakerphoneOn = manager.isSpeakerphoneOn
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            earpieceFallbackTried = false
            Log.i("xcertplay-usb", "audio mode $savedAudioMode -> ${manager.mode} for telephony stream=$id")
            runCatching {
                // Some vendor audio services silently refuse the mode change (the
                // readback stays NORMAL); platform echo cancellation hangs off this
                // mode, so flag the refusal explicitly in the report (issue #272).
                onAudioDiagnostic("Audio: mode entered communication from=$savedAudioMode" +
                    " now=${manager.mode} stream=$id" +
                    " modeRefused=${manager.mode != AudioManager.MODE_IN_COMMUNICATION}" +
                    " speakerphone=${manager.isSpeakerphoneOn} scoOn=${manager.isBluetoothScoOn}")
            }
            // Issue #343: the earpiece remedy (upstream #797) only exists on
            // API 24+, where the routing listener can observe it — but a
            // pre-24 head unit in communication mode with the speakerphone
            // off and no SCO link routes VOICE_CALL to a built-in earpiece
            // it does not have, so the call is silent while PCM flows fine
            // (proven on the 5.1.1 reporter's logs: route=streamType=0,
            // volume 7/7, first PCM write done, still no sound). Pre-24 has
            // no routing callback, so derive the same condition from
            // AudioManager state and apply the same loudspeaker remedy,
            // once per call (moveCallToLoudspeaker guards that). It stays
            // off when SCO is on (the call is already audible over BT) or
            // the speakerphone is already on.
            if (realSdkInt() < Build.VERSION_CODES.N &&
                manager.mode == AudioManager.MODE_IN_COMMUNICATION &&
                !manager.isSpeakerphoneOn && !manager.isBluetoothScoOn
            ) {
                moveCallToLoudspeaker(id)
            }
            boostCallStreamVolume()
        }
    }

    /**
     * Port of upstream #797: some phone-chip head units route CarPlay calls to the
     * built-in earpiece, which nobody hears in a car. When detected, force the
     * loudspeaker on. Once per call, only in communication mode.
     */
    private var earpieceFallbackTried = false // guarded by audioModeLock
    private var savedCallStreamVolume: Int? = null // guarded by audioModeLock
    private var savedSpeakerphoneOn: Boolean? = null // guarded by audioModeLock
    private fun moveCallToLoudspeaker(id: AudioStreamId) {
        val manager = audioManager ?: return
        val line = synchronized(audioModeLock) {
            if (communicationModeStream != id || earpieceFallbackTried) return
            earpieceFallbackTried = true
            runCatching { manager.isSpeakerphoneOn = true }
            "Audio: call routed to the earpiece; forced loudspeaker on stream=$id"
        }
        runCatching { onAudioDiagnostic(line) }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream
            if (active != null && id != null && id != active) return
            // Issue #290: the call volume is saved and boosted independently of
            // the communication mode (mic-compatibility calls never enter it),
            // so restore it before the mode early-return below.
            savedCallStreamVolume?.let { saved ->
                savedCallStreamVolume = null
                runCatching { manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, saved, 0) }
                runCatching { onAudioDiagnostic("Audio: call volume restored stream=VOICE_CALL to=$saved") }
            }
            if (active == null) return
            communicationModeStream = null
            val line = try {
                manager.mode = savedAudioMode
                if (manager.mode != savedAudioMode) {
                    // Some vendor audio services drop the first restore after a call
                    // (issue #251: the mode stayed IN_COMMUNICATION, and later calls
                    // were then routed by the car as if still in a call). Retry once
                    // and report whether the readback now matches.
                    manager.mode = savedAudioMode
                    Log.i("xcertplay-usb", "audio mode restore needed a retry, now ${manager.mode}")
                }
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
                "Audio: mode restored requested=$savedAudioMode now=${manager.mode} stream=$active" +
                    " modeRestoreFailed=${manager.mode != savedAudioMode}"
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode $savedAudioMode", error)
                "Audio: mode restore failed requested=$savedAudioMode now=${manager.mode}" +
                    " error=${error.javaClass.simpleName}"
            }
            savedSpeakerphoneOn?.let { saved ->
                savedSpeakerphoneOn = null
                runCatching { manager.isSpeakerphoneOn = saved }
                runCatching { onAudioDiagnostic("Audio: speakerphone restored to=$saved") }
            }
            runCatching { onAudioDiagnostic(line) }
        }
    }

    fun close() {
        synchronized(videoOwnershipLock) {
            videoClosed = true
            videoDecoders.entries.toList().forEach { (type, decoder) -> retire(type, decoder) }
            synchronized(mirrorLock) {
                mirrorDecoders.values.forEach(VideoDecoder::close)
                mirrorDecoders.clear()
                mirrorSurfaces.clear()
            }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        // Audio workers close asynchronously; none may reacquire focus after their sink closes.
        audioFocusCoordinator.close()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        callEchoReferences.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.values.forEach(MicrophoneUplink::close)
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder = synchronized(videoOwnershipLock) {
        videoDecoders[type] ?: run {
            // A decoder that replaces a closed one picks up the stream's codec configuration.
            val decoder = newVideoDecoder(type, surfaces[type] ?: defaultSurface, startImmediately = false)
            videoDecoders[type] = decoder
            lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
            decoder.start()
            decoder
        }
    }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null, startImmediately: Boolean = true) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
        onExit = ::onVideoDecoderExit,
        // Only the main screen goes to the host's SurfaceView; mirrors and the cluster keep their path.
        pacingDelayNanos = if (type == MAIN_SCREEN_TYPE && statsLabel == null) videoPacingDelayMillis * 1_000_000L else 0L,
    ).also { if (startImmediately) it.start() }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        // A replacement owns a fresh ring: the old worker can still finish a blocking write.
        val echoReference = if (callEchoCancellation && format.audioType == TELEPHONY_AUDIO_TYPE && format.sampleRate > 0) {
            EchoReference(format.sampleRate).also { callEchoReferences[id] = it }
        } else {
            callEchoReferences.remove(id)
            null
        }
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            telephonyDownlinkViaMusic,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            echoReference,
            callVoiceFilter && format.audioType == TELEPHONY_AUDIO_TYPE,
            volumePercent = when (format.audioType) {
                "media" -> mediaVolumePercent
                "navigation" -> navigationVolumePercent
                else -> 100
            },
        ).also {
            // Port of upstream #797: if the call lands on the earpiece, move it to the loudspeaker.
            if (format.audioType == TELEPHONY_AUDIO_TYPE) it.onRoutedToEarpiece = { moveCallToLoudspeaker(id) }
            audioRenderers[id] = it
        }
    }

    internal companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
}

/** CarPlay's main screen stream type. */
private const val MAIN_SCREEN_TYPE = 110

/**
 * An offscreen consumer a parked decoder renders to while its SurfaceView is gone. It drops each image
 * on its own thread, so it keeps draining while the main thread waits on a surface handoff. Only the
 * decoder worker creates and closes it, after the codec no longer renders to it.
 */
internal class ParkingOutput(width: Int, height: Int) {
    private val consumerLock = Any()
    private var closed = false
    private val thread = android.os.HandlerThread("carplay-video-parking").apply { start() }
    private val reader: android.media.ImageReader
    val surface: Surface
    /** The looper the consumer drains on, never the main one. */
    val listenerLooper: android.os.Looper get() = thread.looper

    init {
        try {
            reader = android.media.ImageReader.newInstance(
                width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.ImageFormat.PRIVATE, 3,
            )
            reader.setOnImageAvailableListener({ source ->
                synchronized(consumerLock) {
                    if (!closed) source.acquireLatestImage()?.close()
                }
            }, android.os.Handler(thread.looper))
            surface = reader.surface
        } catch (error: Throwable) {
            if (realSdkInt() >= Build.VERSION_CODES.JELLY_BEAN_MR2) thread.quitSafely() else thread.quit()
            throw error
        }
    }

    fun close() {
        try {
            synchronized(consumerLock) {
                if (!closed) {
                    closed = true
                    reader.close()
                }
            }
        } finally {
            if (realSdkInt() >= Build.VERSION_CODES.JELLY_BEAN_MR2) thread.quitSafely() else thread.quit()
        }
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    pacingDelayNanos: Long = 0L,
    /** Called on the worker as its last step, after it has released its codec. */
    private val onExit: (VideoDecoder) -> Unit = {},
) : Closeable {
    private val pacer = FramePacer()
    private val pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    // Per queued presentation time (decode thread only): when it was queued, and whether it is a paced
    // frame's local time rather than the queue time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Set on the paced frames queued just before a pause in the iPhone's frames: the decoder releases a
    // frame only once about two more are queued, so they waited for that pause, which no display delay
    // can hide.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    // The decoder releases a frame only once later input arrives, so a frame queued before a still-screen
    // gap waits for the next one; such waits are not decode time and are left out of the stats.
    private var lastQueuedNanos = 0L
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    // Issue #315: worker-confined; set only by the ReleaseForReverse /
    // ResumeFromReverse jobs, read by feed/changeSurface/recover on the
    // same worker, so no synchronization is needed.
    private var reverseSuspended = false
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    // API 17 (Android 4.2) decoders often ignore csd-0/csd-1; the first frame
    // must carry SPS/PPS in-band or it fails to decode. Set on configure.
    private var inBandSpsPps: ByteArray? = null
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    private var consecutiveInputWithoutOutput = 0
    private var consecutiveDroppedWithoutKeyFrame = 0
    /** Issue #351: stale frames dropped while the pipeline is still rendering. */
    private var staleFramesDropped = 0
    /** Issue #346: consecutive output stalls; forces a decoder rebuild at threshold. */
    private val stallRebuildTracker = DecoderStallRebuildTracker()
    /** Issue #216: when the current wait for an acceptable keyframe began. */
    private var keyframeWaitStartedNs = 0L
    private var lastOutputRenderedNs = 0L
    private var surfaceAttachedAtNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }

    fun start() { thread.start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L) {
        stats.onReceived(nalus.size)
        // Pacing runs on the worker, in queue order: callbacks of a replaced stream may still deliver here.
        queue.offer(VideoJob.Frame(nalus, senderNanos = senderNanos, arrivalNanos = arrivalNanos))
    }

    fun setSurface(surface: Surface?) {
        if (surface != null) {
            queue.discardFrames()
        }
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    /** Issue #315: legacy reverse strategy — fully release the codec and stop the input pump. */
    fun suspendForReverse() {
        queue.offer(VideoJob.ReleaseForReverse)
    }

    /** Issue #315: legacy reverse strategy — cold-start the codec after the exit delay. */
    fun resumeFromReverse() {
        queue.offer(VideoJob.ResumeFromReverse)
    }

    /** Asks the worker to stop rendering to [surface], parking the codec when [park]. */
    fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        return SurfaceDetachRequest(surface, park).also { queue.offer(VideoJob.DetachSurface(it)) }
    }

    /** True once [request] is confirmed or the worker has exited; false when [deadlineNanos] passed first. */
    fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    /** True once the worker has exited, so its codec is released. */
    val exited: Boolean get() = !thread.isAlive

    /**
     * After [close], waits until [deadlineNanos] for the worker to release its codec and exit. An
     * interrupt does not end the wait early; it is restored before returning.
     */
    fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun refreshPicture() {
        queue.offer(VideoJob.RefreshPicture)
    }

    /** A frame was dropped before it reached this decoder (decrypt queue overflow). */
    fun notifyReferenceLost() {
        queue.offer(VideoJob.ReferenceLost)
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                // Issue #351: when frames are still rendering, the
                                // decoder is behind but alive — drop just this stale
                                // frame and keep feeding instead of flushing the whole
                                // queue and waiting for a keyframe (that loop is what
                                // made slow-decode streams stutter). A genuine stall
                                // (nothing rendered recently) keeps the old path.
                                if (System.nanoTime() - lastOutputRenderedNs < MIN_OUTPUT_STALL_NS) {
                                    staleFramesDropped++
                                    if (staleFramesDropped == 1 || staleFramesDropped % 30 == 0) {
                                        report("video stale frame dropped count=$staleFramesDropped (decoder behind, keeping feed)")
                                    }
                                } else {
                                    queue.discardFrames()
                                    recover("video backlog exceeded 800 ms", allowRebuild = false)
                                }
                            } else feed(job.nalus, localTimeOf(job))
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        is VideoJob.ReleaseForReverse -> {
                            queue.discardFrames()
                            releaseDecoder()
                            consecutiveInputWithoutOutput = 0
                            consecutiveDroppedWithoutKeyFrame = 0
                            keyframeWaitStartedNs = 0L
                            referenceChain.reset()
                            reverseSuspended = true
                            report("video: decoder released for reverse (legacy cold start)")
                        }
                        is VideoJob.ResumeFromReverse -> {
                            reverseSuspended = false
                            if (outputSurface != null) {
                                releaseDecoder()
                                lastConfig?.let {
                                    configureDecoder(it)
                                    requestKeyFrameIfDue(force = true)
                                }
                            }
                        }
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        is VideoJob.RefreshPicture -> {
                            lastKeyFrameRequestNs = System.nanoTime()
                            referenceChain.reset()
                            requestKeyFrame()
                        }
                        is VideoJob.Resync -> recover("video queue overflow")
                        // A frame lost upstream broke the reference chain: dependent
                        // frames are rejected from here on, and the loop below keeps
                        // requesting a keyframe until one lands (issue #288).
                        is VideoJob.ReferenceLost -> referenceChain.onReferenceLost()
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    // Issue #315: while suspended for reverse, received frames
                    // are intentionally dropped and 0 are rendered; neither the
                    // degenerate recovery nor the keyframe loop may fire.
                    if (!reverseSuspended) {
                        if (outputSurface != null && stats.shouldRecoverDegeneratePipeline()) {
                            recover("pipeline receiving frames but 0 rendered", allowRebuild = false)
                        } else if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) {
                            requestKeyFrameIfDue()
                        }
                    }
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
            // The codec is gone, so nothing renders to any surface or to the parking consumer any more.
            parking?.close()
            parking = null
            queue.drain().forEach { (it as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED) }
            runCatching { onExit(this) }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        // Issue #326: AutoChips AC83xx chipsets (OMX.atc.video.decoder.avc) deadlock/hang on dynamic CarPlay AVC streams;
        // on those, prioritize the software decoder fallback directly.
        // Issue #324 follow-up: HiSilicon/K3v2 (OMX.k3.video.decoder.avc) is different.
        // Its hardware decoder aborts in ACodec (CHECK(def.nBufferSize >= size)) only
        // when KEY_MAX_INPUT_SIZE forces a port buffer larger than the OMX port's
        // native capacity — and on KitKat it is the ONLY decoder that can decode
        // CarPlay's Main/High CABAC stream (the software decoder is Baseline-only
        // PacketVideo and fails every frame with -2). So for K3 the hardware
        // decoder is used first, with unconstrained native port sizing
        // (tuned = false sets neither KEY_MAX_INPUT_SIZE nor KEY_PRIORITY);
        // software stays only as a last resort if hardware configure throws.
        val defaultBroken = isKnownBrokenHardwareDecoder(mime)
        val nativeSizing = isNativeSizingHardwareDecoder(mime)
        val software = softwareDecoderName(mime)
        // Issue #324: API <= 19 H.264 decoders (such as SoftAVC / OMX.google.h264.decoder on KitKat/JellyBean)
        // or old vendor decoders fail or ignore csd-0/csd-1 without in-band Annex B headers.
        // Prepend SPS/PPS in-band to the first frame (Annex B format).
        inBandSpsPps = if (codec != VideoCodec.H265 && (realSdkInt() <= 19 || defaultBroken)) {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            if (sps.isNotEmpty() && pps.isNotEmpty()) {
                START_CODE + sps + START_CODE + pps
            } else null
        } else null
        val attempts = when {
            nativeSizing -> {
                Log.w(TAG, "default hardware decoder for $mime needs native port sizing on this device; trying hardware without forced input size first")
                listOf(DecoderAttempt(codecName = null, tuned = false)) +
                    software?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
            }
            defaultBroken && software != null -> {
                Log.w(TAG, "default hardware decoder for $mime is known broken on this device; prioritizing software decoder: $software")
                listOf(DecoderAttempt(software, tuned = false)) + listOf(
                    DecoderAttempt(codecName = null, tuned = true),
                    DecoderAttempt(codecName = null, tuned = false),
                )
            }
            else -> {
                listOf(
                    DecoderAttempt(codecName = null, tuned = true),
                    DecoderAttempt(codecName = null, tuned = false),
                ) + software?.let { listOf(DecoderAttempt(it, tuned = false)) }.orEmpty()
            }
        }
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            val decoderName = safeDecoderName(next)
            report("decoder=$decoderName mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=$decoderName mime=$mime size=${width}x$height",
            )
        }
    }

    /** MediaCodec.getName() only exists on API 21+; on older devices it throws NoSuchMethodError. */
    private fun safeDecoderName(codec: MediaCodec): String =
        if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { codec.name }.getOrDefault("unknown")
        } else {
            "unknown"
        }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            reportFailure("stage=configure tuned=${attempt.tuned} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun isKnownBrokenHardwareDecoder(mime: String): Boolean {
        if (mime != MediaFormat.MIMETYPE_VIDEO_AVC) return false
        val defaultDecoder = defaultDecoderName(mime) ?: return false
        // OMX.atc.video.decoder.avc on AutoChips AC83xx head units hangs/deadlocks when decoding dynamic bitstreams.
        return defaultDecoder.startsWith("OMX.atc.video.decoder", ignoreCase = true)
    }

    /**
     * Issue #324 follow-up: OMX.k3.video.decoder.avc on HiSilicon/K3v2
     * (Huawei Ascend P6/P7 etc.) aborts with CHECK(def.nBufferSize >= size)
     * in ACodec.cpp when KEY_MAX_INPUT_SIZE forces a port buffer larger
     * than the OMX port's native capacity — but it is the only decoder on
     * KitKat that handles CarPlay's Main/High CABAC stream, so it must be
     * used with native (unconstrained) port sizing, not skipped.
     */
    private fun isNativeSizingHardwareDecoder(mime: String): Boolean {
        if (mime != MediaFormat.MIMETYPE_VIDEO_AVC) return false
        val defaultDecoder = defaultDecoderName(mime) ?: return false
        return defaultDecoder.startsWith("OMX.k3.video.decoder", ignoreCase = true)
    }

    private fun defaultDecoderName(mime: String): String? {
        if (realSdkInt() >= Build.VERSION_CODES.Q) {
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && mime in it.supportedTypes
            }?.name
        }
        if (realSdkInt() >= 16) {
            @Suppress("DEPRECATION")
            return (0 until MediaCodecList.getCodecCount()).mapNotNull { i ->
                @Suppress("DEPRECATION")
                MediaCodecList.getCodecInfoAt(i)
            }.firstOrNull {
                !it.isEncoder && mime in it.supportedTypes
            }?.name
        }
        return null
    }

    private fun softwareDecoderName(mime: String): String? {
        if (realSdkInt() >= Build.VERSION_CODES.Q) {
            return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }?.name
        }
        // Pre-Q (including T3 real API 19): MediaCodecList(REGULAR_CODECS) is API 21+.
        // Use the API 16 getCodecCount/getCodecInfoAt to find the software decoder
        // (OMX.google.*). Without this, a hardware decoder rejection on T3 has no
        // fallback and video fails entirely.
        if (realSdkInt() >= 16) {
            @Suppress("DEPRECATION")
            return (0 until MediaCodecList.getCodecCount()).mapNotNull { i ->
                @Suppress("DEPRECATION")
                MediaCodecList.getCodecInfoAt(i)
            }.firstOrNull {
                !it.isEncoder && it.name.startsWith("OMX.google.") && mime in it.supportedTypes
            }?.name
        }
        return null
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest) {
        var outcome = DetachOutcome.RELEASED
        try {
            outcome = detachAction(outputSurface, request.surface, request.park, decoder != null)
            when (outcome) {
                DetachOutcome.NOT_RENDERING -> Unit
                DetachOutcome.PARKED -> {
                    if (realSdkInt() < 23) {
                        outcome = DetachOutcome.RELEASED
                        outputSurface = null
                        releaseDecoder()
                        Log.i(TAG, "video decoder detached (surface parking unsupported on API < 23)")
                    } else {
                        try {
                            val consumer = parking ?: ParkingOutput(width, height).also { parking = it }
                            checkNotNull(decoder).setOutputSurface(consumer.surface)
                            outputSurface = consumer.surface
                            Log.i(TAG, "video decoder parked off screen")
                        } catch (error: Exception) {
                            Log.w(TAG, "video decoder could not park; releasing it", error)
                            outcome = DetachOutcome.RELEASED
                            outputSurface = null
                            releaseDecoder()
                        }
                    }
                }
                DetachOutcome.RELEASED -> {
                    outputSurface = null
                    releaseDecoder()
                    Log.i(TAG, "video decoder detached from surface")
                }
            }
        } catch (error: Exception) {
            outcome = DetachOutcome.RELEASED
            outputSurface = null
            releaseDecoder()
            throw error
        } finally {
            request.complete(outcome)
        }
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        stats.reset()
        queue.discardFrames()
        consecutiveInputWithoutOutput = 0
        consecutiveDroppedWithoutKeyFrame = 0
        stallRebuildTracker.reset()
        keyframeWaitStartedNs = 0L
        val now = System.nanoTime()
        lastOutputRenderedNs = now
        surfaceAttachedAtNs = now
        if (reverseSuspended) {
            // Issue #315: a surface churn during reverse must only record
            // the new surface; the codec stays released until the delayed
            // cold start (ResumeFromReverse) runs.
            releaseDecoder()
            return
        }
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null && realSdkInt() >= Build.VERSION_CODES.M) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                referenceChain.reset()
                requestKeyFrameIfDue(force = true)
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let {
            configureDecoder(it)
            requestKeyFrameIfDue(force = true)
        }
    }

    private fun feed(nalus: ByteArray, presentNs: Long = 0L) {
        // Issue #315: the input pump is cut while suspended for reverse —
        // frames must be dropped here, not lazily rebuild the codec below.
        if (reverseSuspended) return
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            consecutiveDroppedWithoutKeyFrame++
            // Issue #216: keyframe requests repeat while waiting, but if the chain
            // tracker and the phone disagree about the reference state (or every
            // keyframe arrives damaged on a lossy link), well-formed frames can be
            // rejected forever without ever triggering a full reset — the picture
            // froze at shown=0 for minutes while rx stayed healthy. Escalate a
            // long wait to the same full recovery the stall paths use.
            val nowNs = System.nanoTime()
            if (keyframeWaitStartedNs == 0L) keyframeWaitStartedNs = nowNs
            if (nowNs - keyframeWaitStartedNs >= KEYFRAME_WAIT_ESCALATION_NS) {
                recover("keyframe wait exceeded ${KEYFRAME_WAIT_ESCALATION_NS / 1_000_000}ms with $consecutiveDroppedWithoutKeyFrame frames dropped")
                return
            }
            if (consecutiveDroppedWithoutKeyFrame >= 60) {
                // If we've dropped ~2 seconds of frames waiting for keyframe, force-request keyframe immediately
                requestKeyFrameIfDue(force = true)
                consecutiveDroppedWithoutKeyFrame = 0
            } else {
                requestKeyFrameIfDue()
            }
            return
        }
        consecutiveDroppedWithoutKeyFrame = 0
        keyframeWaitStartedNs = 0L
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        // API 17: prepend SPS/PPS in-band to the first frame; old decoders
        // ignore csd-0/csd-1 and fail to decode without it.
        val spsPps = inBandSpsPps
        val frameData = if (spsPps != null) {
            inBandSpsPps = null
            Log.i(TAG, "video decoder prepending SPS/PPS in-band to first frame (${spsPps.size} bytes)")
            spsPps + annexB
        } else annexB
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled", allowRebuild = false); return }
        @Suppress("DEPRECATION")
        val input = if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) codec.getInputBuffer(index) else codec.inputBuffers[index]
        checkNotNull(input) { "Decoder input buffer unavailable" }
        input.clear()
        if (frameData.size <= input.remaining()) {
            input.put(frameData)
            // A paced frame carries its local time; MediaCodec returns it with the decoded output.
            val presentationUs = if (presentNs > 0) presentNs / 1000 else System.nanoTime() / 1000
            codec.queueInputBuffer(index, 0, frameData.size, presentationUs, 0)
            val queuedNow = System.nanoTime()
            if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
            lastQueuedNanos = queuedNow
            queuedPresentationUs[queuedSlot] = presentationUs
            queuedAtNanos[queuedSlot] = queuedNow
            queuedPaced[queuedSlot] = presentNs > 0
            queuedBeforePause[queuedSlot] = false
            if (presentNs > 0) {
                // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
                if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                    recentPacedSlots.forEach { if (it >= 0) queuedBeforePause[it] = true }
                }
                lastQueuedLocalNanos = presentNs
                recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
                recentPacedSlots[0] = queuedSlot
            }
            queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
            referenceChain.onQueued()
            consecutiveInputWithoutOutput++
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
        if (consecutiveInputWithoutOutput >= MAX_CONSECUTIVE_INPUT_WITHOUT_OUTPUT) {
            val now = System.nanoTime()
            val elapsedSinceOutputNs = now - lastOutputRenderedNs
            val elapsedSinceSurfaceAttachNs = now - surfaceAttachedAtNs
            // Allow 3.5s warm-up grace period after surface attach to prevent false positives on busy map streams (Issue #315, #320)
            if (elapsedSinceSurfaceAttachNs >= 3_500_000_000L && (lastOutputRenderedNs == 0L || elapsedSinceOutputNs >= MIN_OUTPUT_STALL_NS)) {
                // Issue #346: the first stalls only re-ask for a keyframe (existing
                // behavior); once the tracker counts repeated stalls, force a decoder
                // rebuild even though a keyframe wait is already in progress — on the
                // T3 unit the requested keyframes never unstick the wedged decoder,
                // so the wait never clears and the plain path never rebuilds.
                val forceRebuild = !reverseSuspended && stallRebuildTracker.onStall()
                val reason = "video decoder output stalled: $consecutiveInputWithoutOutput frames queued without output" +
                    if (forceRebuild) " (repeated stalls; forcing decoder rebuild)" else ""
                recover(reason, allowRebuild = false, forceRebuild = forceRebuild)
                return
            }
        }
    }

    private fun recover(reason: String, allowRebuild: Boolean = true, forceRebuild: Boolean = false) {
        // Issue #315: no recovery may resurrect the codec while it is
        // suspended for reverse; the delayed cold start owns the restart.
        if (reverseSuspended) return
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        // Issue #251: while a keyframe wait is already running, rebuilding the
        // decoder races the keyframe's arrival — the rebuild discards the
        // configured codec, an IDR that lands mid-rebuild is wasted, and the
        // wait restarts; on a busy control channel this looped hundreds of
        // times over tens of seconds while frames kept arriving. Only a
        // recovery that does not find a keyframe wait in progress rebuilds;
        // later ones just reset the (already broken) chain and re-ask. A
        // genuinely wedged codec still gets rebuilt once per keyframe cycle:
        // accepting an IDR clears the wait, so the next recovery rebuilds.
        // Queue backlog / frame drops keep the healthy decoder instance and only wait for IDR.
        // Issue #346: a forced rebuild bypasses the keyframe-wait gate: when the
        // decoder is genuinely wedged, the requested keyframes never arrive (or
        // never unstick it), so the wait never clears and the plain path would
        // never rebuild. The stall tracker rate-limits this to one forced rebuild
        // per REBUILD_AFTER_STALLS stalls.
        val rebuild = forceRebuild || (allowRebuild && !referenceChain.needsKeyFrame)
        consecutiveInputWithoutOutput = 0
        consecutiveDroppedWithoutKeyFrame = 0
        keyframeWaitStartedNs = 0L
        lastOutputRenderedNs = System.nanoTime()
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        if (rebuild) releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue(force = true)
    }

    private fun requestKeyFrameIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun queuedSlotOf(presentationUs: Long): Int =
        queuedPresentationUs.indices.firstOrNull { queuedPresentationUs[it] == presentationUs } ?: -1

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    // Parked output goes to the offscreen consumer: it keeps the codec's state, but it is
                    // not shown, so it is neither paced nor counted.
                    val shown = render && outputSurface !== parking?.surface
                    val now = System.nanoTime()
                    val slot = queuedSlotOf(info.presentationTimeUs)
                    val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
                    val heldOverGap = queued in 1 until resumedAtNanos
                    if (queued > 0 && !heldOverGap) stats.onDecodeLatency(now - queued)
                    val delay = pacingDelay
                    val paced = shown && delay != null && slot >= 0 && queuedPaced[slot]
                    val localNs = info.presentationTimeUs * 1000
                    val pauseHeld = paced && queuedBeforePause[slot]
                    val targetNs = if (paced && delay != null) {
                        if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
                        stats.onPacingDelay(delay.nanos)
                        localNs + delay.nanos
                    } else 0L
                    if (realSdkInt() >= 21 && targetNs - now in 1..MAX_PACING_AHEAD_NS) {
                        codec.releaseOutputBuffer(index, targetNs)
                    } else {
                        if (shown && delay != null && !heldOverGap && !pauseHeld) stats.onLate()
                        codec.releaseOutputBuffer(index, render)
                    }
                    if (shown) {
                        stats.onRendered()
                        consecutiveInputWithoutOutput = 0
                        staleFramesDropped = 0
                        stallRebuildTracker.onOutput()
                        lastOutputRenderedNs = now
                    }
                    if (shown && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        consecutiveInputWithoutOutput = 0
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
            // This surface is reused by decoder recovery and later sessions. Never call
            // lockCanvas here: its CPU producer stays connected until Surface destruction,
            // preventing MediaCodec from reconnecting. The host owns disconnect UI.
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 800_000_000L
        // A paced frame more than this ahead is treated as a bad mapping and shown at once.
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        // Longer input gaps are a still screen, not decoding work.
        const val STILL_GAP_NS = 500_000_000L
        // Consecutive frames this far apart in iPhone time are a pause in its frames: about four frame
        // intervals at 30 fps.
        const val SENDER_PAUSE_NS = 120_000_000L
        // Frames before a pause that wait for it: the decoder holds about two, sometimes three.
        const val PAUSE_HELD_FRAMES = 3
        const val MAX_CONSECUTIVE_INPUT_WITHOUT_OUTPUT = 30
        const val MIN_OUTPUT_STALL_NS = 1_500_000_000L
        /** Issue #216: a keyframe wait longer than this escalates to a full recovery. */
        const val KEYFRAME_WAIT_ESCALATION_NS = 5_000_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val telephonyDownlinkViaMusic: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    /** Receives the played call audio so the call microphone can cancel its echo. */
    private val echoReference: EchoReference? = null,
    voiceFilter: Boolean = false,
    /** Called when this call's track is routed to the earpiece; the sink moves the call to the loudspeaker. */
    var onRoutedToEarpiece: (() -> Unit)? = null,
    private var routingListener: Any? = null,
    /** Issues #253/#263: this stream's PCM volume in percent (100 = unchanged). */
    private val volumePercent: Int = 100,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private val pcmChannels = if (format.channels >= 2) 2 else 1
    private val voiceFilter = if (voiceFilter && format.sampleRate > 0) VoiceFilter(format.sampleRate, pcmChannels) else null

    // Keep absent framework types out of Dalvik's erased lambda/field casts on KitKat.
    private var trackAttributes: Any? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    /** Issue #272: state for recovering a decoder whose initial create failed. */
    private var codecMime: String? = null
    private var decoderCreateRetries = 0
    private var lastDecoderCreateRetryNanos = 0L
    /** Issue #272: bundled libopus path for ROMs with no MediaCodec Opus decoder at all. */
    private var softwareOpusDecoder: NativeOpusDecoder? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private var firstPcmWriteReported = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var pcmPeakThisWindow = 0
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    // Issue #170: set once the platform AAC decoder is proven degenerate and swapped out.
    private var softwareAacFallbackDone = false
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) {
                val gap = (now - previous) / 1_000_000L
                var old = maxArrivalGapMs.get()
                while (gap > old && !maxArrivalGapMs.compareAndSet(old, gap)) old = maxArrivalGapMs.get()
            }
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel volume=$volumePercent% focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        if (codec != null || softwareOpusDecoder != null) return
        codecMime = mime
        codec = createConfiguredDecoder(mime, forceSoftware = false)
        if (codec == null) {
            // Issue #272: creating the telephony Opus decoder can fail outright on
            // legacy OMX stacks (native_setup IOException), e.g. raced against the
            // media decoder's release at call start. Fall back to the software
            // decoder right away; feedCodec additionally retries creation while
            // packets keep arriving, so one transient failure cannot mute a stream.
            report("Audio: platform decoder unavailable for $mime; trying software decoder")
            codec = createConfiguredDecoder(mime, forceSoftware = true)
        }
        if (codec == null && mime == MediaFormat.MIMETYPE_AUDIO_OPUS) {
            // Issue #272 (field follow-up): some ROMs have no Opus decoder in
            // MediaCodec at all — the platform create and the OMX.google lookup
            // both come back empty, deterministically, so retries never recover.
            // The project already bundles libopus for the microphone; decode with
            // it and feed the PCM straight to the track.
            softwareOpusDecoder = NativeOpusDecoder.create(format.sampleRate, format.channels)?.also {
                report(
                    "Audio: no platform Opus decoder on this device; using bundled " +
                        "software Opus decoder rate=${format.sampleRate} channels=${format.channels}",
                )
            }
            if (softwareOpusDecoder == null) {
                // Issue #289: this failure used to be silent in reports (logcat
                // only), so a unit whose bundled decoder could not start showed
                // a "ready" stream that decoded zero packets with no explanation.
                report(
                    "Audio: bundled software Opus decoder unavailable on this device " +
                        "(${NativeOpusDecoder.failureReason ?: "unknown reason"}); " +
                        "this stream will stay silent",
                )
            }
        }
    }

    /** Issue #272: bounded, spaced retries of a failed decoder create. */
    private fun retryDecoderCreateIfDue() {
        val mime = codecMime ?: return
        if (decoderCreateRetries >= MAX_DECODER_CREATE_RETRIES) return
        val now = System.nanoTime()
        if (now - lastDecoderCreateRetryNanos < DECODER_CREATE_RETRY_INTERVAL_NANOS) return
        lastDecoderCreateRetryNanos = now
        decoderCreateRetries++
        report("Audio: retrying decoder create attempt=$decoderCreateRetries mime=$mime")
        configureCodec(mime)
    }

    /**
     * Builds, configures and starts an audio decoder without touching [codec], so the
     * degenerate-decoder fallback can construct the replacement before releasing the old one.
     */
    private fun createConfiguredDecoder(mime: String, forceSoftware: Boolean): MediaCodec? {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        val softwareName = if (forceSoftware) findSoftwareDecoderName(mime) else null
        if (forceSoftware && softwareName == null) {
            report("Audio: no software decoder found for $mime on this device; keeping platform decoder")
            return null
        }
        return try {
            MediaCodecStartup.create(
                create = {
                    diagnosticStage = "decoder-create"
                    if (softwareName != null) MediaCodec.createByCodecName(softwareName)
                    else MediaCodec.createDecoderByType(mime)
                },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    /**
     * Issue #170: some vendor AAC decoders (seen on AutoChips AC822X, Android 4.2.2) accept the
     * stream but emit ~1 PCM frame per output buffer instead of ~1024, so the prebuffer start
     * threshold is never reached, the track never starts, and there is silence with no error
     * anywhere. When the decode yield is pathologically low after several seconds of streaming,
     * swap the platform decoder for the software one, once per renderer. Healthy decoders can
     * never trip this: nominal AAC-LC yield is 1024 frames per input.
     */
    private fun maybeFallbackDegenerateDecoder() {
        if (!running) return
        if (format.codec != AudioCodecKind.AAC_LC) return
        if (softwareAacFallbackDone || playbackStarted) return
        if (inputQueued < DEGENERATE_DECODER_MIN_INPUTS) return
        // Less than 1/16th of the nominal yield.
        if (totalWrittenFrames >= inputQueued * 64L) return
        softwareAacFallbackDone = true
        val inputs = inputQueued
        val frames = totalWrittenFrames
        val replacement = createConfiguredDecoder(MediaFormat.MIMETYPE_AUDIO_AAC, forceSoftware = true)
            ?: return
        val old = codec
        codec = replacement
        runCatching { old?.stop() }
        runCatching { old?.release() }
        // Reset decode-side state; the (correctly configured) AudioTrack is untouched, and the
        // queued packets feed the new decoder from the live stream.
        inputQueued = 0
        inputDropped = 0
        outputBuffers = 0
        totalWrittenFrames = 0
        prebufferBytes = 0
        firstPcmLogged = false
        firstPcmWriteReported = false
        firstInputQueuedLogged = false
        lastDecoderOutputMetadata = null
        decoderOutputReports = 0
        report(
            "Audio: platform AAC decoder degenerate (frames=$frames from inputs=$inputs); " +
                "switched to software decoder",
        )
        Log.i(TAG, "audio AAC decoder fallback to software after degenerate platform output")
    }

    /**
     * Pre-Q mirror of the video path's software-decoder lookup: MediaCodecList(REGULAR_CODECS)
     * is API 21+, so on older releases scan the legacy list for the well-known Google software
     * decoder. Dalvik-safe: isSoftwareOnly is only touched on API 29+.
     */
    private fun findSoftwareDecoderName(mime: String): String? {
        if (realSdkInt() >= Build.VERSION_CODES.Q) {
            return runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                    !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
                }?.name
            }.getOrNull()
        }
        if (realSdkInt() >= 16) {
            @Suppress("DEPRECATION")
            return runCatching {
                (0 until MediaCodecList.getCodecCount()).mapNotNull { i ->
                    @Suppress("DEPRECATION")
                    MediaCodecList.getCodecInfoAt(i)
                }.firstOrNull {
                    !it.isEncoder && it.name.startsWith("OMX.google.") && mime in it.supportedTypes
                }?.name
            }.getOrNull()
        }
        return null
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        var attributes: Any? = if (realSdkInt() >= 21) audioAttributesFor(selection, streamOverride) else null
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (streamOverride == 0) {
            attributes = if (realSdkInt() >= 21) audioAttributesFor(selection) else null
            routeLabel = "usage"
            built = if (realSdkInt() >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(checkNotNull(attributes) as AudioAttributes)
                    .setAudioFormat(pcmFormat(encoding, channelMask))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(plan.trackBufferBytes)
                    .build()
            } else {
                val preferredStream = streamType()
                routeLabel = "streamType=$preferredStream"
                LegacyAudioFallback.build(
                    createLegacy = {
                        @Suppress("DEPRECATION")
                        AudioTrack(preferredStream, format.sampleRate, channelMask, encoding,
                            plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                    },
                    isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                    release = { it.release() },
                    createFallback = {
                        routeLabel = "streamType=$preferredStream(fallback=STREAM_MUSIC)"
                        Log.w(TAG, "streamType=$preferredStream rejected by this ROM; falling back to STREAM_MUSIC")
                        @Suppress("DEPRECATION")
                        AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                            plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                    },
                )
            }
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    @Suppress("DEPRECATION")
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = if (realSdkInt() >= 21) audioAttributesFor(selection) else null
                    if (realSdkInt() >= Build.VERSION_CODES.M) {
                        AudioTrack.Builder()
                            .setAudioAttributes(checkNotNull(attributes) as AudioAttributes)
                            .setAudioFormat(pcmFormat(encoding, channelMask))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(plan.trackBufferBytes)
                            .build()
                    } else {
                        val fallbackStream = streamType()
                        LegacyAudioFallback.build(
                            createLegacy = {
                                @Suppress("DEPRECATION")
                                AudioTrack(fallbackStream, format.sampleRate, channelMask, encoding,
                                    plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                            },
                            isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                            release = { it.release() },
                            createFallback = {
                                Log.w(TAG, "streamType=$fallbackStream rejected by this ROM; falling back to STREAM_MUSIC")
                                @Suppress("DEPRECATION")
                                AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                                    plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                            },
                        )
                    }
                },
            )
        }
        track = built
        // Port of upstream #797: listen for the call track being routed to the earpiece
        // (some phone-chip head units do this; nobody hears it in a car). API 24+ only,
        // guarded by SDK check to avoid VerifyError on API 17-23.
        if (format.audioType == AndroidMediaSink.TELEPHONY_AUDIO_TYPE &&
            realSdkInt() >= Build.VERSION_CODES.N) {
            runCatching {
                val listener = android.media.AudioRouting.OnRoutingChangedListener { routing ->
                    val device = routing.routedDevice
                    if (device?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                        onRoutedToEarpiece?.invoke()
                    }
                }
                built.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
                routingListener = listener
            }
        }
        diagnosticStage = "track-attributes"
        trackAttributes = if (realSdkInt() >= 21) attributes?.let {
            audioTrackAttributesForFocus(built, it as AudioAttributes)
        } else null
        diagnosticStage = "track-capacity"
        val capacityBytes = if (realSdkInt() >= 23) built.bufferSizeInFrames * frameBytes else plan.trackBufferBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                (if (realSdkInt() >= 21) attributeDiagnostic(trackAttributes as? AudioAttributes) else "legacyStream=${streamType()} ") +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        // Issue #88: a near-zero STREAM_MUSIC volume is the most common "packets flow but
        // no sound" cause on head units; surface it loudly while the audio starts.
        audioFocusCoordinator.musicVolumeWarning()?.let { report(it) }
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${effectiveUsage(selection)} " +
                "contentType=${effectiveContentType(selection)} " +
                "telephonyViaMusic=$telephonyDownlinkViaMusic " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    /**
     * Issue #343-Q1: with the music-stream experiment on, the call downlink leaves
     * the telephony path on the usage layer too (the legacy stream type is switched
     * in AudioChannelMapper); channel stays PHONE, call mode and mic unchanged.
     */
    private fun effectiveUsage(selection: AudioChannelSelection): Int =
        if (telephonyDownlinkViaMusic && selection.channel == AudioChannel.PHONE) AudioAttributes.USAGE_MEDIA
        else usageFor(selection.channel)

    private fun effectiveContentType(selection: AudioChannelSelection): Int =
        if (telephonyDownlinkViaMusic && selection.channel == AudioChannel.PHONE) AudioAttributes.CONTENT_TYPE_MUSIC
        else contentTypeFor(selection.contentType)

    @RequiresApi(21)
    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..STREAM_ACCESSIBILITY_COMPAT) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(effectiveUsage(selection))
            .setContentType(effectiveContentType(selection))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
            telephonyDownlinkViaMusic = telephonyDownlinkViaMusic,
        )
    }

    @RequiresApi(21)
    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes {
        if (selection.channel == AudioChannel.NAVIGATION &&
            selection.streamType in AudioManager.STREAM_VOICE_CALL..STREAM_ACCESSIBILITY_COMPAT) {
            try {
                return AudioAttributes.Builder().setLegacyStreamType(selection.streamType).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy navigation stream ${selection.streamType} rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(effectiveUsage(selection))
            .setContentType(effectiveContentType(selection))
            .build()
    }

    @RequiresApi(21)
    private fun attributeDiagnostic(attributes: AudioAttributes?): String =
        "usage=${attributes?.usage} contentType=${attributes?.contentType} "

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation normally takes no focus so it overlays media without ducking it, but a
     * lone navigation track takes focus so head units that mute output without focus
     * still play guidance (#214); the coordinator drops it again once media/phone starts.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
            telephonyDownlinkViaMusic = telephonyDownlinkViaMusic,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run {
            // Issue #272: the bundled software decoder bypasses MediaCodec entirely.
            softwareOpusDecoder?.let { decoder ->
                decoder.decode(payload)?.let { pcmBytes ->
                    outputBuffers++
                    writePcm(pcmBytes)
                }
                return
            }
            decoderUnavailablePackets++
            retryDecoderCreateIfDue()
            return
        }
        diagnosticStage = "decoder-input"
        // Upstream #381: while a blocking track write holds this thread, decoded output fills
        // up and the decoder stops taking input. Dropping the packet after one 10 ms wait cut
        // ~21 ms of music every 10-20 s; drain the output and keep waiting instead.
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        @Suppress("DEPRECATION")
        val input = if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
            codec.getInputBuffer(index)
        } else {
            codec.inputBuffers[index]
        } ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        @Suppress("DEPRECATION")
                        val output = if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
                            codec.getOutputBuffer(index)
                        } else {
                            codec.outputBuffers[index]
                        }
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    /**
     * Issues #253/#263: scales this stream's PCM16 by the configured percent so the
     * driver can balance navigation against media even when the head unit offers a
     * single volume. Runs once per writePcm call, before the chunked track writes.
     */
    private fun applyStreamGain(data: ByteArray, offset: Int, length: Int) {
        if (volumePercent == 100 || length < 2) return
        val factor = volumePercent / 100.0
        val end = offset + length - 1
        var i = offset
        while (i < end) {
            val sample = (data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)
            val scaled = (sample * factor).toInt().coerceIn(-32768, 32767)
            data[i] = (scaled and 0xff).toByte()
            data[i + 1] = ((scaled shr 8) and 0xff).toByte()
            i += 2
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        voiceFilter?.process(data, offset, length)
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        applyStreamGain(data, offset, length)
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = if (realSdkInt() >= Build.VERSION_CODES.M) {
                track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            } else {
                track.write(data, offset + written, writeLength)
            }
            if (count > 0 && !firstPcmWriteReported) {
                firstPcmWriteReported = true
                report("Audio: first PCM write audioType=${format.audioType} bytes=$count api=${Build.VERSION.SDK_INT}")
                if (Build.VERSION.SDK_INT <= 22) {
                    val streamSnapshot = channelOverride(mappedChannel ?: AudioChannel.MEDIA).takeIf { it != 0 } ?: streamType()
                    report("Audio: legacy output state " + audioFocusCoordinator.legacyOutputSnapshot(streamSnapshot))
                }
            }
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            // Issue #309: frame counts prove PCM flows but not that it is audible —
            // a phone-side duck stuck at silence looks identical in the stats. Track
            // the window peak so the next report can tell silence from sound.
            pcmPeakThisWindow = maxOf(pcmPeakThisWindow, pcm16Peak(data, offset + written, count))
            written += count
            // Keeps the focus lease alive only while audio is actually flowing.
            audioFocusCoordinator.onPcmRendered()
            // Issue #364: announcement frame activity drives the active-duck
            // experiment; the coordinator ignores media frames and stays
            // inert while the setting is off.
            mappedChannel?.let(audioFocusCoordinator::onAnnouncementFrame)
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
            echoReference?.let { reference ->
                val pending = if (playbackStarted) {
                    totalWrittenFrames - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                } else {
                    null
                }
                reference.append(data, offset + written - count, count, pcmChannels, pending, System.nanoTime())
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.compatUnderruns()
        try {
            track.play()
            playbackStarted = true
        } catch (error: IllegalStateException) {
            Log.w(TAG, "AudioTrack play failed state=${track.state}", error)
        }
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.compatUnderruns() > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            try {
                track.pause()
            } catch (error: IllegalStateException) {
                Log.w(TAG, "AudioTrack pause failed state=${track.state}", error)
            }
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.compatUnderruns() ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${if (realSdkInt() >= 23) currentTrack?.routedDevice?.type ?: -1 else -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${if (realSdkInt() >= 23) currentTrack?.bufferSizeInFrames ?: -1 else -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow pcmPeak=$pcmPeakThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        pcmPeakThisWindow = 0
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
        // Runs on the worker thread once per stats window; see Issue #170.
        diagnosticStage = "decoder-health"
        maybeFallbackDegenerateDecoder()
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        abandonAudioFocus()
        softwareOpusDecoder?.close()
        softwareOpusDecoder = null
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            // Port of upstream #797: remove the routing listener (API 24+ only).
            if (realSdkInt() >= Build.VERSION_CODES.N) {
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    val listener = routingListener as? android.media.AudioRouting.OnRoutingChangedListener
                    if (listener != null) track.removeOnRoutingChangedListener(listener)
                }
            }
            routingListener = null
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private fun AudioTrack.compatUnderruns(): Int =
        if (realSdkInt() >= Build.VERSION_CODES.N) underrunCount else 0

    private companion object {
        const val TAG = "xcertplay-usb"
        // AudioManager.STREAM_ACCESSIBILITY was added in API 26 (value 10). Keep compatibility for API < 26.
        const val STREAM_ACCESSIBILITY_COMPAT = 10
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 1
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        /** Issue #272: decoder-create recovery budget for one audio stream. */
        const val MAX_DECODER_CREATE_RETRIES = 5
        const val DECODER_CREATE_RETRY_INTERVAL_NANOS = 1_000_000_000L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        // Issue #170: inputs before the degenerate-decoder check may fire (~5 s of AAC-LC).
        const val DEGENERATE_DECODER_MIN_INPUTS = 120
    }
}
