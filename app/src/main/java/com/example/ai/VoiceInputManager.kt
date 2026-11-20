package com.example.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Foreground voice capture for standard Gemini conversations using Android SpeechRecognizer.
 * Emits normalized RMS amplitude for the holographic waveform and supports English, Urdu, Hindi,
 * and multilingual speech input.
 */
class VoiceInputManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private val _rmsAmplitude = MutableStateFlow(0f)
    val rmsAmplitude: StateFlow<Float> = _rmsAmplitude.asStateFlow()

    val isSupported: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(
        languageMode: String,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        mainHandler.post {
            stopListening()
            if (!isSupported) {
                onError("On-device speech recognition service is unavailable on this device. Please type your message below.")
                return@post
            }

            _partialText.value = ""
            _rmsAmplitude.value = 0.08f

            try {
                val sr = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = sr
                _isListening.value = true

                sr.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {
                        // Typical rmsdB range is roughly -2f to +10f
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1f)
                        _rmsAmplitude.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        _rmsAmplitude.value = 0.05f
                    }

                    override fun onError(error: Int) {
                        val partial = _partialText.value.trim()
                        cleanup()
                        if (partial.isNotBlank() &&
                            (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)
                        ) {
                            onFinalResult(partial)
                        } else {
                            onError(mapSpeechError(error))
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        cleanup()
                        if (best.isNotBlank()) {
                            onFinalResult(best)
                        } else {
                            onError("Didn't catch that. Tap the core or type your message.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim().orEmpty()
                        if (partial.isNotBlank()) {
                            _partialText.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val languageTag = when (languageMode) {
                    "Urdu (اردو)" -> "ur-PK"
                    "Hindi (हिन्दी)" -> "hi-IN"
                    else -> "en-US"
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                }

                // Small delay so any prior WakeWordRecognizer finishes tearing down cleanly
                mainHandler.postDelayed({
                    if (_isListening.value && recognizer === sr) {
                        sr.startListening(intent)
                    }
                }, 150L)
            } catch (e: Exception) {
                cleanup()
                onError(e.localizedMessage ?: "Failed to start microphone recognition.")
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            cleanup()
        }
    }

    private fun cleanup() {
        mainHandler.removeCallbacksAndMessages(null)
        _isListening.value = false
        _rmsAmplitude.value = 0f
        _partialText.value = ""
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }

    private fun mapSpeechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition network error."
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try speaking clearly."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy; please try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        else -> "Speech input error ($code)."
    }
}
