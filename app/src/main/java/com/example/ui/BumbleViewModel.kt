package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BumbleApp
import com.example.ai.ConnectionTestResult
import com.example.ai.CoreState
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiLiveListener
import com.example.ai.LiveConnectionState
import com.example.ai.WakeWordStatus
import com.example.assistant.InstalledAppInfo
import com.example.assistant.MathEvaluator
import com.example.assistant.WeatherResult
import com.example.assistant.WebSearchResult
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.ConversationSessionEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.NoteEntity
import com.example.data.local.ReminderEntity
import com.example.data.local.BumbleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID

data class LiveTranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "tool", "system"
    val text: String,
    val toolName: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class VisionUiState(
    val previewBitmap: Bitmap? = null,
    val analysisResult: String = "",
    val isAnalyzing: Boolean = false,
    val error: String? = null,
    val sourceLabel: String = "No frame captured"
)

data class ToolsUiState(
    val weatherResult: WeatherResult? = null,
    val isLoadingWeather: Boolean = false,
    val weatherError: String? = null,
    val searchResult: WebSearchResult? = null,
    val isSearching: Boolean = false,
    val searchError: String? = null,
    val translationResult: String = "",
    val isTranslating: Boolean = false,
    val plannerResult: String = "",
    val isPlanning: Boolean = false,
    val calcResult: String = "",
    val calcError: String? = null,
    val actionFeedback: String? = null
)

class BumbleViewModel(application: Application) : AndroidViewModel(application), GeminiLiveListener {

    private val app = application as BumbleApp
    private val conversationDao = app.database.conversationDao()
    private val memoryDao = app.database.memoryDao()
    private val noteDao = app.database.noteDao()
    private val reminderDao = app.database.reminderDao()
    private val secureKeyStore = app.secureKeyStore
    private val settingsDataStore = app.settingsDataStore
    private val audioEngine = app.audioEngine
    private val wakeWordManager = app.wakeWordManager
    private val geminiRest = app.geminiRestClient
    private val actionExecutor = app.actionExecutor
    private val externalTools = app.externalTools

    private val liveClient = GeminiLiveClient(this)

    val settings: StateFlow<BumbleSettings> = settingsDataStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, BumbleSettings())

    val sessions: StateFlow<List<ConversationSessionEntity>> = conversationDao.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val memories: StateFlow<List<MemoryEntity>> = memoryDao.observeAllMemories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val notes: StateFlow<List<NoteEntity>> = noteDao.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reminders: StateFlow<List<ReminderEntity>> = reminderDao.observeReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val wakeWordStatus: StateFlow<WakeWordStatus> = wakeWordManager.status

    private val _coreState = MutableStateFlow(CoreState.IDLE)
    val coreState: StateFlow<CoreState> = _coreState.asStateFlow()

    private val _connectionState = MutableStateFlow(LiveConnectionState.DISCONNECTED)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _statusBanner = MutableStateFlow("Core Standby — Ready")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _liveTranscript = MutableStateFlow<List<LiveTranscriptEntry>>(emptyList())
    val liveTranscript: StateFlow<List<LiveTranscriptEntry>> = _liveTranscript.asStateFlow()

    private val _isMicPaused = MutableStateFlow(false)
    val isMicPaused: StateFlow<Boolean> = _isMicPaused.asStateFlow()

    private val _isSpeakerMuted = MutableStateFlow(false)
    val isSpeakerMuted: StateFlow<Boolean> = _isSpeakerMuted.asStateFlow()

    private val _hasApiKey = MutableStateFlow(secureKeyStore.hasApiKey())
    val hasApiKey: StateFlow<Boolean> = _hasApiKey.asStateFlow()

    private val _maskedApiKey = MutableStateFlow(secureKeyStore.getMaskedApiKey())
    val maskedApiKey: StateFlow<String> = _maskedApiKey.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<ConnectionTestResult?>(null)
    val connectionTestResult: StateFlow<ConnectionTestResult?> = _connectionTestResult.asStateFlow()

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _selectedSessionMessages = MutableStateFlow<List<ConversationMessageEntity>>(emptyList())
    val selectedSessionMessages: StateFlow<List<ConversationMessageEntity>> = _selectedSessionMessages.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _visionState = MutableStateFlow(VisionUiState())
    val visionState: StateFlow<VisionUiState> = _visionState.asStateFlow()

    private val _toolsState = MutableStateFlow(ToolsUiState())
    val toolsState: StateFlow<ToolsUiState> = _toolsState.asStateFlow()

    val audioAmplitude: StateFlow<Float> = combine(
        audioEngine.inputAmplitude,
        audioEngine.outputAmplitude,
        _coreState
    ) { inputAmp, outputAmp, state ->
        when (state) {
            CoreState.SPEAKING -> maxOf(outputAmp, 0.22f)
            CoreState.LISTENING -> maxOf(inputAmp, 0.10f)
            CoreState.THINKING -> 0.45f
            else -> 0.05f
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.05f)

    private var activeSessionId: Long? = null
    private var isAppInForeground = false
    private var speakingTimeoutJob: Job? = null
    private var inputTranscriptBuffer = StringBuilder()
    private var outputTranscriptBuffer = StringBuilder()

    init {
        refreshInstalledApps()
    }

    fun refreshInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            _installedApps.value = actionExecutor.listInstalledApps()
        }
    }

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun onAppForegrounded() {
        isAppInForeground = true
        evaluateWakeWordStandby()
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        wakeWordManager.stopListening(backgrounded = true)
        if (_connectionState.value != LiveConnectionState.DISCONNECTED) {
            endLiveSession(dueToBackground = true)
        } else {
            audioEngine.stopAll()
        }
    }

    fun evaluateWakeWordStandby() {
        val canListen = isAppInForeground &&
            settings.value.wakeWordEnabled &&
            hasMicPermission() &&
            _connectionState.value == LiveConnectionState.DISCONNECTED

        if (canListen) {
            wakeWordManager.startForegroundListening {
                viewModelScope.launch(Dispatchers.Main) {
                    if (isAppInForeground && _connectionState.value == LiveConnectionState.DISCONNECTED) {
                        startLiveSession()
                    }
                }
            }
        } else {
            wakeWordManager.stopListening(backgrounded = !isAppInForeground)
        }
    }

    fun startLiveSession(initialPrompt: String? = null) {
        val apiKey = secureKeyStore.getApiKey().orEmpty()
        if (apiKey.isBlank()) {
            _coreState.value = CoreState.ERROR
            _connectionState.value = LiveConnectionState.ERROR
            _statusBanner.value = "Gemini API Key Required — Open Settings"
            appendTranscript("system", "No Gemini API key stored. Open Settings to enter and test your key.")
            return
        }

        if (!hasMicPermission() && initialPrompt.isNullOrBlank()) {
            _coreState.value = CoreState.ERROR
            _statusBanner.value = "Microphone permission required for voice"
            return
        }

        // Release WakeWord exclusive mic before opening AudioEngine
        wakeWordManager.stopListening(backgrounded = false)

        viewModelScope.launch {
            _isMicPaused.value = false
            _isSpeakerMuted.value = false
            _coreState.value = CoreState.THINKING
            _statusBanner.value = "Establishing Gemini Live Link..."
            inputTranscriptBuffer.clear()
            outputTranscriptBuffer.clear()

            val currentSettings = settings.value
            if (currentSettings.saveConversationHistory && activeSessionId == null) {
                activeSessionId = conversationDao.insertSession(
                    ConversationSessionEntity(
                        title = initialPrompt?.take(42)?.ifBlank { "Live Voice Session" } ?: "Live Voice Session",
                        modelUsed = currentSettings.liveModel,
                        languageMode = currentSettings.languageMode
                    )
                )
            }

            val approvedMemories = memoryDao.getApprovedMemories()
            val memorySummary = approvedMemories.joinToString("\n") { "- [${it.category}] ${it.content}" }

            liveClient.connect(apiKey, currentSettings, memorySummary)

            if (!initialPrompt.isNullOrBlank()) {
                delay(650)
                sendLiveText(initialPrompt)
            }
        }
    }

    fun endLiveSession(dueToBackground: Boolean = false) {
        speakingTimeoutJob?.cancel()
        flushPendingTranscripts()
        audioEngine.stopAll()
        liveClient.disconnect()
        _isMicPaused.value = false
        _isSpeakerMuted.value = false
        _connectionState.value = LiveConnectionState.DISCONNECTED
        _coreState.value = CoreState.IDLE
        _statusBanner.value = if (dueToBackground) {
            "Mic & Audio Stopped (App Left Foreground)"
        } else {
            "Session Ended — Core Standby"
        }
        activeSessionId = null

        if (!dueToBackground && isAppInForeground) {
            viewModelScope.launch {
                delay(500)
                evaluateWakeWordStandby()
            }
        }
    }

    fun toggleMicPause() {
        val next = !_isMicPaused.value
        _isMicPaused.value = next
        audioEngine.setMicPaused(next)
        _statusBanner.value = if (next) "Microphone Paused" else "Listening (Gemini Live)"
        if (next && _coreState.value == CoreState.LISTENING) {
            _coreState.value = CoreState.IDLE
        } else if (!next && _connectionState.value == LiveConnectionState.CONNECTED) {
            _coreState.value = CoreState.LISTENING
        }
    }

    fun toggleSpeakerMute() {
        val next = !_isSpeakerMuted.value
        _isSpeakerMuted.value = next
        audioEngine.setSpeakerMuted(next)
    }

    fun interruptBumble() {
        audioEngine.interruptPlayback()
        _coreState.value = if (_isMicPaused.value) CoreState.IDLE else CoreState.LISTENING
        _statusBanner.value = "Interrupted — Listening"
    }

    fun sendLiveText(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (_connectionState.value != LiveConnectionState.CONNECTED) {
            startLiveSession(initialPrompt = clean)
            return
        }
        audioEngine.interruptPlayback()
        _coreState.value = CoreState.THINKING
        _statusBanner.value = "Processing Command..."
        appendTranscript("user", clean, persist = true)
        liveClient.sendTextTurn(clean)
    }

    fun sendLiveImage(bitmap: Bitmap, caption: String = "Describe what you see in this frame.") {
        if (_connectionState.value != LiveConnectionState.CONNECTED) {
            _statusBanner.value = "Start a Live session first or analyze directly in Vision."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val baos = ByteArrayOutputStream()
            val scaled = Bitmap.createScaledBitmap(bitmap, 640, (640f * bitmap.height / bitmap.width.coerceAtLeast(1)).toInt().coerceAtLeast(360), true)
            scaled.compress(Bitmap.CompressFormat.JPEG, 78, baos)
            liveClient.sendImageFrame(baos.toByteArray())
            liveClient.sendTextTurn(caption)
            appendTranscript("user", "[Vision Frame] $caption", persist = true)
        }
    }

    // --- GeminiLiveListener Callbacks ---

    override fun onConnectionChanged(state: LiveConnectionState, message: String?) {
        _connectionState.value = state
        when (state) {
            LiveConnectionState.CONNECTING -> {
                _coreState.value = CoreState.THINKING
                _statusBanner.value = "Connecting Gemini Live..."
            }
            LiveConnectionState.RECONNECTING -> {
                _coreState.value = CoreState.THINKING
                _statusBanner.value = message ?: "Reconnecting..."
            }
            LiveConnectionState.CONNECTED -> {
                _statusBanner.value = "Gemini Live Active"
            }
            LiveConnectionState.DISCONNECTED -> {
                if (_coreState.value != CoreState.ERROR) {
                    _coreState.value = CoreState.IDLE
                }
            }
            LiveConnectionState.ERROR -> {
                _coreState.value = CoreState.ERROR
                _statusBanner.value = message ?: "Connection Error"
            }
        }
    }

    override fun onSetupComplete() {
        audioEngine.startPlayback()
        if (hasMicPermission()) {
            val started = audioEngine.startCapture { pcmChunk ->
                liveClient.sendPcmAudio(pcmChunk)
            }
            if (started) {
                _coreState.value = CoreState.LISTENING
                _statusBanner.value = "Listening — Speak Naturally"
            } else {
                _coreState.value = CoreState.ERROR
                _statusBanner.value = "Audio capture unavailable"
            }
        } else {
            _coreState.value = CoreState.IDLE
            _statusBanner.value = "Live Connected (Text Mode — Mic Permission Not Granted)"
        }
    }

    override fun onAudioOutput(pcm24kMono: ByteArray) {
        _coreState.value = CoreState.SPEAKING
        _statusBanner.value = "Bumble Speaking..."
        audioEngine.writePlaybackPcm(pcm24kMono)

        speakingTimeoutJob?.cancel()
        speakingTimeoutJob = viewModelScope.launch {
            delay(550)
            audioEngine.resetOutputAmplitude()
            if (_connectionState.value == LiveConnectionState.CONNECTED && !_isMicPaused.value) {
                _coreState.value = CoreState.LISTENING
                _statusBanner.value = "Listening — Interrupt Anytime"
            }
        }
    }

    override fun onInterrupted() {
        speakingTimeoutJob?.cancel()
        audioEngine.interruptPlayback()
        flushPendingTranscripts()
        _coreState.value = if (_isMicPaused.value) CoreState.IDLE else CoreState.LISTENING
        _statusBanner.value = "Interrupted — Listening"
    }

    override fun onTurnComplete() {
        flushPendingTranscripts()
        if (_connectionState.value == LiveConnectionState.CONNECTED && _coreState.value == CoreState.THINKING) {
            _coreState.value = if (_isMicPaused.value) CoreState.IDLE else CoreState.LISTENING
            _statusBanner.value = "Listening"
        }
    }

    override fun onInputTranscript(text: String) {
        inputTranscriptBuffer.append(text)
        upsertStreamingTranscript("user", inputTranscriptBuffer.toString())
    }

    override fun onOutputTranscript(text: String) {
        outputTranscriptBuffer.append(text)
        upsertStreamingTranscript("assistant", outputTranscriptBuffer.toString())
    }

    override fun onModelTextFallback(text: String) {
        outputTranscriptBuffer.append(text)
        upsertStreamingTranscript("assistant", outputTranscriptBuffer.toString())
    }

    override fun onToolCall(id: String, name: String, args: JSONObject) {
        _coreState.value = CoreState.THINKING
        _statusBanner.value = "Executing: $name"
        viewModelScope.launch {
            val result = actionExecutor.executeFunctionCall(name, args)
            appendTranscript("tool", result.summary, toolName = name, persist = true)
            liveClient.sendToolResponse(id, name, result.payloadJson)
        }
    }

    override fun onError(message: String) {
        _coreState.value = CoreState.ERROR
        _statusBanner.value = message
        appendTranscript("system", "Error: $message")
    }

    private fun upsertStreamingTranscript(role: String, fullText: String) {
        val current = _liveTranscript.value.toMutableList()
        val last = current.lastOrNull()
        if (last != null && last.role == role && last.id.startsWith("streaming_$role")) {
            current[current.lastIndex] = last.copy(text = fullText)
        } else {
            current.add(LiveTranscriptEntry(id = "streaming_${role}_${System.nanoTime()}", role = role, text = fullText))
        }
        _liveTranscript.value = current
    }

    private fun flushPendingTranscripts() {
        val userText = inputTranscriptBuffer.toString().trim()
        val modelText = outputTranscriptBuffer.toString().trim()
        inputTranscriptBuffer.clear()
        outputTranscriptBuffer.clear()

        // Finalize streaming IDs in memory
        val updated = _liveTranscript.value.map {
            if (it.id.startsWith("streaming_")) it.copy(id = UUID.randomUUID().toString()) else it
        }
        _liveTranscript.value = updated

        val sessionId = activeSessionId ?: return
        if (!settings.value.saveConversationHistory) return

        viewModelScope.launch(Dispatchers.IO) {
            if (userText.isNotBlank()) {
                conversationDao.insertMessage(
                    ConversationMessageEntity(
                        sessionId = sessionId,
                        role = "user",
                        text = userText,
                        isAudioTranscript = true
                    )
                )
                conversationDao.updateSessionMeta(sessionId, userText.take(44), System.currentTimeMillis())
            }
            if (modelText.isNotBlank()) {
                conversationDao.insertMessage(
                    ConversationMessageEntity(
                        sessionId = sessionId,
                        role = "assistant",
                        text = modelText,
                        isAudioTranscript = true
                    )
                )
                conversationDao.updateSessionMeta(
                    sessionId,
                    userText.ifBlank { modelText }.take(44),
                    System.currentTimeMillis()
                )
            }
        }
    }

    private fun appendTranscript(
        role: String,
        text: String,
        toolName: String? = null,
        persist: Boolean = false
    ) {
        _liveTranscript.value = _liveTranscript.value + LiveTranscriptEntry(
            role = role,
            text = text,
            toolName = toolName
        )
        val sessionId = activeSessionId
        if (persist && sessionId != null && settings.value.saveConversationHistory) {
            viewModelScope.launch(Dispatchers.IO) {
                conversationDao.insertMessage(
                    ConversationMessageEntity(
                        sessionId = sessionId,
                        role = role,
                        text = text,
                        toolName = toolName
                    )
                )
                conversationDao.updateSessionMeta(sessionId, text.take(44), System.currentTimeMillis())
            }
        }
    }

    fun clearLiveTranscript() {
        _liveTranscript.value = emptyList()
    }

    // --- Settings & Secure API Key ---

    fun saveApiKey(rawKey: String) {
        val ok = secureKeyStore.saveApiKey(rawKey)
        _hasApiKey.value = secureKeyStore.hasApiKey()
        _maskedApiKey.value = secureKeyStore.getMaskedApiKey()
        _connectionTestResult.value = null
        if (!ok) {
            _statusBanner.value = "Failed to encrypt API key"
        } else if (_hasApiKey.value) {
            _statusBanner.value = "API key encrypted in Android Keystore"
            if (_coreState.value == CoreState.ERROR) _coreState.value = CoreState.IDLE
        } else {
            _statusBanner.value = "API key cleared"
        }
    }

    fun clearApiKey() {
        secureKeyStore.clearApiKey()
        _hasApiKey.value = false
        _maskedApiKey.value = ""
        _connectionTestResult.value = null
    }

    fun testApiConnection(overrideKey: String? = null) {
        val keyToTest = overrideKey?.takeIf { it.isNotBlank() } ?: secureKeyStore.getApiKey().orEmpty()
        viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionTestResult.value = geminiRest.testConnection(keyToTest)
            _isTestingConnection.value = false
        }
    }

    fun updateSettings(transform: (BumbleSettings) -> BumbleSettings) {
        viewModelScope.launch {
            settingsDataStore.updateSettings(transform)
            evaluateWakeWordStandby()
        }
    }

    // --- History, Memories, Notes, Reminders ---

    fun loadSessionMessages(sessionId: Long) {
        viewModelScope.launch {
            _selectedSessionMessages.value = conversationDao.getMessagesForSession(sessionId)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            conversationDao.deleteSession(sessionId)
            _selectedSessionMessages.value = emptyList()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            conversationDao.deleteAllSessions()
            _selectedSessionMessages.value = emptyList()
            _liveTranscript.value = emptyList()
        }
    }

    fun addMemory(content: String, category: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            memoryDao.insertMemory(
                MemoryEntity(content = content.trim(), category = category, isApproved = true, source = "user")
            )
        }
    }

    fun approveMemory(id: Long) {
        viewModelScope.launch { memoryDao.approveMemory(id) }
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch { memoryDao.deleteMemory(id) }
    }

    fun clearAllMemories() {
        viewModelScope.launch { memoryDao.deleteAllMemories() }
    }

    fun addNote(title: String, content: String) {
        if (title.isBlank() && content.isBlank()) return
        viewModelScope.launch {
            noteDao.insertNote(NoteEntity(title = title.ifBlank { "Untitled Note" }, content = content))
        }
    }

    fun togglePinNote(note: NoteEntity) {
        viewModelScope.launch {
            noteDao.updateNote(note.copy(isPinned = !note.isPinned, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch { noteDao.deleteNote(note) }
    }

    fun addReminder(title: String, minutesFromNow: Int) {
        if (title.isBlank()) return
        viewModelScope.launch {
            actionExecutor.createReminder(title, minutesFromNow)
        }
    }

    fun toggleReminderComplete(reminder: ReminderEntity) {
        viewModelScope.launch {
            reminderDao.setCompleted(reminder.id, !reminder.isCompleted)
        }
    }

    fun deleteReminder(id: Long) {
        viewModelScope.launch { reminderDao.deleteReminder(id) }
    }

    // --- Vision ---

    fun setVisionImage(bitmap: Bitmap?, sourceLabel: String) {
        _visionState.value = VisionUiState(
            previewBitmap = bitmap,
            sourceLabel = sourceLabel
        )
    }

    fun analyzeVisionBitmap(prompt: String) {
        val bmp = _visionState.value.previewBitmap ?: return
        val key = secureKeyStore.getApiKey().orEmpty()
        viewModelScope.launch {
            _visionState.value = _visionState.value.copy(isAnalyzing = true, error = null)
            val res = geminiRest.analyzeImage(
                apiKey = key,
                model = settings.value.visionModel,
                bitmap = bmp,
                prompt = prompt.ifBlank { "Identify the objects, text, and context in this image clearly." },
                languageMode = settings.value.languageMode
            )
            res.fold(
                onSuccess = { answer ->
                    _visionState.value = _visionState.value.copy(isAnalyzing = false, analysisResult = answer)
                },
                onFailure = { e ->
                    _visionState.value = _visionState.value.copy(
                        isAnalyzing = false,
                        error = e.localizedMessage ?: "Vision analysis failed"
                    )
                }
            )
        }
    }

    fun setVisionError(msg: String) {
        _visionState.value = _visionState.value.copy(isAnalyzing = false, error = msg)
    }

    // --- Tools Dashboard Actions ---

    fun evaluateMath(expression: String) {
        MathEvaluator.evaluate(expression).fold(
            onSuccess = { v ->
                _toolsState.value = _toolsState.value.copy(
                    calcResult = MathEvaluator.formatResult(v),
                    calcError = null
                )
            },
            onFailure = { e ->
                _toolsState.value = _toolsState.value.copy(
                    calcResult = "",
                    calcError = e.message ?: "Invalid expression"
                )
            }
        )
    }

    fun fetchWeather(city: String) {
        viewModelScope.launch {
            _toolsState.value = _toolsState.value.copy(isLoadingWeather = true, weatherError = null)
            externalTools.fetchWeatherByCity(city.ifBlank { "London" }).fold(
                onSuccess = { w ->
                    _toolsState.value = _toolsState.value.copy(isLoadingWeather = false, weatherResult = w)
                },
                onFailure = { e ->
                    _toolsState.value = _toolsState.value.copy(
                        isLoadingWeather = false,
                        weatherError = e.localizedMessage ?: "Weather lookup failed"
                    )
                }
            )
        }
    }

    fun runWebSearch(query: String) {
        viewModelScope.launch {
            _toolsState.value = _toolsState.value.copy(isSearching = true, searchError = null)
            externalTools.searchWeb(query).fold(
                onSuccess = { r ->
                    _toolsState.value = _toolsState.value.copy(isSearching = false, searchResult = r)
                },
                onFailure = { e ->
                    _toolsState.value = _toolsState.value.copy(
                        isSearching = false,
                        searchError = e.localizedMessage ?: "Search failed"
                    )
                }
            )
        }
    }

    fun runTranslation(text: String, targetLanguage: String) {
        val key = secureKeyStore.getApiKey().orEmpty()
        viewModelScope.launch {
            _toolsState.value = _toolsState.value.copy(isTranslating = true)
            val prompt = "Translate the following text naturally into $targetLanguage. Include script and concise pronunciation/transliteration if helpful:\n\n$text"
            geminiRest.generateText(key, settings.value.visionModel, prompt).fold(
                onSuccess = { res ->
                    _toolsState.value = _toolsState.value.copy(isTranslating = false, translationResult = res)
                },
                onFailure = { e ->
                    _toolsState.value = _toolsState.value.copy(
                        isTranslating = false,
                        translationResult = "Error: ${e.localizedMessage}"
                    )
                }
            )
        }
    }

    fun generateDailyPlan(goals: String) {
        val key = secureKeyStore.getApiKey().orEmpty()
        viewModelScope.launch {
            _toolsState.value = _toolsState.value.copy(isPlanning = true)
            val approvedMemories = memoryDao.getApprovedMemories().take(8).joinToString("\n") { "- ${it.content}" }
            val prompt = buildString {
                appendLine("Create a focused, time-blocked daily plan for today based on these priorities:")
                appendLine(goals.ifBlank { "Productive deep work, health break, evening wrap-up" })
                if (approvedMemories.isNotBlank()) {
                    appendLine("\nUser context:\n$approvedMemories")
                }
                appendLine("\nLanguage: ${settings.value.languageMode}")
            }
            geminiRest.generateText(key, settings.value.visionModel, prompt).fold(
                onSuccess = { plan ->
                    _toolsState.value = _toolsState.value.copy(isPlanning = false, plannerResult = plan)
                },
                onFailure = { e ->
                    _toolsState.value = _toolsState.value.copy(
                        isPlanning = false,
                        plannerResult = "Error: ${e.localizedMessage}"
                    )
                }
            )
        }
    }

    fun launchInstalledApp(appInfo: InstalledAppInfo) {
        val res = actionExecutor.openApp(appInfo.packageName)
        _toolsState.value = _toolsState.value.copy(actionFeedback = res.summary)
    }

    fun triggerDeviceAction(actionBlock: () -> String) {
        _toolsState.value = _toolsState.value.copy(actionFeedback = actionBlock())
    }

    fun clearAllLocalData() {
        viewModelScope.launch {
            conversationDao.deleteAllSessions()
            memoryDao.deleteAllMemories()
            noteDao.deleteAllNotes()
            reminderDao.deleteAllReminders()
            _liveTranscript.value = emptyList()
            _selectedSessionMessages.value = emptyList()
            _statusBanner.value = "All local conversations, memories, notes, and reminders cleared."
        }
    }

    override fun onCleared() {
        super.onCleared()
        wakeWordManager.stopListening(backgrounded = false)
        audioEngine.stopAll()
        liveClient.disconnect()
    }
}
