package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.shilapi.xcertplay.compat.realSdkInt
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlaySocketBinder
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
    /** The call's far-end audio; when set, telephony capture runs DiPlay's own echo canceller. */
    private val echoReference: EchoReference? = null,
    private val echoCancellerFactory: (Int, Int, Int) -> CallEchoCanceller? = { frame, rate, tail ->
        SpeexEchoCanceller.create(frame, rate, tail)
    },
    private val callMicCompatibility: Boolean = false,
    private val context: android.content.Context? = null,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })

    /**
     * Echo-canceller outcomes decide call-echo complaints (issue #272), so they belong in
     * the exported diagnostic report, not only in logcat where reports never see them.
     * Guarded: a diagnostic failure must never break capture (issue #272 hardening).
     */
    private fun diagnose(message: String) {
        Log.i(TAG, message)
        runCatching { onDiagnostic(message) }
    }
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    @Volatile private var echoCanceller: CallEchoCanceller? = null
    private var thread: Thread? = null
    /** The audio source actually used by the current recorder; null before start. */
    @Volatile private var usedSource: Int? = null
    @Volatile private var actualSampleRate: Int = config.sampleRate

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val candidateRates = when (config.sampleRate) {
            // Issue #360: some legacy drivers refuse the min-buffer query for
            // the requested rate outright (an RK3188 returns -1 for 32000), so
            // a request without a fallback never starts capturing at all.
            24000 -> listOf(24000, 48000, 16000)
            32000 -> listOf(32000, 48000, 16000)
            else -> listOf(config.sampleRate)
        }
        var selectedRate = config.sampleRate
        var minBuffer = -1
        for (rate in candidateRates) {
            val min = AudioRecord.getMinBufferSize(
                rate,
                channelMask,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
            )
            if (min > 0) {
                selectedRate = rate
                minBuffer = min
                break
            }
        }
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }
        actualSampleRate = selectedRate
        if (selectedRate != config.sampleRate) {
            Log.i(TAG, "microphone hardware sampleRate $selectedRate selected for requested ${config.sampleRate}")
        }

        val source = when (config.audioType) {
            // Issue #102: if VOICE_COMMUNICATION proved silent-broken on this device,
            // fall back to MIC for telephony uplinks instead of capturing zeroes forever.
            "telephony" -> if (callMicCompatibility || voiceCommunicationSilentBroken) {
                Log.i(TAG, "microphone telephony using MIC (compatibility=$callMicCompatibility broken=$voiceCommunicationSilentBroken)")
                MediaRecorder.AudioSource.MIC
            } else {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        // Ceiling division: a 48000 capture for a 32000 request needs 1.5x the
        // frame bytes per frame period; the plain integer ratio rounds to 1.
        val scale = if (config.sampleRate > 0) {
            maxOf(1, (actualSampleRate + config.sampleRate - 1) / config.sampleRate)
        } else {
            1
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4 * scale)
        // Some head units throw on VOICE_RECOGNITION at build(); VOICE_COMMUNICATION works there.
        val nextRecorder = createRecorder(source, channelMask, bufferSize)
            ?: if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                stats.useVoiceCommunicationSource()
                createRecorder(MediaRecorder.AudioSource.VOICE_COMMUNICATION, channelMask, bufferSize)
            } else {
                null
            }
        if (nextRecorder == null) {
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            AirPlaySocketBinder.datagram(config.host, "CarPlay microphone uplink")
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        usedSource = source
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            if (config.audioType == "telephony" && !callMicCompatibility) {
                echoCanceller = createEchoCanceller()
                effects = voiceEffects(nextRecorder.audioSessionId)
                // Report the final state (voiceEffects can still veto the software
                // canceller when the platform AEC cannot be disabled).
                diagnose(
                    "Microphone: echo canceller active=${echoCanceller != null} " +
                        "referencePresent=${echoReference != null} tail=${ECHO_TAIL_MILLIS}ms",
                )
            }
            nextRecorder.startRecording()
            stats.started(routeType(nextRecorder))
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: SecurityException) {
            Log.e(TAG, "microphone permission denied", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun createRecorder(source: Int, channelMask: Int, bufferSize: Int): AudioRecord? {
        val recorder = try {
            if (realSdkInt() >= android.os.Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AndroidAudioFormat.Builder()
                            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(actualSampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    source,
                    actualSampleRate,
                    channelMask,
                    AndroidAudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "microphone permission denied source=$source", error)
            stats.failure(MicrophoneFailureStage.RECORDER_CREATION, error)
            return null
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed source=$source", error)
            stats.failure(MicrophoneFailureStage.RECORDER_CREATION, error)
            return null
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize source=$source")
            stats.failure(MicrophoneFailureStage.RECORDER_INITIALIZATION, code = recorder.state)
            recorder.release()
            return null
        }
        return recorder
    }

    private fun createEchoCanceller(): CallEchoCanceller? {
        val reference = echoReference ?: return null
        if (config.channels != 1 || config.sampleRate != reference.sampleRate) {
            diagnose(
                "Microphone: echo canceller skipped rate=${config.sampleRate} channels=${config.channels} " +
                    "referenceRate=${reference.sampleRate}",
            )
            return null
        }
        return echoCancellerFactory(config.frameBytes / 2, config.sampleRate, ECHO_TAIL_MILLIS)
    }

    private fun voiceEffects(sessionId: Int): List<AudioEffect> {
        var aec: AudioEffect? = null
        if (echoCanceller != null) {
            // VOICE_COMMUNICATION can enable AEC by default. Keep its controller alive and
            // explicitly disable it while Speex runs, rather than stacking two cancellers.
            val platformAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(true)
            if (platformAvailable) {
                aec = configuredEffect("AEC", false) { AcousticEchoCanceler.create(sessionId) }
                if (aec == null) {
                    echoCanceller?.close()
                    echoCanceller = null
                    diagnose("Microphone: software echo canceller dropped: platform AEC could not be disabled")
                }
            }
        }
        if (echoCanceller == null) {
            aec = enabledEffect("AEC") {
                if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
            }
        }
        return listOfNotNull(aec, enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        })
    }

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? =
        configuredEffect(name, true, create)

    private fun configuredEffect(name: String, enabled: Boolean, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(enabled)
                if (status == AudioEffect.SUCCESS && effect.enabled == enabled) {
                    diagnose("Microphone: effect=$name enabled=$enabled")
                    return effect
                }
                diagnose("Microphone: effect=$name could not be ${if (enabled) "enabled" else "disabled"} status=$status")
            } else {
                diagnose("Microphone: effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val rawBuffer = if (actualSampleRate != config.sampleRate) {
            // Size the capture buffer by the rate ratio: input bytes per frame
            // period scale with the capture rate, so one full read converts
            // into readBuffer without the converters truncating its tail.
            ByteArray(readBuffer.size * actualSampleRate / config.sampleRate)
        } else {
            readBuffer
        }
        val counters = MicrophoneCounters()
        val routeInfo = { routeType(recorder) }
        var canceller = echoCanceller
        val clock = canceller?.let { CaptureClock(actualSampleRate) }
        val reference = canceller?.let { ShortArray(it.frameSamples) }
        var filled = 0
        try {
            while (running.get()) {
                stats.reading()
                val count = if (realSdkInt() >= android.os.Build.VERSION_CODES.M) {
                    recorder.read(rawBuffer, 0, rawBuffer.size, AudioRecord.READ_BLOCKING)
                } else {
                    recorder.read(rawBuffer, 0, rawBuffer.size)
                }
                stats.read(count)
                if (count > 0) clock?.read(recorder, count / 2)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo)
                    continue
                }
                val processedCount = when {
                    actualSampleRate == 48000 && config.sampleRate == 24000 ->
                        downsample48To24(rawBuffer, count, readBuffer)
                    actualSampleRate == 16000 && config.sampleRate == 24000 ->
                        upsample16To24(rawBuffer, count, readBuffer)
                    actualSampleRate == 48000 && config.sampleRate == 32000 ->
                        downsample48To32(rawBuffer, count, readBuffer)
                    actualSampleRate == 16000 && config.sampleRate == 32000 ->
                        upsample16To32(rawBuffer, count, readBuffer)
                    else -> count
                }
                var offset = 0
                while (offset < processedCount && running.get()) {
                    val copied = minOf(frame.size - filled, processedCount - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        if (canceller != null && clock != null && reference != null) {
                            // Ask slightly ahead of the frame so speaker latency the clock misses stays inside the tail.
                            echoReference?.read(reference, clock.frameEndNs(offset / 2) + ECHO_REFERENCE_LEAD_NS)
                            val processed = try {
                                canceller.process(frame, reference)
                            } catch (error: RuntimeException) {
                                Log.w(TAG, "microphone native echo processing failed; restoring platform AEC", error)
                                false
                            } catch (error: LinkageError) {
                                Log.w(TAG, "microphone native echo processing unavailable; restoring platform AEC", error)
                                false
                            }
                            if (!processed) {
                                restorePlatformEchoCancellation()
                                canceller = null
                            }
                        }
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            running.set(false)
            release()
        }
    }

    private fun isFrameSilent(frame: ByteArray): Boolean {
        for (i in 0 until frame.size - 1 step 2) {
            val sample = ((frame[i + 1].toInt() shl 8) or (frame[i].toInt() and 0xFF)).toShort().toInt()
            if (sample > 64 || sample < -64) return false
        }
        return true
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        stats.frameSilent(isFrameSilent(frame))
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.rtpSamplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = if (realSdkInt() >= 23)
        runCatching { recorder.routedDevice?.type }.getOrNull() else null

    /** Capture can continue with the same recorder if the optional native processor stops working. */
    @Synchronized
    private fun restorePlatformEchoCancellation() {
        val current = echoCanceller ?: return
        echoCanceller = null
        current.close()
        if (!running.get()) return // close/release already owns all recorder effects.
        val aec = effects.filterIsInstance<AcousticEchoCanceler>().firstOrNull()
        if (aec != null) {
            val enabled = runCatching { aec.setEnabled(true) == AudioEffect.SUCCESS && aec.enabled }.getOrDefault(false)
            if (enabled) {
                Log.i(TAG, "microphone native echo processing stopped; platform AEC restored")
                return
            }
            effects = effects - aec
            releaseEffect(aec)
        }
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recorder!!.audioSessionId) else null
        }?.let { effects = effects + it }
        Log.i(TAG, "microphone native echo processing stopped; using platform effects")
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        // Issue #102: if a telephony uplink on VOICE_COMMUNICATION never captured any
        // non-silent audio, the source is broken on this device — remember so the next
        // telephony uplink uses MIC instead of repeating the silent capture.
        if (config.audioType == "telephony" &&
            usedSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION &&
            !voiceCommunicationSilentBroken &&
            stats.neverReceivedAudio() &&
            stats.silentFrameCount() >= BROKEN_SILENCE_FRAMES
        ) {
            voiceCommunicationSilentBroken = true
            val msg = "microphone telephony VOICE_COMMUNICATION captured only silence" +
                " (${stats.silentFrameCount()} frames); future telephony uplinks will use MIC"
            Log.w(TAG, msg)
            onDiagnostic(msg)
            context?.let { ctx ->
                runCatching {
                    val prefs = ctx.getSharedPreferences("xcertplay_airplay", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putBoolean("call_mic_compatibility", true).apply()
                }
            }
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        echoCanceller?.close()
        echoCanceller = null
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    internal companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
        const val ECHO_TAIL_MILLIS = 250
        const val ECHO_REFERENCE_LEAD_NS = 30_000_000L
        /**
         * Issue #102: set when a telephony uplink on VOICE_COMMUNICATION captured nothing
         * but silence (e.g. GKUI head units whose driver yields zeroes for that source).
         * Later telephony uplinks skip the broken source and use MIC directly.
         */
        @Volatile var voiceCommunicationSilentBroken = false
        /** Minimum silent frames before declaring VOICE_COMMUNICATION broken (~4s at 20ms). */
        const val BROKEN_SILENCE_FRAMES = 200L

        internal fun downsample48To24(input: ByteArray, inputCount: Int, output: ByteArray): Int {
            val samplesIn = inputCount / 2
            val samplesOut = samplesIn / 2
            val maxOut = minOf(samplesOut, output.size / 2)
            for (i in 0 until maxOut) {
                val inIdx = i * 4
                val s0 = ((input[inIdx].toInt() and 0xFF) or (input[inIdx + 1].toInt() shl 8)).toShort().toInt()
                val s1 = ((input[inIdx + 2].toInt() and 0xFF) or (input[inIdx + 3].toInt() shl 8)).toShort().toInt()
                val avg = (s0 + s1) / 2
                val outIdx = i * 2
                output[outIdx] = (avg and 0xFF).toByte()
                output[outIdx + 1] = ((avg shr 8) and 0xFF).toByte()
            }
            return maxOut * 2
        }

        internal fun upsample16To24(input: ByteArray, inputCount: Int, output: ByteArray): Int {
            val samplesIn = inputCount / 2
            val pairs = samplesIn / 2
            val maxPairs = minOf(pairs, output.size / 6)
            for (i in 0 until maxPairs) {
                val inIdx = i * 4
                val s0 = ((input[inIdx].toInt() and 0xFF) or (input[inIdx + 1].toInt() shl 8)).toShort().toInt()
                val s1 = ((input[inIdx + 2].toInt() and 0xFF) or (input[inIdx + 3].toInt() shl 8)).toShort().toInt()
                val mid = (s0 + s1) / 2
                val outIdx = i * 6
                output[outIdx] = (s0 and 0xFF).toByte()
                output[outIdx + 1] = ((s0 shr 8) and 0xFF).toByte()
                output[outIdx + 2] = (mid and 0xFF).toByte()
                output[outIdx + 3] = ((mid shr 8) and 0xFF).toByte()
                output[outIdx + 4] = (s1 and 0xFF).toByte()
                output[outIdx + 5] = ((s1 shr 8) and 0xFF).toByte()
            }
            return maxPairs * 6
        }

        internal fun downsample48To32(input: ByteArray, inputCount: Int, output: ByteArray): Int {
            val samplesIn = inputCount / 2
            val groups = samplesIn / 3
            val maxGroups = minOf(groups, output.size / 4)
            for (g in 0 until maxGroups) {
                val inIdx = g * 6
                val s0 = ((input[inIdx].toInt() and 0xFF) or (input[inIdx + 1].toInt() shl 8)).toShort().toInt()
                val s1 = ((input[inIdx + 2].toInt() and 0xFF) or (input[inIdx + 3].toInt() shl 8)).toShort().toInt()
                val s2 = ((input[inIdx + 4].toInt() and 0xFF) or (input[inIdx + 5].toInt() shl 8)).toShort().toInt()
                val outIdx = g * 4
                output[outIdx] = (s0 and 0xFF).toByte()
                output[outIdx + 1] = ((s0 shr 8) and 0xFF).toByte()
                val avg = (s1 + s2) / 2
                output[outIdx + 2] = (avg and 0xFF).toByte()
                output[outIdx + 3] = ((avg shr 8) and 0xFF).toByte()
            }
            return maxGroups * 4
        }

        internal fun upsample16To32(input: ByteArray, inputCount: Int, output: ByteArray): Int {
            val samplesIn = inputCount / 2
            val pairs = samplesIn / 2
            val maxPairs = minOf(pairs, output.size / 8)
            for (i in 0 until maxPairs) {
                val inIdx = i * 4
                val s0 = ((input[inIdx].toInt() and 0xFF) or (input[inIdx + 1].toInt() shl 8)).toShort().toInt()
                val s1 = ((input[inIdx + 2].toInt() and 0xFF) or (input[inIdx + 3].toInt() shl 8)).toShort().toInt()
                val mid = (s0 + s1) / 2
                val outIdx = i * 8
                output[outIdx] = (s0 and 0xFF).toByte()
                output[outIdx + 1] = ((s0 shr 8) and 0xFF).toByte()
                output[outIdx + 2] = (mid and 0xFF).toByte()
                output[outIdx + 3] = ((mid shr 8) and 0xFF).toByte()
                output[outIdx + 4] = (s1 and 0xFF).toByte()
                output[outIdx + 5] = ((s1 shr 8) and 0xFF).toByte()
                output[outIdx + 6] = (s1 and 0xFF).toByte()
                output[outIdx + 7] = ((s1 shr 8) and 0xFF).toByte()
            }
            return maxPairs * 8
        }
    }
}

/**
 * When each captured microphone sample was heard, on System.nanoTime's clock. Uses the recorder's
 * timestamp when the platform has one, otherwise assumes the newest sample arrived [FALLBACK_LATENCY_NS] ago.
 */
internal class CaptureClock(private val sampleRate: Int) {
    private val timestamp = if (realSdkInt() >= android.os.Build.VERSION_CODES.N) android.media.AudioTimestamp() else null
    private var samplesRead = 0L
    private var lastReadSamples = 0
    private var lastReadEndNs = 0L

    fun read(recorder: AudioRecord, samples: Int) {
        samplesRead += samples
        lastReadSamples = samples
        val now = System.nanoTime()
        val ts = timestamp
        val stamped = if (realSdkInt() >= android.os.Build.VERSION_CODES.N && ts != null) {
            runCatching {
                recorder.getTimestamp(ts, android.media.AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
            }.getOrDefault(false)
        } else false
        lastReadEndNs = if (stamped && ts != null && ts.nanoTime > 0) {
            ts.nanoTime + (samplesRead - ts.framePosition) * 1_000_000_000L / sampleRate
        } else {
            now - FALLBACK_LATENCY_NS
        }
    }

    /** The capture time of the end of the first [samplesIntoRead] samples of the latest read. */
    fun frameEndNs(samplesIntoRead: Int): Long =
        lastReadEndNs - (lastReadSamples - samplesIntoRead).coerceAtLeast(0) * 1_000_000_000L / sampleRate

    internal companion object {
        const val FALLBACK_LATENCY_NS = 20_000_000L
    }
}
