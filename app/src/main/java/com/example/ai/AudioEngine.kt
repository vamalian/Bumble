package com.example.ai

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Low-latency bidirectional PCM audio engine for Gemini Live API:
 * - Capture: 16kHz, 16-bit PCM mono with hardware AcousticEchoCanceler & NoiseSuppressor
 * - Playback: 24kHz, 16-bit PCM mono with instant flush() for barge-in interruption
 */
class AudioEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private val playbackLock = Any()

    @Volatile
    private var isMicPaused = false

    @Volatile
    private var isSpeakerMuted = false

    private val _inputAmplitude = MutableStateFlow(0f)
    val inputAmplitude: StateFlow<Float> = _inputAmplitude.asStateFlow()

    private val _outputAmplitude = MutableStateFlow(0f)
    val outputAmplitude: StateFlow<Float> = _outputAmplitude.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startCapture(onPcmChunk: (ByteArray) -> Unit): Boolean {
        stopCapture()
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val bufferSize = maxOf(minBuf * 2, CHUNK_BYTES * 4)
        val source = MediaRecorder.AudioSource.VOICE_COMMUNICATION

        val recorder = runCatching {
            AudioRecord(
                source,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.getOrNull() ?: return false

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            }
        }

        audioRecord = recorder
        return try {
            recorder.startRecording()
            captureJob = scope.launch {
                val readBuffer = ByteArray(CHUNK_BYTES)
                while (isActive && recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = recorder.read(readBuffer, 0, readBuffer.size)
                    if (bytesRead > 0) {
                        if (isMicPaused) {
                            _inputAmplitude.value = 0f
                        } else {
                            val chunk = readBuffer.copyOf(bytesRead)
                            _inputAmplitude.value = computeNormalizedRms(chunk, bytesRead)
                            onPcmChunk(chunk)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord capture", e)
            stopCapture()
            false
        }
    }

    fun startPlayback() {
        synchronized(playbackLock) {
            if (audioTrack != null) return
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.play()
            audioTrack = track
        }
    }

    fun writePlaybackPcm(pcm24kMono: ByteArray) {
        if (isSpeakerMuted || pcm24kMono.isEmpty()) {
            _outputAmplitude.value = 0f
            return
        }
        _outputAmplitude.value = computeNormalizedRms(pcm24kMono, pcm24kMono.size)
        synchronized(playbackLock) {
            val track = audioTrack ?: return
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.play() }
            }
            var offset = 0
            while (offset < pcm24kMono.size) {
                val written = track.write(pcm24kMono, offset, pcm24kMono.size - offset)
                if (written <= 0) break
                offset += written
            }
        }
    }

    fun interruptPlayback() {
        _outputAmplitude.value = 0f
        synchronized(playbackLock) {
            val track = audioTrack ?: return
            runCatching {
                track.pause()
                track.flush()
                track.play()
            }
        }
    }

    fun setMicPaused(paused: Boolean) {
        isMicPaused = paused
        if (paused) _inputAmplitude.value = 0f
    }

    fun setSpeakerMuted(muted: Boolean) {
        isSpeakerMuted = muted
        if (muted) interruptPlayback()
    }

    fun resetOutputAmplitude() {
        _outputAmplitude.value = 0f
    }

    fun stopCapture() {
        captureJob?.cancel()
        captureJob = null
        _inputAmplitude.value = 0f
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
    }

    fun stopPlayback() {
        _outputAmplitude.value = 0f
        synchronized(playbackLock) {
            runCatching {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
                audioTrack?.release()
            }
            audioTrack = null
        }
    }

    fun stopAll() {
        isMicPaused = false
        isSpeakerMuted = false
        stopCapture()
        stopPlayback()
    }

    private fun computeNormalizedRms(bytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val sample = ((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toDouble()
            sumSq += sample * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples)
        return (rms / 9000.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "AudioEngine"
        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000
        private const val CHUNK_BYTES = 2048 // ~64ms at 16kHz 16-bit mono
    }
}
