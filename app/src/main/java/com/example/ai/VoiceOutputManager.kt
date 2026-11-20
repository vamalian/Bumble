package com.example.ai

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sin

/**
 * Standard Android TextToSpeech voice output with configurable voice profiles,
 * multilingual locale selection (English, Urdu, Hindi), interruptible playback,
 * and rhythmic waveform envelope for the golden holographic core.
 */
class VoiceOutputManager(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private var pulseJob: Job? = null

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _outputAmplitude = MutableStateFlow(0f)
    val outputAmplitude: StateFlow<Float> = _outputAmplitude.asStateFlow()

    @Volatile
    var isMuted: Boolean = false
        private set

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _isSpeaking.value = true
                        startWaveformEnvelope()
                    }

                    override fun onDone(utteranceId: String?) {
                        stopWaveformEnvelope()
                        _isSpeaking.value = false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        stopWaveformEnvelope()
                        _isSpeaking.value = false
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        stopWaveformEnvelope()
                        _isSpeaking.value = false
                    }
                })
                pendingSpeak?.invoke()
                pendingSpeak = null
            }
        }
    }

    fun speak(
        text: String,
        voiceProfile: String,
        languageMode: String
    ) {
        val clean = sanitizeForSpeech(text)
        if (clean.isBlank() || isMuted) return

        val action = {
            val engine = tts
            if (engine != null && isInitialized) {
                applyVoiceProfile(engine, voiceProfile)
                applyLocale(engine, clean, languageMode)
                val utteranceId = UUID.randomUUID().toString()
                engine.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
            }
        }

        if (isInitialized) {
            action()
        } else {
            pendingSpeak = action
        }
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) stop()
    }

    fun stop() {
        pendingSpeak = null
        stopWaveformEnvelope()
        _isSpeaking.value = false
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        isInitialized = false
    }

    private fun applyVoiceProfile(engine: TextToSpeech, profile: String) {
        when (profile) {
            "Deep Tactical" -> {
                engine.setPitch(0.84f)
                engine.setSpeechRate(1.04f)
            }
            "Warm Balanced" -> {
                engine.setPitch(1.02f)
                engine.setSpeechRate(0.98f)
            }
            "Bright Fast" -> {
                engine.setPitch(1.10f)
                engine.setSpeechRate(1.14f)
            }
            "Calm Natural" -> {
                engine.setPitch(0.95f)
                engine.setSpeechRate(0.90f)
            }
            else -> { // "JARVIS Crisp"
                engine.setPitch(0.92f)
                engine.setSpeechRate(1.06f)
            }
        }
    }

    private fun applyLocale(engine: TextToSpeech, text: String, languageMode: String) {
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        val hasArabicScript = text.any { it in '\u0600'..'\u06FF' }
        val targetLocale = when {
            hasDevanagari || languageMode == "Hindi (हिन्दी)" -> Locale.forLanguageTag("hi-IN")
            hasArabicScript || languageMode == "Urdu (اردو)" -> Locale.forLanguageTag("ur-PK")
            else -> Locale.US
        }
        val res = engine.setLanguage(targetLocale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
    }

    private fun sanitizeForSpeech(raw: String): String {
        return raw
            .replace("**", "")
            .replace("__", "")
            .replace("`", "")
            .replace("#", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun startWaveformEnvelope() {
        pulseJob?.cancel()
        pulseJob = scope.launch {
            var t = 0.0
            while (isActive && _isSpeaking.value) {
                val amp = (0.35 + 0.45 * abs(sin(t * 2.1)) + 0.15 * abs(sin(t * 5.3))).toFloat()
                _outputAmplitude.value = amp.coerceIn(0.2f, 0.95f)
                t += 0.25
                delay(50L)
            }
            _outputAmplitude.value = 0f
        }
    }

    private fun stopWaveformEnvelope() {
        pulseJob?.cancel()
        pulseJob = null
        _outputAmplitude.value = 0f
    }
}
