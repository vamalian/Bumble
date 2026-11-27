package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

data class MicDiagnosticResult(
    val success: Boolean,
    val audioSource: String,
    val sampleRateHz: Int,
    val bytesCaptured: Int,
    val peakNormalized: Float,
    val rmsDb: Float,
    val hasHardwareAec: Boolean,
    val message: String
)

data class SpeakerDiagnosticResult(
    val success: Boolean,
    val sampleRateHz: Int,
    val bytesWritten: Int,
    val audioFocusGranted: Boolean,
    val message: String
)

/**
 * Full-duplex real-time PCM audio engine for Gemini Live API:
 * - Captures 16 kHz, 16-bit mono little-endian PCM via AudioRecord with VOICE_COMMUNICATION -> MIC fallback
 * - Plays 24 kHz, 16-bit mono little-endian PCM via AudioTrack with AudioFocus & instant barge-in flush
 * - Computes genuine PCM RMS amplitude for both microphone input and speaker output
 */
class LiveAudioEngine(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Capture state
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val isCapturingFlag = AtomicBoolean(false)
    private var captureThread: Thread? = null

    // Playback state
    private data class OutputChunk(val generation: Long, val pcm: ByteArray, val sampleRate: Int)

    private val playbackQueue = LinkedBlockingQueue<OutputChunk>(512)
    private val playbackGeneration = AtomicLong(0L)
    private val isPlaybackRunning = AtomicBoolean(false)
    private val turnCompletePending = AtomicBoolean(false)
    private val trackLock = Any()

    @Volatile
    private var audioTrack: AudioTrack? = null
    @Volatile
    private var currentOutputSampleRate: Int = DEFAULT_OUTPUT_SAMPLE_RATE
    private var playbackThread: Thread? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val hasAudioFocus = AtomicBoolean(false)

    // Observable telemetry
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _inputAmplitude = MutableStateFlow(0f)
    val inputAmplitude: StateFlow<Float> = _inputAmplitude.asStateFlow()

    private val _outputAmplitude = MutableStateFlow(0f)
    val outputAmplitude: StateFlow<Float> = _outputAmplitude.asStateFlow()

    @Volatile
    var isMicMuted: Boolean = false
        private set

    @Volatile
    var isSpeakerMuted: Boolean = false
        private set

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                interruptPlayback()
            }
        }
    }

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startCapture(
        onPcmChunk: (ByteArray) -> Unit,
        onCaptureError: (String) -> Unit
    ): Result<String> {
        if (!hasRecordPermission()) {
            return Result.failure(SecurityException("Microphone permission (RECORD_AUDIO) not granted."))
        }

        stopCapture()

        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuf > 0) maxOf(minBuf * 2, INPUT_CHUNK_BYTES * 2) else INPUT_CHUNK_BYTES * 4

        val candidateSources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )

        var selectedRecord: AudioRecord? = null
        var selectedSourceLabel = "None"
        var lastInitError = "Unable to initialize AudioRecord."

        for ((source, label) in candidateSources) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrElse { e ->
                lastInitError = e.localizedMessage ?: "AudioRecord constructor failed"
                null
            }

            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                val startedOk = runCatching {
                    candidate.startRecording()
                    candidate.recordingState == AudioRecord.RECORDSTATE_RECORDING
                }.getOrElse { e ->
                    lastInitError = e.localizedMessage ?: "startRecording failed"
                    false
                }
                if (startedOk) {
                    selectedRecord = candidate
                    selectedSourceLabel = label
                    break
                } else {
                    runCatching { candidate.release() }
                }
            } else {
                runCatching { candidate?.release() }
            }
        }

        val activeRecord = selectedRecord
            ?: return Result.failure(IllegalStateException(lastInitError))

        audioRecord = activeRecord

        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(activeRecord.audioSessionId)?.apply {
                    enabled = true
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(activeRecord.audioSessionId)?.apply {
                    enabled = true
                }
            }
        }

        isCapturingFlag.set(true)
        _isCapturing.value = true

        val thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val readBuf = ByteArray(INPUT_CHUNK_BYTES)
            var consecutiveErrors = 0
            try {
                while (isCapturingFlag.get()) {
                    val rec = audioRecord ?: break
                    val bytesRead = rec.read(readBuf, 0, readBuf.size)
                    if (bytesRead > 0) {
                        consecutiveErrors = 0
                        if (isMicMuted) {
                            _inputAmplitude.value = 0f
                        } else {
                            val amp = computePcm16NormalizedRms(readBuf, bytesRead)
                            _inputAmplitude.value = amp
                            onPcmChunk(readBuf.copyOf(bytesRead))
                        }
                    } else if (bytesRead < 0) {
                        consecutiveErrors++
                        if (consecutiveErrors >= 5 && isCapturingFlag.get()) {
                            onCaptureError("Microphone stream read error ($bytesRead). Another app may hold audio focus.")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                if (isCapturingFlag.get()) {
                    Log.e(TAG, "AudioRecord capture loop failed", e)
                    onCaptureError(e.localizedMessage ?: "Microphone capture failed.")
                }
            } finally {
                _inputAmplitude.value = 0f
                _isCapturing.value = false
            }
        }.apply {
            name = "BumbleAudioRecord"
            isDaemon = true
        }

        captureThread = thread
        thread.start()
        return Result.success(selectedSourceLabel)
    }

    fun stopCapture() {
        isCapturingFlag.set(false)
        _isCapturing.value = false
        _inputAmplitude.value = 0f

        val rec = audioRecord
        audioRecord = null
        runCatching {
            if (rec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                rec.stop()
            }
        }
        runCatching { captureThread?.join(250L) }
        captureThread = null

        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching { rec?.release() }
    }

    fun setMicMuted(muted: Boolean) {
        isMicMuted = muted
        if (muted) _inputAmplitude.value = 0f
    }

    fun setSpeakerMuted(muted: Boolean) {
        isSpeakerMuted = muted
        if (muted) interruptPlayback()
    }

    fun enqueueOutputAudio(pcmBytes: ByteArray, sampleRate: Int = DEFAULT_OUTPUT_SAMPLE_RATE) {
        if (pcmBytes.isEmpty() || isSpeakerMuted) return
        turnCompletePending.set(false)
        ensurePlaybackWorkerRunning()
        val chunk = OutputChunk(
            generation = playbackGeneration.get(),
            pcm = pcmBytes,
            sampleRate = sampleRate.coerceIn(8000, 48000)
        )
        if (!playbackQueue.offer(chunk)) {
            playbackQueue.poll()
            playbackQueue.offer(chunk)
        }
    }

    fun markServerTurnComplete() {
        turnCompletePending.set(true)
    }

    fun interruptPlayback() {
        playbackGeneration.incrementAndGet()
        turnCompletePending.set(false)
        playbackQueue.clear()
        synchronized(trackLock) {
            val track = audioTrack
            if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                runCatching {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        }
        _isSpeaking.value = false
        _outputAmplitude.value = 0f
        abandonAudioFocus()
    }

    fun stopPlayback() {
        playbackGeneration.incrementAndGet()
        isPlaybackRunning.set(false)
        turnCompletePending.set(false)
        playbackQueue.clear()
        runCatching { playbackThread?.join(250L) }
        playbackThread = null
        releaseAudioTrack()
        _isSpeaking.value = false
        _outputAmplitude.value = 0f
        abandonAudioFocus()
    }

    fun stopAll() {
        stopCapture()
        stopPlayback()
    }

    private fun ensurePlaybackWorkerRunning() {
        if (isPlaybackRunning.compareAndSet(false, true)) {
            val worker = Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                var idlePollsAfterTurnComplete = 0
                try {
                    while (isPlaybackRunning.get()) {
                        val chunk = playbackQueue.poll(60L, TimeUnit.MILLISECONDS)
                        if (chunk == null) {
                            if (_isSpeaking.value) {
                                if (turnCompletePending.get()) {
                                    idlePollsAfterTurnComplete++
                                    if (idlePollsAfterTurnComplete >= 3) {
                                        _isSpeaking.value = false
                                        _outputAmplitude.value = 0f
                                        turnCompletePending.set(false)
                                        idlePollsAfterTurnComplete = 0
                                        abandonAudioFocus()
                                    }
                                }
                            }
                            continue
                        }

                        idlePollsAfterTurnComplete = 0
                        if (chunk.generation != playbackGeneration.get() || isSpeakerMuted) {
                            continue
                        }

                        val track = getOrCreateAudioTrack(chunk.sampleRate) ?: continue
                        requestAudioFocusIfNeeded()
                        _isSpeaking.value = true
                        _outputAmplitude.value = computePcm16NormalizedRms(chunk.pcm, chunk.pcm.size)

                        var offset = 0
                        while (offset < chunk.pcm.size &&
                            isPlaybackRunning.get() &&
                            chunk.generation == playbackGeneration.get() &&
                            !isSpeakerMuted
                        ) {
                            val written = track.write(
                                chunk.pcm,
                                offset,
                                chunk.pcm.size - offset,
                                AudioTrack.WRITE_BLOCKING
                            )
                            if (written <= 0) break
                            offset += written
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "AudioTrack playback loop error", e)
                } finally {
                    _isSpeaking.value = false
                    _outputAmplitude.value = 0f
                    abandonAudioFocus()
                }
            }.apply {
                name = "BumbleAudioTrack"
                isDaemon = true
            }
            playbackThread = worker
            worker.start()
        }
    }

    private fun getOrCreateAudioTrack(sampleRate: Int): AudioTrack? {
        synchronized(trackLock) {
            val existing = audioTrack
            if (existing != null &&
                existing.state == AudioTrack.STATE_INITIALIZED &&
                currentOutputSampleRate == sampleRate
            ) {
                if (existing.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    runCatching { existing.play() }
                }
                return existing
            }

            releaseAudioTrackLocked()

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = if (minBuf > 0) maxOf(minBuf * 2, 16384) else 16384

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val created = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull()

            return if (created != null && created.state == AudioTrack.STATE_INITIALIZED) {
                runCatching { created.play() }
                audioTrack = created
                currentOutputSampleRate = sampleRate
                created
            } else {
                runCatching { created?.release() }
                null
            }
        }
    }

    private fun releaseAudioTrack() {
        synchronized(trackLock) {
            releaseAudioTrackLocked()
        }
    }

    private fun releaseAudioTrackLocked() {
        val track = audioTrack
        audioTrack = null
        if (track != null) {
            runCatching {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                }
                track.stop()
            }
            runCatching { track.release() }
        }
    }

    private fun requestAudioFocusIfNeeded(): Boolean {
        if (hasAudioFocus.get()) return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        hasAudioFocus.set(granted)
        return granted
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus.getAndSet(false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
    }

    /**
     * Real hardware microphone diagnostic for Admin Panel (16 kHz PCM capture check).
     */
    @SuppressLint("MissingPermission")
    suspend fun runMicDiagnostic(durationMs: Long = 1600L): MicDiagnosticResult = withContext(Dispatchers.IO) {
        if (!hasRecordPermission()) {
            return@withContext MicDiagnosticResult(
                success = false,
                audioSource = "None",
                sampleRateHz = INPUT_SAMPLE_RATE,
                bytesCaptured = 0,
                peakNormalized = 0f,
                rmsDb = -96f,
                hasHardwareAec = AcousticEchoCanceler.isAvailable(),
                message = "RECORD_AUDIO permission is not granted."
            )
        }
        if (isCapturingFlag.get()) {
            return@withContext MicDiagnosticResult(
                success = true,
                audioSource = "Active Live Capture",
                sampleRateHz = INPUT_SAMPLE_RATE,
                bytesCaptured = INPUT_CHUNK_BYTES,
                peakNormalized = _inputAmplitude.value,
                rmsDb = amplitudeToDb(_inputAmplitude.value),
                hasHardwareAec = AcousticEchoCanceler.isAvailable(),
                message = "Microphone is actively streaming 16 kHz PCM in your live session."
            )
        }

        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = if (minBuf > 0) maxOf(minBuf * 2, INPUT_CHUNK_BYTES * 2) else INPUT_CHUNK_BYTES * 4

        var rec: AudioRecord? = null
        var srcName = "MIC"
        for ((src, name) in listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )) {
            val cand = runCatching {
                AudioRecord(
                    src,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize
                )
            }.getOrNull()
            if (cand != null && cand.state == AudioRecord.STATE_INITIALIZED) {
                rec = cand
                srcName = name
                break
            } else {
                runCatching { cand?.release() }
            }
        }

        if (rec == null) {
            return@withContext MicDiagnosticResult(
                success = false,
                audioSource = "None",
                sampleRateHz = INPUT_SAMPLE_RATE,
                bytesCaptured = 0,
                peakNormalized = 0f,
                rmsDb = -96f,
                hasHardwareAec = AcousticEchoCanceler.isAvailable(),
                message = "AudioRecord failed to initialize on this hardware."
            )
        }

        var totalBytes = 0
        var peakAmp = 0f
        val buf = ByteArray(INPUT_CHUNK_BYTES)
        try {
            rec.startRecording()
            val deadline = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < deadline) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    totalBytes += n
                    val amp = computePcm16NormalizedRms(buf, n)
                    if (amp > peakAmp) peakAmp = amp
                } else if (n < 0) {
                    break
                }
            }
            val db = amplitudeToDb(peakAmp)
            MicDiagnosticResult(
                success = totalBytes > 0,
                audioSource = srcName,
                sampleRateHz = INPUT_SAMPLE_RATE,
                bytesCaptured = totalBytes,
                peakNormalized = peakAmp,
                rmsDb = db,
                hasHardwareAec = AcousticEchoCanceler.isAvailable(),
                message = if (totalBytes > 0) {
                    "Captured $totalBytes bytes @ 16 kHz ($srcName), peak ${"%.1f".format(db)} dBFS."
                } else {
                    "AudioRecord opened ($srcName) but captured 0 bytes."
                }
            )
        } catch (e: Exception) {
            MicDiagnosticResult(
                success = false,
                audioSource = srcName,
                sampleRateHz = INPUT_SAMPLE_RATE,
                bytesCaptured = totalBytes,
                peakNormalized = peakAmp,
                rmsDb = -96f,
                hasHardwareAec = AcousticEchoCanceler.isAvailable(),
                message = "Mic test error: ${e.localizedMessage}"
            )
        } finally {
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            }
            runCatching { rec.release() }
        }
    }

    /**
     * Real hardware speaker diagnostic playing a clean 24 kHz 16-bit PCM two-tone chime
     * through the same AudioTrack + AudioFocus path used for Gemini Live speech output.
     */
    suspend fun runSpeakerDiagnostic(): SpeakerDiagnosticResult = withContext(Dispatchers.IO) {
        val sampleRate = DEFAULT_OUTPUT_SAMPLE_RATE
        val durationSec = 0.45
        val totalSamples = (sampleRate * durationSec).toInt()
        val pcm = ByteArray(totalSamples * 2)

        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val freq = if (i < totalSamples / 2) 523.25 else 659.25 // C5 -> E5 HUD chime
            val env = when {
                i < 400 -> i / 400.0
                i > totalSamples - 1200 -> (totalSamples - i) / 1200.0
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            val sample = (sin(2.0 * PI * freq * t) * 9000.0 * env).toInt().coerceIn(-32767, 32767)
            pcm[i * 2] = (sample and 0xFF).toByte()
            pcm[i * 2 + 1] = ((sample ushr 8) and 0xFF).toByte()
        }

        val focusGranted = requestAudioFocusIfNeeded()
        val track = getOrCreateAudioTrack(sampleRate)
            ?: return@withContext SpeakerDiagnosticResult(
                success = false,
                sampleRateHz = sampleRate,
                bytesWritten = 0,
                audioFocusGranted = focusGranted,
                message = "Failed to initialize 24 kHz AudioTrack for speaker playback."
            )

        _isSpeaking.value = true
        _outputAmplitude.value = computePcm16NormalizedRms(pcm, pcm.size)
        var writtenTotal = 0
        try {
            var offset = 0
            while (offset < pcm.size) {
                val n = track.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                writtenTotal += n
                offset += n
            }
            delay(480L)
            SpeakerDiagnosticResult(
                success = writtenTotal > 0,
                sampleRateHz = sampleRate,
                bytesWritten = writtenTotal,
                audioFocusGranted = focusGranted,
                message = "Played $writtenTotal bytes @ 24 kHz PCM (AudioFocus=$focusGranted)."
            )
        } catch (e: Exception) {
            SpeakerDiagnosticResult(
                success = false,
                sampleRateHz = sampleRate,
                bytesWritten = writtenTotal,
                audioFocusGranted = focusGranted,
                message = "Speaker test error: ${e.localizedMessage}"
            )
        } finally {
            _isSpeaking.value = false
            _outputAmplitude.value = 0f
            abandonAudioFocus()
        }
    }

    companion object {
        private const val TAG = "LiveAudioEngine"
        const val INPUT_SAMPLE_RATE = 16_000
        const val DEFAULT_OUTPUT_SAMPLE_RATE = 24_000
        const val INPUT_CHUNK_BYTES = 3200 // 100ms of 16kHz 16-bit mono PCM

        fun computePcm16NormalizedRms(bytes: ByteArray, length: Int): Float {
            val sampleCount = length / 2
            if (sampleCount <= 0) return 0f
            var sumSq = 0.0
            var i = 0
            while (i + 1 < length) {
                val low = bytes[i].toInt() and 0xFF
                val high = bytes[i + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toDouble()
                sumSq += sample * sample
                i += 2
            }
            val rms = sqrt(sumSq / sampleCount) / 32768.0
            // Apply mild perceptual gain so normal speech reads comfortably in 0.15..0.95
            return (rms * 3.4).toFloat().coerceIn(0f, 1f)
        }

        private fun amplitudeToDb(amp: Float): Float {
            if (amp <= 0.0001f) return -96f
            return (20f * log10(amp)).coerceIn(-96f, 0f)
        }
    }
}
