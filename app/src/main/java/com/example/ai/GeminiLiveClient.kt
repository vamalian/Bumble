package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.assistant.AndroidActionExecutor
import com.example.assistant.ToolExecutionResult
import com.example.data.local.BumbleSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    data class AudioChunk(val pcmBytes: ByteArray, val sampleRate: Int) : LiveServerEvent
    data class TextChunk(val text: String) : LiveServerEvent
    data class InputTranscription(val text: String) : LiveServerEvent
    data class OutputTranscription(val text: String) : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    data class ToolCallRequest(val id: String, val name: String, val args: JSONObject) : LiveServerEvent
    data class SessionResumption(val handle: String, val resumable: Boolean) : LiveServerEvent
    data class GoAway(val timeLeft: String) : LiveServerEvent
}

/**
 * Official Google Gemini Live API (BidiGenerateContent WebSocket) real-time client.
 * Handles:
 * - Both UTF-8 text and binary ByteString WebSocket frames from Google servers
 * - setup -> setupComplete handshake synchronization
 * - Real-time 16 kHz PCM input streaming (realtimeInput.audio)
 * - 24 kHz PCM output decoding, input/output transcriptions, barge-in interruptions, and tool calls
 * - Session resumption handles and exponential-backoff reconnection
 * - Classified API key, quota/429, unsupported model, and network errors
 */
class GeminiLiveClient {

    interface Listener {
        fun onEvent(event: LiveServerEvent)
        fun onFatalError(error: LiveApiError)
        fun onDisconnected(wasClean: Boolean, reason: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // WebSocket stays open indefinitely
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val _connectionStatus = MutableStateFlow(LiveConnectionStatus.DISCONNECTED)
    val connectionStatus: StateFlow<LiveConnectionStatus> = _connectionStatus.asStateFlow()

    private val _lastError = MutableStateFlow<LiveApiError?>(null)
    val lastError: StateFlow<LiveApiError?> = _lastError.asStateFlow()

    private val _lastConnectLatencyMs = MutableStateFlow<Long?>(null)
    val lastConnectLatencyMs: StateFlow<Long?> = _lastConnectLatencyMs.asStateFlow()

    private val _reconnectAttempt = MutableStateFlow(0)
    val reconnectAttempt: StateFlow<Int> = _reconnectAttempt.asStateFlow()

    val framesSentCount = AtomicLong(0L)
    val framesReceivedCount = AtomicLong(0L)

    @Volatile
    private var webSocket: WebSocket? = null
    @Volatile
    private var listener: Listener? = null
    @Volatile
    private var activeApiKey: String = ""
    @Volatile
    private var activeSettings: BumbleSettings = BumbleSettings()
    @Volatile
    private var activeMemoryContext: String = ""
    @Volatile
    private var sessionResumptionHandle: String? = null
    @Volatile
    private var connectStartMillis: Long = 0L

    private val isSetupDone = AtomicBoolean(false)
    private val userClosed = AtomicBoolean(false)
    private var setupDeferred: CompletableDeferred<Result<Unit>>? = null
    private var reconnectJob: Job? = null

    val isReady: Boolean
        get() = webSocket != null && isSetupDone.get() && _connectionStatus.value == LiveConnectionStatus.CONNECTED

    val hasResumableSession: Boolean
        get() = !sessionResumptionHandle.isNullOrBlank()

    suspend fun connect(
        apiKey: String,
        settings: BumbleSettings,
        memoryContext: String,
        sessionListener: Listener
    ): Result<Unit> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            val err = LiveApiError(
                category = LiveErrorCategory.MISSING_API_KEY,
                message = "Gemini API key is missing. Configure it in Settings."
            )
            _lastError.value = err
            _connectionStatus.value = LiveConnectionStatus.ERROR
            return Result.failure(IllegalStateException(err.message))
        }

        disconnect(resetSessionHandle = true)

        activeApiKey = cleanKey
        activeSettings = settings
        activeMemoryContext = memoryContext
        listener = sessionListener
        userClosed.set(false)
        _lastError.value = null
        _reconnectAttempt.value = 0

        return openSocketAndAwaitSetup(isReconnect = false)
    }

    private suspend fun openSocketAndAwaitSetup(isReconnect: Boolean): Result<Unit> {
        val deferred = CompletableDeferred<Result<Unit>>()
        setupDeferred = deferred
        isSetupDone.set(false)
        _connectionStatus.value = if (isReconnect) LiveConnectionStatus.RECONNECTING else LiveConnectionStatus.CONNECTING
        connectStartMillis = System.currentTimeMillis()

        val wsUrl = buildWebSocketUrl(activeApiKey)
        val request = Request.Builder().url(wsUrl).build()

        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val setupJson = buildSetupPayload(
                    settings = activeSettings,
                    memoryContext = activeMemoryContext,
                    resumptionHandle = sessionResumptionHandle
                )
                ws.send(setupJson.toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleIncomingPayload(text, deferred)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // Gemini BidiGenerateContent frequently delivers JSON payloads in binary WebSocket frames
                handleIncomingPayload(bytes.utf8(), deferred)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDisconnect(ws, code, reason, throwable = null, response = null, deferred = deferred)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                handleDisconnect(
                    ws = ws,
                    code = response?.code ?: 1006,
                    reason = response?.message ?: t.localizedMessage.orEmpty(),
                    throwable = t,
                    response = response,
                    deferred = deferred
                )
            }
        })

        webSocket = socket

        return try {
            withTimeout(SETUP_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = LiveApiError(
                category = LiveErrorCategory.NETWORK_ERROR,
                message = "Gemini Live handshake timed out. Check network connectivity and model '${activeSettings.liveModel}'."
            )
            _lastError.value = err
            _connectionStatus.value = LiveConnectionStatus.ERROR
            runCatching { socket.cancel() }
            Result.failure(IllegalStateException(err.message))
        }
    }

    private fun handleIncomingPayload(rawJson: String, deferred: CompletableDeferred<Result<Unit>>) {
        val events = parseServerMessage(rawJson)
        for (event in events) {
            when (event) {
                is LiveServerEvent.SetupComplete -> {
                    isSetupDone.set(true)
                    _reconnectAttempt.value = 0
                    _lastConnectLatencyMs.value = System.currentTimeMillis() - connectStartMillis
                    _connectionStatus.value = LiveConnectionStatus.CONNECTED
                    _lastError.value = null
                    if (!deferred.isCompleted) deferred.complete(Result.success(Unit))
                    listener?.onEvent(event)
                }
                is LiveServerEvent.SessionResumption -> {
                    if (event.resumable && event.handle.isNotBlank()) {
                        sessionResumptionHandle = event.handle
                    }
                    listener?.onEvent(event)
                }
                is LiveServerEvent.AudioChunk -> {
                    framesReceivedCount.incrementAndGet()
                    listener?.onEvent(event)
                }
                else -> listener?.onEvent(event)
            }
        }
    }

    private fun handleDisconnect(
        ws: WebSocket,
        code: Int,
        reason: String,
        throwable: Throwable?,
        response: Response?,
        deferred: CompletableDeferred<Result<Unit>>
    ) {
        if (webSocket !== ws && webSocket != null) return
        isSetupDone.set(false)
        webSocket = null

        val responseBody = runCatching { response?.body?.string() }.getOrNull()
        val wasUserClosed = userClosed.get() || code == 1000

        if (wasUserClosed) {
            _connectionStatus.value = LiveConnectionStatus.DISCONNECTED
            if (!deferred.isCompleted) deferred.complete(Result.failure(IllegalStateException("Session closed.")))
            listener?.onDisconnected(wasClean = true, reason = reason.ifBlank { "Closed by user" })
            return
        }

        val classified = classifyError(
            httpCode = response?.code ?: code,
            reason = reason,
            responseBody = responseBody,
            throwable = throwable,
            modelId = activeSettings.liveModel
        )
        _lastError.value = classified

        val nonRetryable = classified.category == LiveErrorCategory.INVALID_API_KEY ||
            classified.category == LiveErrorCategory.MISSING_API_KEY ||
            classified.category == LiveErrorCategory.UNSUPPORTED_MODEL

        if (!deferred.isCompleted) {
            _connectionStatus.value = LiveConnectionStatus.ERROR
            deferred.complete(Result.failure(IllegalStateException(classified.message)))
            listener?.onFatalError(classified)
            return
        }

        if (activeSettings.autoReconnect && !nonRetryable && _reconnectAttempt.value < MAX_RECONNECT_ATTEMPTS) {
            scheduleAutoReconnect(classified.message)
        } else {
            _connectionStatus.value = LiveConnectionStatus.ERROR
            listener?.onFatalError(classified)
        }
    }

    private fun scheduleAutoReconnect(reason: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val nextAttempt = _reconnectAttempt.value + 1
            _reconnectAttempt.value = nextAttempt
            _connectionStatus.value = LiveConnectionStatus.RECONNECTING
            val backoffMs = 1000L * (1 shl (nextAttempt - 1)) // 1s, 2s, 4s
            delay(backoffMs)
            if (userClosed.get()) return@launch

            val res = openSocketAndAwaitSetup(isReconnect = true)
            if (res.isFailure && !userClosed.get()) {
                if (nextAttempt >= MAX_RECONNECT_ATTEMPTS) {
                    val err = _lastError.value ?: LiveApiError(
                        LiveErrorCategory.NETWORK_ERROR,
                        "Reconnection failed after $MAX_RECONNECT_ATTEMPTS attempts ($reason)."
                    )
                    _connectionStatus.value = LiveConnectionStatus.ERROR
                    listener?.onFatalError(err)
                }
            }
        }
    }

    fun sendAudioPcm16k(pcmBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupDone.get() || pcmBytes.isEmpty()) return false
        val b64 = Base64.encodeToString(pcmBytes, Base64.NO_WRAP)
        val msg = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "audio",
                JSONObject()
                    .put("mimeType", "audio/pcm;rate=16000")
                    .put("data", b64)
            )
        )
        framesSentCount.incrementAndGet()
        return ws.send(msg.toString())
    }

    fun sendAudioStreamEnd(): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupDone.get()) return false
        val msg = JSONObject().put(
            "realtimeInput",
            JSONObject().put("audioStreamEnd", true)
        )
        return ws.send(msg.toString())
    }

    fun sendUserText(text: String): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupDone.get() || text.isBlank()) return false
        val payload = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                )
                .put("turnComplete", true)
        )
        return ws.send(payload.toString())
    }

    fun sendImageAndText(jpegBase64: String, prompt: String): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupDone.get()) return false
        val parts = JSONArray()
            .put(JSONObject().put("text", prompt.ifBlank { "Analyze this image." }))
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", jpegBase64)
                )
            )
        val payload = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", parts)
                    )
                )
                .put("turnComplete", true)
        )
        return ws.send(payload.toString())
    }

    fun sendToolResponse(callId: String, name: String, result: ToolExecutionResult): Boolean {
        val ws = webSocket ?: return false
        if (!isSetupDone.get()) return false
        val fnResp = JSONObject()
            .put("name", name)
            .put("response", result.payloadJson)
        if (callId.isNotBlank()) {
            fnResp.put("id", callId)
        }
        val payload = JSONObject().put(
            "toolResponse",
            JSONObject().put("functionResponses", JSONArray().put(fnResp))
        )
        return ws.send(payload.toString())
    }

    fun disconnect(resetSessionHandle: Boolean = true) {
        userClosed.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        isSetupDone.set(false)
        setupDeferred?.cancel()
        setupDeferred = null
        val ws = webSocket
        webSocket = null
        if (resetSessionHandle) {
            sessionResumptionHandle = null
        }
        runCatching { ws?.close(1000, "User ended session") }
        _connectionStatus.value = LiveConnectionStatus.DISCONNECTED
    }

    /**
     * Real Live API handshake diagnostic for Settings / Admin Panel.
     * Verifies WebSocket upgrade + `setup` -> `setupComplete` on `BidiGenerateContent`.
     */
    suspend fun testLiveHandshake(apiKey: String, liveModel: String): ConnectionTestResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return ConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "API key is empty."
            )
        }

        val start = System.currentTimeMillis()
        val deferred = CompletableDeferred<ConnectionTestResult>()
        val cleanModel = BumbleSettings.sanitizeLiveModelId(liveModel)
        val request = Request.Builder().url(buildWebSocketUrl(cleanKey)).build()

        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val minimalSetup = buildSetupPayload(
                    settings = BumbleSettings(liveModel = cleanModel),
                    memoryContext = "",
                    resumptionHandle = null
                )
                webSocket.send(minimalSetup.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                checkHandshakeMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                checkHandshakeMessage(webSocket, bytes.utf8())
            }

            private fun checkHandshakeMessage(webSocket: WebSocket, payload: String) {
                val events = parseServerMessage(payload)
                if (events.any { it is LiveServerEvent.SetupComplete }) {
                    val elapsed = System.currentTimeMillis() - start
                    if (!deferred.isCompleted) {
                        deferred.complete(
                            ConnectionTestResult(
                                success = true,
                                latencyMs = elapsed,
                                message = "Live WebSocket handshake verified for '$cleanModel' in ${elapsed}ms."
                            )
                        )
                    }
                    webSocket.close(1000, "Diagnostic complete")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!deferred.isCompleted) {
                    val elapsed = System.currentTimeMillis() - start
                    val err = classifyError(code, reason, null, null, cleanModel)
                    deferred.complete(
                        ConnectionTestResult(
                            success = false,
                            latencyMs = elapsed,
                            message = err.message
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!deferred.isCompleted) {
                    val elapsed = System.currentTimeMillis() - start
                    val body = runCatching { response?.body?.string() }.getOrNull()
                    val err = classifyError(
                        httpCode = response?.code ?: 1006,
                        reason = response?.message ?: t.localizedMessage.orEmpty(),
                        responseBody = body,
                        throwable = t,
                        modelId = cleanModel
                    )
                    deferred.complete(
                        ConnectionTestResult(
                            success = false,
                            latencyMs = elapsed,
                            message = err.message
                        )
                    )
                }
            }
        })

        return try {
            withTimeout(SETUP_TIMEOUT_MS) { deferred.await() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { ws.cancel() }
            ConnectionTestResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                message = "Live WebSocket diagnostic timed out for '$cleanModel'."
            )
        }
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val WS_ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val SETUP_TIMEOUT_MS = 12_000L
        const val MAX_RECONNECT_ATTEMPTS = 3

        fun buildWebSocketUrl(apiKey: String): String = "$WS_ENDPOINT?key=${apiKey.trim()}"

        fun buildSetupPayload(
            settings: BumbleSettings,
            memoryContext: String,
            resumptionHandle: String?
        ): JSONObject {
            val cleanModel = BumbleSettings.sanitizeLiveModelId(settings.liveModel)
            val voice = if (settings.liveVoice in BumbleSettings.LIVE_VOICE_OPTIONS) {
                settings.liveVoice
            } else {
                BumbleSettings.DEFAULT_LIVE_VOICE
            }

            val setup = JSONObject()
                .put("model", "models/$cleanModel")
                .put(
                    "generationConfig",
                    JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig",
                            JSONObject().put(
                                "voiceConfig",
                                JSONObject().put(
                                    "prebuiltVoiceConfig",
                                    JSONObject().put("voiceName", voice)
                                )
                            )
                        )
                )
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", buildSystemInstruction(settings, memoryContext)))
                    )
                )
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put(
                            "functionDeclarations",
                            AndroidActionExecutor.buildGeminiFunctionDeclarations()
                        )
                    )
                )
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "sessionResumption",
                    if (!resumptionHandle.isNullOrBlank()) {
                        JSONObject().put("handle", resumptionHandle)
                    } else {
                        JSONObject()
                    }
                )

            return JSONObject().put("setup", setup)
        }

        fun parseServerMessage(rawJson: String): List<LiveServerEvent> {
            val events = mutableListOf<LiveServerEvent>()
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return events

            if (root.has("setupComplete")) {
                events.add(LiveServerEvent.SetupComplete)
            }

            root.optJSONObject("sessionResumptionUpdate")?.let { sru ->
                val handle = sru.optString("newHandle", "")
                val resumable = sru.optBoolean("resumable", false)
                if (handle.isNotBlank()) {
                    events.add(LiveServerEvent.SessionResumption(handle, resumable))
                }
            }

            root.optJSONObject("goAway")?.let { ga ->
                events.add(LiveServerEvent.GoAway(ga.optString("timeLeft", "")))
            }

            root.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted", false)) {
                    events.add(LiveServerEvent.Interrupted)
                }

                sc.optJSONObject("inputTranscription")?.optString("text", "")?.let { inTxt ->
                    if (inTxt.isNotBlank()) {
                        events.add(LiveServerEvent.InputTranscription(inTxt))
                    }
                }

                sc.optJSONObject("outputTranscription")?.optString("text", "")?.let { outTxt ->
                    if (outTxt.isNotBlank()) {
                        events.add(LiveServerEvent.OutputTranscription(outTxt))
                    }
                }

                val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i) ?: continue
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val mime = inlineData.optString("mimeType", "")
                            val b64 = inlineData.optString("data", "")
                            if (mime.startsWith("audio/pcm", ignoreCase = true) && b64.isNotBlank()) {
                                val pcmBytes = runCatching {
                                    Base64.decode(b64, Base64.DEFAULT)
                                }.getOrNull()
                                if (pcmBytes != null && pcmBytes.isNotEmpty()) {
                                    val rate = parseSampleRateFromMime(mime)
                                    events.add(LiveServerEvent.AudioChunk(pcmBytes, rate))
                                }
                            }
                        } else if (part.has("text") && !part.optBoolean("thought", false)) {
                            val txt = part.optString("text", "")
                            if (txt.isNotBlank()) {
                                events.add(LiveServerEvent.TextChunk(txt))
                            }
                        }
                    }
                }

                if (sc.optBoolean("turnComplete", false)) {
                    events.add(LiveServerEvent.TurnComplete)
                }
            }

            root.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
                for (i in 0 until calls.length()) {
                    val call = calls.optJSONObject(i) ?: continue
                    val id = call.optString("id", "")
                    val name = call.optString("name", "")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    if (name.isNotBlank()) {
                        events.add(LiveServerEvent.ToolCallRequest(id, name, args))
                    }
                }
            }

            return events
        }

        fun parseSampleRateFromMime(mimeType: String): Int {
            val match = Regex("rate=(\\d+)").find(mimeType)
            return match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(8000, 48000)
                ?: LiveAudioEngine.DEFAULT_OUTPUT_SAMPLE_RATE
        }

        fun classifyError(
            httpCode: Int,
            reason: String,
            responseBody: String?,
            throwable: Throwable?,
            modelId: String
        ): LiveApiError {
            val combined = listOfNotNull(reason, responseBody, throwable?.localizedMessage)
                .joinToString(" | ")
                .trim()
            val lower = combined.lowercase()

            return when {
                httpCode == 401 || httpCode == 403 ||
                    lower.contains("api key") ||
                    lower.contains("api_key_invalid") ||
                    lower.contains("permission_denied") ||
                    lower.contains("unauthenticated") -> {
                    LiveApiError(
                        category = LiveErrorCategory.INVALID_API_KEY,
                        message = "Invalid or unauthorized Gemini API key (code $httpCode). Check your key in Settings.",
                        httpCode = httpCode
                    )
                }

                httpCode == 429 ||
                    lower.contains("quota") ||
                    lower.contains("resource_exhausted") ||
                    lower.contains("rate limit") -> {
                    LiveApiError(
                        category = LiveErrorCategory.QUOTA_EXCEEDED,
                        message = "Gemini API quota or rate limit exceeded ($httpCode). Wait a moment or verify billing tier.",
                        httpCode = httpCode
                    )
                }

                httpCode == 404 || httpCode == 1007 || httpCode == 1008 ||
                    lower.contains("not found") ||
                    lower.contains("unsupported") ||
                    lower.contains("is not supported for bidigeneratecontent") -> {
                    LiveApiError(
                        category = LiveErrorCategory.UNSUPPORTED_MODEL,
                        message = "Model '$modelId' is unsupported or unavailable for Live API ($combined).",
                        httpCode = httpCode
                    )
                }

                throwable != null || httpCode == 1006 -> {
                    LiveApiError(
                        category = LiveErrorCategory.NETWORK_ERROR,
                        message = "Network error communicating with Gemini Live: ${combined.ifBlank { "Connection lost" }}",
                        httpCode = httpCode
                    )
                }

                else -> {
                    LiveApiError(
                        category = LiveErrorCategory.PROTOCOL_ERROR,
                        message = "Gemini Live error ($httpCode): ${combined.ifBlank { "Unknown error" }}",
                        httpCode = httpCode
                    )
                }
            }
        }

        private fun buildSystemInstruction(settings: BumbleSettings, memoryContext: String): String {
            val personalityDirective = when (settings.personality) {
                "JARVIS Tactical" -> "You are BUMBLE, a sleek, intelligent, JARVIS-inspired personal voice AI assistant. Speak concisely, calmly, and clearly with subtle dry wit when appropriate."
                "Warm Companion" -> "You are BUMBLE, a warm, empathetic, attentive personal voice AI companion."
                "Concise Executive" -> "You are BUMBLE, an ultra-concise executive voice assistant. Lead directly with the answer."
                "Multilingual Tutor" -> "You are BUMBLE, a patient multilingual voice AI assistant who explains concepts clearly across languages."
                "Custom" -> settings.customSystemPrompt.ifBlank { "You are BUMBLE, a helpful futuristic voice AI assistant." }
                else -> "You are BUMBLE, a futuristic JARVIS-style voice AI assistant."
            }

            val languageDirective = when (settings.languageMode) {
                "English" -> "Respond naturally in spoken English."
                "Urdu (اردو)" -> "Respond naturally in spoken Urdu (with English technical terms if helpful)."
                "Hindi (हिन्दी)" -> "Respond naturally in spoken Hindi (with English technical terms if helpful)."
                else -> "Fluently understand and respond in spoken English, Urdu, Hindi, and natural code-switching (Hinglish / Roman Urdu), matching the user's language seamlessly."
            }

            return buildString {
                appendLine(personalityDirective)
                appendLine(languageDirective)
                appendLine("Speak naturally as a real-time conversational voice assistant. Avoid markdown symbols or long bullet lists.")
                appendLine("When asked to perform an Android action (open an app, adjust volume or brightness, open Wi-Fi/Bluetooth/system settings, launch camera, set alarm or timer, create/list notes, create reminders, read notifications, weather, web search, calculate), ALWAYS call the matching function tool and confirm the real result aloud.")
                appendLine("Never claim an action succeeded unless the tool call returned ok=true.")
                if (memoryContext.isNotBlank()) {
                    appendLine()
                    appendLine("Approved Personal Memories:")
                    appendLine(memoryContext)
                }
            }
        }
    }
}
