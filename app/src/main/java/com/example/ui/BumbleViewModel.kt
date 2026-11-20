package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BumbleApp
import com.example.ai.AssistantStatus
import com.example.ai.ConnectionTestResult
import com.example.ai.CoreState
import com.example.ai.GeminiChatTurn
import com.example.ai.WakeWordStatus
import com.example.assistant.InstalledAppInfo
import com.example.assistant.MathEvaluator
import com.example.assistant.WeatherResult
import com.example.assistant.WebSearchResult
import com.example.data.local.BumbleSettings
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.ConversationSessionEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.NoteEntity
import com.example.data.local.ReminderEntity
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
import java.util.UUID

data class ChatEntry(
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

class BumbleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BumbleApp
    private val conversationDao = app.database.conversationDao()
    private val memoryDao = app.database.memoryDao()
    private val noteDao = app.database.noteDao()
    private val reminderDao = app.database.reminderDao()
    private val secureKeyStore = app.secureKeyStore
    private val settingsDataStore = app.settingsDataStore
    private val voiceInput = app.voiceInputManager
    private val voiceOutput = app.voiceOutputManager
    private val wakeWordManager = app.wakeWordManager
    private val geminiRest = app.geminiRestClient
    private val actionExecutor = app.actionExecutor
    private val externalTools = app.externalTools

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
    val partialVoiceText: StateFlow<String> = voiceInput.partialText

    private val _assistantStatus = MutableStateFlow(AssistantStatus.READY)
    val assistantStatus: StateFlow<AssistantStatus> = _assistantStatus.asStateFlow()

    private val _statusBanner = MutableStateFlow("Core Standby — Ready")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chatMessages: StateFlow<List<ChatEntry>> = _chatMessages.asStateFlow()

    private val _isSpeakerMuted = MutableStateFlow(false)
    val isSpeakerMuted: StateFlow<Boolean> = _isSpeakerMuted.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

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

    private val _hasError = MutableStateFlow(false)

    val coreState: StateFlow<CoreState> = combine(
        voiceInput.isListening,
        _isProcessing,
        voiceOutput.isSpeaking,
        _hasError
    ) { listening, processing, speaking, error ->
        when {
            listening -> CoreState.LISTENING
            processing -> CoreState.THINKING
            speaking -> CoreState.SPEAKING
            error -> CoreState.ERROR
            else -> CoreState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CoreState.IDLE)

    val audioAmplitude: StateFlow<Float> = combine(
        voiceInput.rmsAmplitude,
        voiceOutput.outputAmplitude,
        coreState
    ) { inputAmp, outputAmp, state ->
        when (state) {
            CoreState.LISTENING -> maxOf(inputAmp, 0.14f)
            CoreState.SPEAKING -> maxOf(outputAmp, 0.24f)
            CoreState.THINKING -> 0.45f
            else -> 0.05f
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.05f)

    private var activeSessionId: Long? = null
    private var isAppInForeground = false
    private var activeRequestJob: Job? = null

    init {
        refreshInstalledApps()
        viewModelScope.launch {
            voiceOutput.isSpeaking.collect { speaking ->
                if (speaking) {
                    _assistantStatus.value = AssistantStatus.SPEAKING
                    _statusBanner.value = "Bumble Speaking..."
                } else if (!_isProcessing.value && !voiceInput.isListening.value && !_hasError.value) {
                    _assistantStatus.value = AssistantStatus.READY
                    _statusBanner.value = "Core Standby — Ready"
                    if (isAppInForeground) {
                        delay(300L)
                        evaluateWakeWordStandby()
                    }
                }
            }
        }
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
        voiceInput.stopListening()
        voiceOutput.stop()
        if (!_isProcessing.value) {
            _assistantStatus.value = AssistantStatus.READY
            _statusBanner.value = "Mic Stopped (App Left Foreground)"
        }
    }

    fun evaluateWakeWordStandby() {
        val canListen = isAppInForeground &&
            settings.value.wakeWordEnabled &&
            hasMicPermission() &&
            !voiceInput.isListening.value &&
            !_isProcessing.value &&
            !voiceOutput.isSpeaking.value

        if (canListen) {
            wakeWordManager.startForegroundListening {
                viewModelScope.launch(Dispatchers.Main) {
                    if (isAppInForeground && !voiceInput.isListening.value && !_isProcessing.value) {
                        startVoiceCapture()
                    }
                }
            }
        } else {
            wakeWordManager.stopListening(backgrounded = !isAppInForeground)
        }
    }

    fun startVoiceCapture() {
        if (!hasMicPermission()) {
            _hasError.value = true
            _assistantStatus.value = AssistantStatus.ERROR
            _statusBanner.value = "Microphone permission required for voice"
            return
        }
        if (!secureKeyStore.hasApiKey()) {
            _hasError.value = true
            _assistantStatus.value = AssistantStatus.ERROR
            _statusBanner.value = "Gemini API Key Required — Open Settings"
            appendEntry("system", "No Gemini API key stored. Open Settings to enter and test your key.")
            return
        }

        // Stop TTS and wake-word cleanly before opening voice SpeechRecognizer
        voiceOutput.stop()
        wakeWordManager.stopListening(backgrounded = false)
        _hasError.value = false
        _assistantStatus.value = AssistantStatus.LISTENING
        _statusBanner.value = "Listening — Speak Now"

        voiceInput.startListening(
            languageMode = settings.value.languageMode,
            onFinalResult = { spokenText ->
                sendMessage(spokenText, isVoiceInput = true)
            },
            onError = { errMsg ->
                _hasError.value = false
                _assistantStatus.value = AssistantStatus.READY
                _statusBanner.value = errMsg
                evaluateWakeWordStandby()
            }
        )
    }

    fun stopVoiceCapture() {
        voiceInput.stopListening()
        _assistantStatus.value = AssistantStatus.READY
        _statusBanner.value = "Core Standby — Ready"
        evaluateWakeWordStandby()
    }

    fun toggleSpeakerMute() {
        val next = !_isSpeakerMuted.value
        _isSpeakerMuted.value = next
        voiceOutput.setMuted(next)
    }

    fun stopSpeaking() {
        voiceOutput.stop()
        if (!_isProcessing.value && !voiceInput.isListening.value) {
            _assistantStatus.value = AssistantStatus.READY
            _statusBanner.value = "Speech Stopped — Ready"
            evaluateWakeWordStandby()
        }
    }

    fun startNewChat() {
        activeRequestJob?.cancel()
        voiceInput.stopListening()
        voiceOutput.stop()
        activeSessionId = null
        _chatMessages.value = emptyList()
        _hasError.value = false
        _isProcessing.value = false
        _assistantStatus.value = AssistantStatus.READY
        _statusBanner.value = "New Session — Ready"
        evaluateWakeWordStandby()
    }

    fun sendMessage(text: String, attachedBitmap: Bitmap? = null, isVoiceInput: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty() && attachedBitmap == null) return

        val apiKey = secureKeyStore.getApiKey().orEmpty()
        if (apiKey.isBlank()) {
            _hasError.value = true
            _assistantStatus.value = AssistantStatus.ERROR
            _statusBanner.value = "Gemini API Key Required — Open Settings"
            appendEntry("system", "No Gemini API key configured. Please enter your API key in Settings.")
            return
        }

        wakeWordManager.stopListening(backgrounded = false)
        voiceOutput.stop()
        activeRequestJob?.cancel()

        val displayUserText = if (attachedBitmap != null) {
            if (clean.isNotBlank()) "[Attached Frame] $clean" else "[Attached Frame] Analyze this image."
        } else {
            clean
        }
        val promptForModel = clean.ifBlank { "Describe and analyze this image." }

        val historyBeforeTurn = _chatMessages.value
            .filter { it.role == "user" || it.role == "assistant" }
            .map { GeminiChatTurn(role = if (it.role == "user") "user" else "model", text = it.text) }

        appendEntry("user", displayUserText)

        activeRequestJob = viewModelScope.launch {
            _hasError.value = false
            _isProcessing.value = true
            _assistantStatus.value = AssistantStatus.PROCESSING
            _statusBanner.value = "Gemini Thinking..."

            try {
                val currentSettings = settings.value
                val sessionId = ensureSession(displayUserText, currentSettings)
                persistMessage(sessionId, "user", displayUserText, isVoice = isVoiceInput, toolName = null)

                val approvedMemories = memoryDao.getApprovedMemories()
                val memorySummary = approvedMemories.joinToString("\n") { "- [${it.category}] ${it.content}" }

                val result = geminiRest.sendAssistantTurn(
                    apiKey = apiKey,
                    settings = currentSettings,
                    memoryContext = memorySummary,
                    history = historyBeforeTurn,
                    userText = promptForModel,
                    attachedBitmap = attachedBitmap,
                    onToolExecuted = { toolName, toolRes ->
                        appendEntry("tool", toolRes.summary, toolName = toolName)
                        persistMessage(sessionId, "tool", toolRes.summary, isVoice = false, toolName = toolName)
                    },
                    executeTool = { name, args ->
                        actionExecutor.executeFunctionCall(name, args)
                    },
                    onPartialText = { _ -> }
                )

                _isProcessing.value = false
                result.fold(
                    onSuccess = { reply ->
                        appendEntry("assistant", reply)
                        persistMessage(sessionId, "assistant", reply, isVoice = currentSettings.autoSpeakReplies, toolName = null)
                        if (currentSettings.autoSpeakReplies && !_isSpeakerMuted.value && isAppInForeground) {
                            voiceOutput.speak(
                                text = reply,
                                voiceProfile = currentSettings.voiceProfile,
                                languageMode = currentSettings.languageMode
                            )
                        } else {
                            _assistantStatus.value = AssistantStatus.READY
                            _statusBanner.value = "Core Standby — Ready"
                            evaluateWakeWordStandby()
                        }
                    },
                    onFailure = { e ->
                        val errText = e.localizedMessage ?: "Gemini request failed."
                        _hasError.value = true
                        _assistantStatus.value = AssistantStatus.ERROR
                        _statusBanner.value = errText
                        appendEntry("system", "Error: $errText")
                    }
                )
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun ensureSession(firstTitle: String, currentSettings: BumbleSettings): Long? {
        if (!currentSettings.saveConversationHistory) return null
        val existing = activeSessionId
        if (existing != null) return existing
        val created = conversationDao.insertSession(
            ConversationSessionEntity(
                title = firstTitle.take(44).ifBlank { "Gemini Chat" },
                modelUsed = currentSettings.geminiModel,
                languageMode = currentSettings.languageMode
            )
        )
        activeSessionId = created
        return created
    }

    private suspend fun persistMessage(
        sessionId: Long?,
        role: String,
        text: String,
        isVoice: Boolean,
        toolName: String?
    ) {
        if (sessionId == null || !settings.value.saveConversationHistory) return
        conversationDao.insertMessage(
            ConversationMessageEntity(
                sessionId = sessionId,
                role = role,
                text = text,
                toolName = toolName,
                isAudioTranscript = isVoice
            )
        )
        if (role == "user") {
            conversationDao.updateSessionMeta(sessionId, text.take(44), System.currentTimeMillis())
        }
    }

    private fun appendEntry(role: String, text: String, toolName: String? = null) {
        _chatMessages.value = _chatMessages.value + ChatEntry(
            role = role,
            text = text,
            toolName = toolName
        )
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
            _hasError.value = false
            _assistantStatus.value = AssistantStatus.READY
            _statusBanner.value = "API key encrypted in Android Keystore"
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
            _connectionTestResult.value = geminiRest.testConnection(keyToTest, settings.value.geminiModel)
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
            _chatMessages.value = emptyList()
            activeSessionId = null
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
            _chatMessages.value = emptyList()
            _selectedSessionMessages.value = emptyList()
            activeSessionId = null
            _statusBanner.value = "All local conversations, memories, notes, and reminders cleared."
        }
    }

    override fun onCleared() {
        super.onCleared()
        wakeWordManager.stopListening(backgrounded = false)
        voiceInput.stopListening()
        voiceOutput.shutdown()
    }
}
