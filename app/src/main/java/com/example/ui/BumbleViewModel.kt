package com.example.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BumbleApp
import com.example.ai.AssistantStatus
import com.example.ai.ConnectionTestResult
import com.example.ai.CoreState
import com.example.ai.GeminiLiveClient
import com.example.ai.LiveApiError
import com.example.ai.LiveConnectionStatus
import com.example.ai.LiveErrorCategory
import com.example.ai.LiveDiagnostics
import com.example.ai.LiveServerEvent
import com.example.ai.MicDiagnosticResult
import com.example.ai.MicHardwareStatus
import com.example.ai.SpeakerDiagnosticResult
import com.example.ai.WakeWordStatus
import com.example.assistant.InstalledAppInfo
import com.example.assistant.MathEvaluator
import com.example.assistant.WeatherResult
import com.example.assistant.WebSearchResult
import com.example.data.local.AdminAuthResult
import com.example.data.local.AdminCredentialStore
import com.example.data.local.BumbleSettings
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.ConversationSessionEntity
import com.example.data.local.MemoryEntity
import com.example.data.local.NoteEntity
import com.example.data.local.PasswordChangeResult
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
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

data class PrivacySafeStats(
    val sessionCount: Int = 0,
    val totalMessages: Int = 0,
    val userMessages: Int = 0,
    val assistantMessages: Int = 0,
    val toolMessages: Int = 0,
    val memoryCount: Int = 0,
    val noteCount: Int = 0,
    val reminderCount: Int = 0
)

data class AdminUiState(
    val isAuthenticated: Boolean = false,
    val isUsingDefaultPassword: Boolean = true,
    val authError: String? = null,
    val passwordChangeMessage: String? = null,
    val passwordChangeSuccess: Boolean = false,
    val lockoutRemainingSeconds: Long = 0L,
    val sessionRemainingSeconds: Long = 0L,
    val lastPasswordChangeMillis: Long = 0L,
    val isHardwareVaultReady: Boolean = false,
    val micDiagnostic: MicDiagnosticResult? = null,
    val speakerDiagnostic: SpeakerDiagnosticResult? = null,
    val liveHandshakeResult: ConnectionTestResult? = null,
    val isRunningMicTest: Boolean = false,
    val isRunningSpeakerTest: Boolean = false,
    val isTestingLiveHandshake: Boolean = false
)

class BumbleViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BumbleApp
    private val conversationDao = app.database.conversationDao()
    private val memoryDao = app.database.memoryDao()
    private val noteDao = app.database.noteDao()
    private val reminderDao = app.database.reminderDao()
    private val secureKeyStore = app.secureKeyStore
    private val adminCredentialStore = app.adminCredentialStore
    private val settingsDataStore = app.settingsDataStore
    private val geminiLive = app.geminiLiveClient
    private val audioEngine = app.liveAudioEngine
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

    val privacySafeStats: StateFlow<PrivacySafeStats> = combine(
        conversationDao.observeSessionCount(),
        conversationDao.observeTotalMessageCount(),
        conversationDao.observeMessageCountByRole("user"),
        conversationDao.observeMessageCountByRole("assistant"),
        conversationDao.observeMessageCountByRole("tool"),
        memoryDao.observeMemoryCount(),
        noteDao.observeNoteCount(),
        reminderDao.observeReminderCount()
    ) { values: Array<Int> ->
        PrivacySafeStats(
            sessionCount = values[0],
            totalMessages = values[1],
            userMessages = values[2],
            assistantMessages = values[3],
            toolMessages = values[4],
            memoryCount = values[5],
            noteCount = values[6],
            reminderCount = values[7]
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PrivacySafeStats())

    val wakeWordStatus: StateFlow<WakeWordStatus> = wakeWordManager.status

    private val _liveInputTranscript = MutableStateFlow("")
    val liveInputTranscript: StateFlow<String> = _liveInputTranscript.asStateFlow()

    private val _liveOutputTranscript = MutableStateFlow("")
    val liveOutputTranscript: StateFlow<String> = _liveOutputTranscript.asStateFlow()

    private val _statusBanner = MutableStateFlow("Core Standby — Ready")
    val statusBanner: StateFlow<String> = _statusBanner.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chatMessages: StateFlow<List<ChatEntry>> = _chatMessages.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isSpeakerMuted = MutableStateFlow(false)
    val isSpeakerMuted: StateFlow<Boolean> = _isSpeakerMuted.asStateFlow()

    private val _isProcessingTurn = MutableStateFlow(false)
    val isProcessingTurn: StateFlow<Boolean> = _isProcessingTurn.asStateFlow()

    private val _micStatus = MutableStateFlow(
        if (hasMicPermission()) MicHardwareStatus.STANDBY else MicHardwareStatus.PERMISSION_REQUIRED
    )
    private val _activeAudioSource = MutableStateFlow("None")
    private val _liveError = MutableStateFlow<LiveApiError?>(null)
    private val _recentApiErrors = MutableStateFlow<List<LiveApiError>>(emptyList())
    val recentApiErrors: StateFlow<List<LiveApiError>> = _recentApiErrors.asStateFlow()

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

    private val _adminState = MutableStateFlow(
        AdminUiState(
            isUsingDefaultPassword = adminCredentialStore.isUsingDefaultPassword(),
            lockoutRemainingSeconds = adminCredentialStore.getLockoutRemainingSeconds(),
            lastPasswordChangeMillis = adminCredentialStore.getLastPasswordChangeMillis(),
            isHardwareVaultReady = adminCredentialStore.isHardwareVaultReady()
        )
    )
    val adminState: StateFlow<AdminUiState> = _adminState.asStateFlow()

    val coreState: StateFlow<CoreState> = combine(
        geminiLive.connectionStatus,
        audioEngine.isCapturing,
        _isProcessingTurn,
        audioEngine.isSpeaking,
        _liveError
    ) { conn, capturing, processing, speaking, liveErr ->
        when {
            speaking -> CoreState.SPEAKING
            conn == LiveConnectionStatus.CONNECTING || conn == LiveConnectionStatus.RECONNECTING -> CoreState.CONNECTING
            processing -> CoreState.PROCESSING
            capturing && conn == LiveConnectionStatus.CONNECTED -> CoreState.LISTENING
            liveErr != null || conn == LiveConnectionStatus.ERROR -> CoreState.ERROR
            else -> CoreState.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CoreState.IDLE)

    val assistantStatus: StateFlow<AssistantStatus> = coreState.combine(_statusBanner) { c, _ ->
        when (c) {
            CoreState.IDLE -> AssistantStatus.READY
            CoreState.CONNECTING -> AssistantStatus.CONNECTING
            CoreState.LISTENING -> AssistantStatus.LISTENING
            CoreState.PROCESSING -> AssistantStatus.PROCESSING
            CoreState.SPEAKING -> AssistantStatus.SPEAKING
            CoreState.ERROR -> AssistantStatus.ERROR
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AssistantStatus.READY)

    val audioAmplitude: StateFlow<Float> = combine(
        audioEngine.inputAmplitude,
        audioEngine.outputAmplitude,
        coreState
    ) { inputAmp, outputAmp, state ->
        when (state) {
            CoreState.LISTENING -> inputAmp
            CoreState.SPEAKING -> outputAmp
            CoreState.CONNECTING -> 0.22f
            CoreState.PROCESSING -> 0.35f
            CoreState.ERROR -> 0.08f
            CoreState.IDLE -> 0.04f
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.04f)

    val liveDiagnostics: StateFlow<LiveDiagnostics> = combine(
        geminiLive.connectionStatus,
        _micStatus,
        _activeAudioSource,
        settings,
        geminiLive.reconnectAttempt,
        geminiLive.lastConnectLatencyMs,
        _liveError
    ) { values: Array<Any?> ->
        val conn = values[0] as LiveConnectionStatus
        val mic = values[1] as MicHardwareStatus
        val source = values[2] as String
        val cfg = values[3] as BumbleSettings
        val attempt = values[4] as Int
        val latency = values[5] as Long?
        val err = values[6] as LiveApiError?
        LiveDiagnostics(
            connectionStatus = conn,
            micStatus = mic,
            activeAudioSource = source,
            activeModel = cfg.liveModel,
            endpointLabel = "v1beta BidiGenerateContent",
            reconnectAttempt = attempt,
            maxReconnectAttempts = GeminiLiveClient.MAX_RECONNECT_ATTEMPTS,
            lastConnectLatencyMs = latency,
            audioFramesSent = geminiLive.framesSentCount.get(),
            audioFramesReceived = geminiLive.framesReceivedCount.get(),
            lastError = err,
            sessionResumable = geminiLive.hasResumableSession
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveDiagnostics())

    private val sessionMutex = Mutex()
    private val transcriptLock = Any()
    private val inputTranscriptBuf = StringBuilder()
    private val outputTranscriptBuf = StringBuilder()

    private var activeSessionId: Long? = null
    private var isAppInForeground = false
    private var sessionLaunchJob: Job? = null
    private var adminDeadlineMillis: Long = 0L

    init {
        refreshInstalledApps()
        observeLiveTelemetry()
        startAdminSessionTicker()
    }

    private fun observeLiveTelemetry() {
        viewModelScope.launch {
            coreState.collect { state ->
                when (state) {
                    CoreState.SPEAKING -> _statusBanner.value = "Bumble Speaking (24 kHz Live PCM)"
                    CoreState.CONNECTING -> _statusBanner.value = "Connecting Gemini Live..."
                    CoreState.LISTENING -> {
                        _statusBanner.value = if (_isMicMuted.value) {
                            "Mic Muted — Tap Unmute to Speak"
                        } else {
                            "Listening — Speak Now"
                        }
                    }
                    CoreState.PROCESSING -> _statusBanner.value = "Gemini Processing..."
                    CoreState.ERROR -> {
                        _liveError.value?.message?.let { _statusBanner.value = it }
                    }
                    CoreState.IDLE -> {
                        if (!geminiLive.isReady && _liveError.value == null) {
                            _statusBanner.value = "Core Standby — Ready"
                        }
                    }
                }
            }
        }
    }

    private fun startAdminSessionTicker() {
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                val lockSecs = adminCredentialStore.getLockoutRemainingSeconds()
                val current = _adminState.value
                if (current.isAuthenticated) {
                    val remainingMs = adminDeadlineMillis - System.currentTimeMillis()
                    if (remainingMs <= 0L) {
                        logoutAdmin(reason = "Owner session locked after 5 minutes of inactivity.")
                    } else {
                        _adminState.value = current.copy(
                            lockoutRemainingSeconds = lockSecs,
                            sessionRemainingSeconds = (remainingMs + 999L) / 1000L
                        )
                    }
                } else if (current.lockoutRemainingSeconds != lockSecs) {
                    _adminState.value = current.copy(lockoutRemainingSeconds = lockSecs)
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
        _micStatus.value = when {
            audioEngine.isCapturing.value -> MicHardwareStatus.ACTIVE_16KHZ_PCM
            hasMicPermission() -> MicHardwareStatus.STANDBY
            else -> MicHardwareStatus.PERMISSION_REQUIRED
        }
        evaluateWakeWordStandby()
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        sessionLaunchJob?.cancel()
        wakeWordManager.stopListening(backgrounded = true)
        audioEngine.stopAll()
        geminiLive.disconnect(resetSessionHandle = true)
        commitTranscriptsIfAny()
        _isProcessingTurn.value = false
        _micStatus.value = if (hasMicPermission()) MicHardwareStatus.STANDBY else MicHardwareStatus.PERMISSION_REQUIRED
        _activeAudioSource.value = "None"
        _statusBanner.value = "Session Stopped (App Left Foreground)"
        if (_adminState.value.isAuthenticated) {
            logoutAdmin(reason = "Owner session locked when app left foreground.")
        }
    }

    fun evaluateWakeWordStandby() {
        val permOk = hasMicPermission()
        if (!permOk && !audioEngine.isCapturing.value) {
            _micStatus.value = MicHardwareStatus.PERMISSION_REQUIRED
        } else if (permOk && !audioEngine.isCapturing.value && _micStatus.value == MicHardwareStatus.PERMISSION_REQUIRED) {
            _micStatus.value = MicHardwareStatus.STANDBY
        }

        val canListenWake = isAppInForeground &&
            settings.value.wakeWordEnabled &&
            permOk &&
            !audioEngine.isCapturing.value &&
            !geminiLive.isReady &&
            coreState.value != CoreState.CONNECTING &&
            !_isProcessingTurn.value &&
            !audioEngine.isSpeaking.value

        if (canListenWake) {
            wakeWordManager.startForegroundListening {
                viewModelScope.launch(Dispatchers.Main) {
                    if (isAppInForeground && !audioEngine.isCapturing.value && !geminiLive.isReady) {
                        startLiveSession()
                    }
                }
            }
        } else {
            wakeWordManager.stopListening(backgrounded = !isAppInForeground)
        }
    }

    fun startLiveSession() {
        if (!hasMicPermission()) {
            _micStatus.value = MicHardwareStatus.PERMISSION_REQUIRED
            val err = LiveApiError(
                category = LiveErrorCategory.MICROPHONE_ERROR,
                message = "Microphone permission (RECORD_AUDIO) is required for live voice."
            )
            recordLiveError(err)
            return
        }

        val apiKey = secureKeyStore.getApiKey().orEmpty()
        if (apiKey.isBlank()) {
            val err = LiveApiError(
                category = LiveErrorCategory.MISSING_API_KEY,
                message = "Gemini API key required. Enter your key in Settings."
            )
            recordLiveError(err)
            appendEntry("system", "No Gemini API key stored. Open Settings to configure and test your key.")
            return
        }

        sessionLaunchJob?.cancel()
        sessionLaunchJob = viewModelScope.launch {
            sessionMutex.withLock {
                _liveError.value = null
                _isProcessingTurn.value = false
                _micStatus.value = MicHardwareStatus.INITIALIZING
                _statusBanner.value = "Connecting Gemini Live..."

                // Release SpeechRecognizer cleanly before starting 16 kHz AudioRecord
                wakeWordManager.stopListening(backgrounded = false)
                audioEngine.stopPlayback()
                delay(220L)

                if (!geminiLive.isReady) {
                    val connectRes = connectGeminiLiveInternal(apiKey)
                    if (connectRes.isFailure) {
                        _micStatus.value = if (hasMicPermission()) MicHardwareStatus.STANDBY else MicHardwareStatus.PERMISSION_REQUIRED
                        return@withLock
                    }
                }

                // Start real 16 kHz AudioRecord capture ONLY after setupComplete succeeds
                val captureRes = audioEngine.startCapture(
                    onPcmChunk = { chunk ->
                        geminiLive.sendAudioPcm16k(chunk)
                    },
                    onCaptureError = { captureErrMsg ->
                        viewModelScope.launch {
                            handleMicFailure(captureErrMsg)
                        }
                    }
                )

                captureRes.fold(
                    onSuccess = { sourceLabel ->
                        _activeAudioSource.value = sourceLabel
                        _micStatus.value = MicHardwareStatus.ACTIVE_16KHZ_PCM
                        _isMicMuted.value = audioEngine.isMicMuted
                        _statusBanner.value = "Listening ($sourceLabel 16 kHz)"
                    },
                    onFailure = { e ->
                        geminiLive.disconnect(resetSessionHandle = true)
                        handleMicFailure(e.localizedMessage ?: "Microphone failed to start.")
                    }
                )
            }
        }
    }

    private fun handleMicFailure(message: String) {
        audioEngine.stopCapture()
        _activeAudioSource.value = "None"
        _micStatus.value = if (hasMicPermission()) {
            MicHardwareStatus.HARDWARE_CONFLICT
        } else {
            MicHardwareStatus.PERMISSION_REQUIRED
        }
        recordLiveError(
            LiveApiError(
                category = LiveErrorCategory.MICROPHONE_ERROR,
                message = message
            )
        )
        appendEntry("system", "Microphone Error: $message")
    }

    fun stopLiveSession() {
        sessionLaunchJob?.cancel()
        viewModelScope.launch {
            sessionMutex.withLock {
                audioEngine.stopAll()
                geminiLive.sendAudioStreamEnd()
                geminiLive.disconnect(resetSessionHandle = true)
                commitTranscriptsIfAny()
                _isProcessingTurn.value = false
                _activeAudioSource.value = "None"
                _micStatus.value = if (hasMicPermission()) MicHardwareStatus.STANDBY else MicHardwareStatus.PERMISSION_REQUIRED
                _liveError.value = null
                _statusBanner.value = "Core Standby — Ready"
                evaluateWakeWordStandby()
            }
        }
    }

    fun retryLiveConnection() {
        _liveError.value = null
        if (hasMicPermission()) {
            startLiveSession()
        } else {
            _statusBanner.value = "Grant Microphone permission to start Live Voice"
        }
    }

    fun toggleMicMute() {
        val next = !_isMicMuted.value
        _isMicMuted.value = next
        audioEngine.setMicMuted(next)
        if (next && geminiLive.isReady) {
            geminiLive.sendAudioStreamEnd()
        }
        if (coreState.value == CoreState.LISTENING) {
            _statusBanner.value = if (next) "Mic Muted — Live Connected" else "Listening — Speak Now"
        }
    }

    fun toggleSpeakerMute() {
        val next = !_isSpeakerMuted.value
        _isSpeakerMuted.value = next
        audioEngine.setSpeakerMuted(next)
    }

    fun stopSpeaking() {
        audioEngine.interruptPlayback()
        _isProcessingTurn.value = false
        if (audioEngine.isCapturing.value && geminiLive.isReady) {
            _statusBanner.value = "Interrupted — Listening"
        } else {
            _statusBanner.value = "Speech Stopped — Ready"
            evaluateWakeWordStandby()
        }
    }

    fun startNewChat() {
        sessionLaunchJob?.cancel()
        audioEngine.stopAll()
        geminiLive.disconnect(resetSessionHandle = true)
        synchronized(transcriptLock) {
            inputTranscriptBuf.clear()
            outputTranscriptBuf.clear()
        }
        _liveInputTranscript.value = ""
        _liveOutputTranscript.value = ""
        activeSessionId = null
        _chatMessages.value = emptyList()
        _liveError.value = null
        _isProcessingTurn.value = false
        _activeAudioSource.value = "None"
        _micStatus.value = if (hasMicPermission()) MicHardwareStatus.STANDBY else MicHardwareStatus.PERMISSION_REQUIRED
        _statusBanner.value = "New Session — Ready"
        evaluateWakeWordStandby()
    }

    fun sendMessage(text: String, attachedBitmap: Bitmap? = null) {
        val clean = text.trim()
        if (clean.isEmpty() && attachedBitmap == null) return

        val apiKey = secureKeyStore.getApiKey().orEmpty()
        if (apiKey.isBlank()) {
            val err = LiveApiError(
                category = LiveErrorCategory.MISSING_API_KEY,
                message = "Gemini API Key Required — Open Settings"
            )
            recordLiveError(err)
            appendEntry("system", "No Gemini API key configured. Please enter your API key in Settings.")
            return
        }

        val displayUserText = if (attachedBitmap != null) {
            if (clean.isNotBlank()) "[Attached Frame] $clean" else "[Attached Frame] Analyze this image."
        } else {
            clean
        }
        val promptForModel = clean.ifBlank { "Describe and analyze this image." }

        audioEngine.interruptPlayback()
        wakeWordManager.stopListening(backgrounded = false)

        viewModelScope.launch {
            sessionMutex.withLock {
                _liveError.value = null
                _isProcessingTurn.value = true
                _statusBanner.value = "Sending to Gemini Live..."

                val cfg = settings.value
                val sessionId = ensureSession(displayUserText, cfg)
                appendEntry("user", displayUserText)
                persistMessage(sessionId, "user", displayUserText, isVoice = false, toolName = null)

                if (!geminiLive.isReady) {
                    val connectRes = connectGeminiLiveInternal(apiKey)
                    if (connectRes.isFailure) {
                        _isProcessingTurn.value = false
                        return@withLock
                    }
                }

                val sentOk = if (attachedBitmap != null) {
                    val jpegB64 = bitmapToJpegBase64(attachedBitmap)
                    geminiLive.sendImageAndText(jpegB64, promptForModel)
                } else {
                    geminiLive.sendUserText(promptForModel)
                }

                if (!sentOk) {
                    _isProcessingTurn.value = false
                    recordLiveError(
                        LiveApiError(
                            category = LiveErrorCategory.NETWORK_ERROR,
                            message = "Failed to send message over Gemini Live WebSocket."
                        )
                    )
                }
            }
        }
    }

    private suspend fun connectGeminiLiveInternal(apiKey: String): Result<Unit> {
        val currentSettings = settings.value
        val approvedMemories = memoryDao.getApprovedMemories()
        val memorySummary = approvedMemories.joinToString("\n") { "- [${it.category}] ${it.content}" }

        return geminiLive.connect(
            apiKey = apiKey,
            settings = currentSettings,
            memoryContext = memorySummary,
            sessionListener = object : GeminiLiveClient.Listener {
                override fun onEvent(event: LiveServerEvent) {
                    handleLiveServerEvent(event)
                }

                override fun onFatalError(error: LiveApiError) {
                    viewModelScope.launch {
                        audioEngine.stopCapture()
                        _activeAudioSource.value = "None"
                        _micStatus.value = if (hasMicPermission()) MicHardwareStatus.STANDBY else MicHardwareStatus.PERMISSION_REQUIRED
                        _isProcessingTurn.value = false
                        recordLiveError(error)
                        appendEntry("system", "Live Error: ${error.message}")
                    }
                }

                override fun onDisconnected(wasClean: Boolean, reason: String) {
                    viewModelScope.launch {
                        audioEngine.stopCapture()
                        _activeAudioSource.value = "None"
                        _micStatus.value = if (hasMicPermission()) MicHardwareStatus.STANDBY else MicHardwareStatus.PERMISSION_REQUIRED
                        _isProcessingTurn.value = false
                        commitTranscriptsIfAny()
                        evaluateWakeWordStandby()
                    }
                }
            }
        )
    }

    private fun handleLiveServerEvent(event: LiveServerEvent) {
        when (event) {
            is LiveServerEvent.SetupComplete -> {
                _liveError.value = null
            }

            is LiveServerEvent.AudioChunk -> {
                _isProcessingTurn.value = false
                audioEngine.enqueueOutputAudio(event.pcmBytes, event.sampleRate)
            }

            is LiveServerEvent.InputTranscription -> {
                synchronized(transcriptLock) {
                    inputTranscriptBuf.append(event.text)
                    _liveInputTranscript.value = inputTranscriptBuf.toString().trim()
                }
            }

            is LiveServerEvent.OutputTranscription -> {
                _isProcessingTurn.value = false
                synchronized(transcriptLock) {
                    outputTranscriptBuf.append(event.text)
                    _liveOutputTranscript.value = outputTranscriptBuf.toString().trim()
                }
            }

            is LiveServerEvent.TextChunk -> {
                _isProcessingTurn.value = false
                synchronized(transcriptLock) {
                    outputTranscriptBuf.append(event.text)
                    _liveOutputTranscript.value = outputTranscriptBuf.toString().trim()
                }
            }

            is LiveServerEvent.Interrupted -> {
                audioEngine.interruptPlayback()
                _isProcessingTurn.value = false
                commitTranscriptsIfAny()
            }

            is LiveServerEvent.TurnComplete -> {
                audioEngine.markServerTurnComplete()
                _isProcessingTurn.value = false
                commitTranscriptsIfAny()
            }

            is LiveServerEvent.ToolCallRequest -> {
                _isProcessingTurn.value = true
                viewModelScope.launch {
                    val toolRes = actionExecutor.executeFunctionCall(event.name, event.args)
                    val cfg = settings.value
                    val sessionId = ensureSession("Tool: ${event.name}", cfg)
                    appendEntry("tool", toolRes.summary, toolName = event.name)
                    persistMessage(sessionId, "tool", toolRes.summary, isVoice = false, toolName = event.name)
                    geminiLive.sendToolResponse(event.id, event.name, toolRes)
                }
            }

            is LiveServerEvent.GoAway -> {
                _statusBanner.value = "Live session rotating (${event.timeLeft})..."
            }

            is LiveServerEvent.SessionResumption -> Unit
        }
    }

    private fun commitTranscriptsIfAny() {
        val userTxt: String
        val assistantTxt: String
        synchronized(transcriptLock) {
            userTxt = inputTranscriptBuf.toString().trim()
            assistantTxt = outputTranscriptBuf.toString().trim()
            inputTranscriptBuf.clear()
            outputTranscriptBuf.clear()
        }
        _liveInputTranscript.value = ""
        _liveOutputTranscript.value = ""

        if (userTxt.isBlank() && assistantTxt.isBlank()) return

        viewModelScope.launch {
            val cfg = settings.value
            val seedTitle = userTxt.ifBlank { assistantTxt }
            val sessionId = ensureSession(seedTitle, cfg)
            if (userTxt.isNotBlank()) {
                appendEntry("user", userTxt)
                persistMessage(sessionId, "user", userTxt, isVoice = true, toolName = null)
            }
            if (assistantTxt.isNotBlank()) {
                appendEntry("assistant", assistantTxt)
                persistMessage(sessionId, "assistant", assistantTxt, isVoice = true, toolName = null)
            }
        }
    }

    private fun recordLiveError(error: LiveApiError) {
        _liveError.value = error
        _recentApiErrors.value = (listOf(error) + _recentApiErrors.value).take(12)
        _statusBanner.value = error.message
    }

    private fun bitmapToJpegBase64(bitmap: Bitmap): String {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val scaled = if (w > 1280 || h > 1280) {
            val ratio = 1280f / maxOf(w, h)
            Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private suspend fun ensureSession(firstTitle: String, currentSettings: BumbleSettings): Long? {
        if (!currentSettings.saveConversationHistory) return null
        val existing = activeSessionId
        if (existing != null) return existing
        val created = conversationDao.insertSession(
            ConversationSessionEntity(
                title = firstTitle.take(44).ifBlank { "Gemini Live Session" },
                modelUsed = currentSettings.liveModel,
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
            _liveError.value = null
            _statusBanner.value = "API key encrypted in Android Keystore"
        } else {
            _statusBanner.value = "API key cleared"
        }
    }

    fun clearApiKey() {
        stopLiveSession()
        secureKeyStore.clearApiKey()
        _hasApiKey.value = false
        _maskedApiKey.value = ""
        _connectionTestResult.value = null
    }

    fun testApiConnection(overrideKey: String? = null) {
        val keyToTest = overrideKey?.takeIf { it.isNotBlank() } ?: secureKeyStore.getApiKey().orEmpty()
        viewModelScope.launch {
            _isTestingConnection.value = true
            val restRes = geminiRest.testConnection(keyToTest, settings.value.visionModel)
            val liveRes = if (restRes.success || keyToTest.isNotBlank()) {
                geminiLive.testLiveHandshake(keyToTest, settings.value.liveModel)
            } else {
                null
            }
            val combined = when {
                liveRes != null && liveRes.success && restRes.success -> ConnectionTestResult(
                    success = true,
                    latencyMs = liveRes.latencyMs,
                    message = "Verified! Live (${settings.value.liveModel}, ${liveRes.latencyMs}ms) & Vision (${settings.value.visionModel}, ${restRes.latencyMs}ms) ready.",
                    availableModelsCount = restRes.availableModelsCount
                )
                liveRes != null && !liveRes.success -> liveRes
                else -> restRes
            }
            _connectionTestResult.value = combined
            _adminState.value = _adminState.value.copy(liveHandshakeResult = liveRes)
            if (!combined.success) {
                _recentApiErrors.value = (
                    listOf(LiveApiError(LiveErrorCategory.PROTOCOL_ERROR, combined.message)) +
                        _recentApiErrors.value
                    ).take(12)
            }
            _isTestingConnection.value = false
        }
    }

    fun updateSettings(transform: (BumbleSettings) -> BumbleSettings) {
        viewModelScope.launch {
            settingsDataStore.updateSettings(transform)
            evaluateWakeWordStandby()
        }
    }

    // --- Owner / Admin Panel Security & Diagnostics ---

    fun loginAdmin(password: String) {
        when (val res = adminCredentialStore.verifyPassword(password)) {
            is AdminAuthResult.Success -> {
                adminDeadlineMillis = System.currentTimeMillis() + AdminCredentialStore.SESSION_TIMEOUT_MS
                _adminState.value = _adminState.value.copy(
                    isAuthenticated = true,
                    isUsingDefaultPassword = adminCredentialStore.isUsingDefaultPassword(),
                    authError = null,
                    passwordChangeMessage = null,
                    lockoutRemainingSeconds = 0L,
                    sessionRemainingSeconds = AdminCredentialStore.SESSION_TIMEOUT_MS / 1000L,
                    lastPasswordChangeMillis = adminCredentialStore.getLastPasswordChangeMillis(),
                    isHardwareVaultReady = adminCredentialStore.isHardwareVaultReady()
                )
            }
            is AdminAuthResult.Invalid -> {
                _adminState.value = _adminState.value.copy(
                    isAuthenticated = false,
                    authError = "Incorrect owner password. ${res.remainingAttempts} attempts remaining.",
                    lockoutRemainingSeconds = 0L
                )
            }
            is AdminAuthResult.LockedOut -> {
                _adminState.value = _adminState.value.copy(
                    isAuthenticated = false,
                    authError = "Too many failed attempts. Locked for ${res.remainingSeconds}s.",
                    lockoutRemainingSeconds = res.remainingSeconds
                )
            }
        }
    }

    fun touchAdminSession() {
        if (_adminState.value.isAuthenticated) {
            adminDeadlineMillis = System.currentTimeMillis() + AdminCredentialStore.SESSION_TIMEOUT_MS
        }
    }

    fun logoutAdmin(reason: String? = null) {
        adminDeadlineMillis = 0L
        _adminState.value = _adminState.value.copy(
            isAuthenticated = false,
            authError = reason,
            passwordChangeMessage = null,
            sessionRemainingSeconds = 0L
        )
    }

    fun changeAdminPassword(currentPassword: String, newPassword: String, confirmPassword: String) {
        touchAdminSession()
        when (val res = adminCredentialStore.changePassword(currentPassword, newPassword, confirmPassword)) {
            is PasswordChangeResult.Success -> {
                _adminState.value = _adminState.value.copy(
                    isUsingDefaultPassword = adminCredentialStore.isUsingDefaultPassword(),
                    lastPasswordChangeMillis = adminCredentialStore.getLastPasswordChangeMillis(),
                    passwordChangeSuccess = true,
                    passwordChangeMessage = "Owner password updated and encrypted in Android Keystore."
                )
            }
            is PasswordChangeResult.Error -> {
                _adminState.value = _adminState.value.copy(
                    passwordChangeSuccess = false,
                    passwordChangeMessage = res.message
                )
            }
        }
    }

    fun testLiveConnectionAdmin() {
        touchAdminSession()
        val key = secureKeyStore.getApiKey().orEmpty()
        viewModelScope.launch {
            _adminState.value = _adminState.value.copy(isTestingLiveHandshake = true)
            val res = geminiLive.testLiveHandshake(key, settings.value.liveModel)
            _adminState.value = _adminState.value.copy(
                isTestingLiveHandshake = false,
                liveHandshakeResult = res
            )
            if (!res.success) {
                _recentApiErrors.value = (
                    listOf(LiveApiError(LiveErrorCategory.PROTOCOL_ERROR, res.message)) +
                        _recentApiErrors.value
                    ).take(12)
            }
        }
    }

    fun runMicDiagnostic() {
        touchAdminSession()
        viewModelScope.launch {
            _adminState.value = _adminState.value.copy(isRunningMicTest = true)
            val wasWakeRunning = wakeWordStatus.value == WakeWordStatus.STANDBY_LISTENING
            if (wasWakeRunning && !audioEngine.isCapturing.value) {
                wakeWordManager.stopListening(backgrounded = false)
                delay(180L)
            }
            val result = audioEngine.runMicDiagnostic()
            _adminState.value = _adminState.value.copy(
                isRunningMicTest = false,
                micDiagnostic = result
            )
            if (wasWakeRunning) evaluateWakeWordStandby()
        }
    }

    fun runSpeakerDiagnostic() {
        touchAdminSession()
        viewModelScope.launch {
            _adminState.value = _adminState.value.copy(isRunningSpeakerTest = true)
            val result = audioEngine.runSpeakerDiagnostic()
            _adminState.value = _adminState.value.copy(
                isRunningSpeakerTest = false,
                speakerDiagnostic = result
            )
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
        audioEngine.stopAll()
        geminiLive.disconnect(resetSessionHandle = true)
    }
}
