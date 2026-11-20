package com.example.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WakeWordStatus {
    DISABLED,
    UNAVAILABLE,
    STANDBY_LISTENING,
    TRIGGERED,
    SUSPENDED_BACKGROUND
}

/**
 * Foreground-only "Hey Bumble" wake-word listener.
 * Strictly stops and destroys SpeechRecognizer when the app is paused/backgrounded
 * or when active voice command capture takes microphone ownership.
 */
class WakeWordManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private var isRestartScheduled = false
    private var onWakeDetectedCallback: (() -> Unit)? = null

    private val _status = MutableStateFlow(WakeWordStatus.DISABLED)
    val status: StateFlow<WakeWordStatus> = _status.asStateFlow()

    val isSpeechRecognitionSupported: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun startForegroundListening(onWakeDetected: () -> Unit) {
        mainHandler.post {
            onWakeDetectedCallback = onWakeDetected
            if (!isSpeechRecognitionSupported) {
                _status.value = WakeWordStatus.UNAVAILABLE
                return@post
            }
            if (isActive) return@post
            isActive = true
            _status.value = WakeWordStatus.STANDBY_LISTENING
            mainHandler.postDelayed({
                if (isActive) createAndStartRecognizer()
            }, 250L)
        }
    }

    fun stopListening(backgrounded: Boolean = false) {
        mainHandler.post {
            isActive = false
            isRestartScheduled = false
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizer()
            _status.value = if (backgrounded) {
                WakeWordStatus.SUSPENDED_BACKGROUND
            } else {
                WakeWordStatus.DISABLED
            }
        }
    }

    private fun createAndStartRecognizer() {
        if (!isActive) return
        destroyRecognizer()
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (isActive) _status.value = WakeWordStatus.STANDBY_LISTENING
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (!isActive) return
                    val delay = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_AUDIO -> 1500L
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            isActive = false
                            _status.value = WakeWordStatus.DISABLED
                            return
                        }
                        else -> 800L
                    }
                    scheduleRestart(delay)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (matchesWakePhrase(matches)) {
                        triggerWake()
                    } else if (isActive) {
                        scheduleRestart(400L)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    if (matchesWakePhrase(matches)) {
                        triggerWake()
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            sr.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Wake-word recognizer failed to start", e)
            _status.value = WakeWordStatus.UNAVAILABLE
            isActive = false
        }
    }

    private fun matchesWakePhrase(candidates: List<String>): Boolean {
        return candidates.any { raw ->
            val text = raw.lowercase().trim()
            text.contains("hey bumble") ||
                text.contains("hi bumble") ||
                text.contains("ok bumble") ||
                text.contains("hey bumbles") ||
                text == "bumble"
        }
    }

    private fun triggerWake() {
        if (!isActive) return
        isActive = false
        _status.value = WakeWordStatus.TRIGGERED
        destroyRecognizer()
        mainHandler.postDelayed({
            onWakeDetectedCallback?.invoke()
        }, 180L)
    }

    private fun scheduleRestart(delayMs: Long) {
        if (!isActive || isRestartScheduled) return
        isRestartScheduled = true
        mainHandler.postDelayed({
            isRestartScheduled = false
            if (isActive) createAndStartRecognizer()
        }, delayMs)
    }

    private fun destroyRecognizer() {
        runCatching {
            recognizer?.cancel()
            recognizer?.destroy()
        }
        recognizer = null
    }

    companion object {
        private const val TAG = "WakeWordManager"
    }
}
