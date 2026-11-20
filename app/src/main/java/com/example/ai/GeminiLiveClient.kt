package com.example.ai

import android.util.Base64
import android.util.Log
import com.example.assistant.AndroidActionExecutor
import com.example.data.local.BumbleSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

interface GeminiLiveListener {
    fun onConnectionChanged(state: LiveConnectionState, message: String? = null)
    fun onSetupComplete()
    fun onAudioOutput(pcm24kMono: ByteArray)
    fun onInterrupted()
    fun onTurnComplete()
    fun onInputTranscript(text: String)
    fun onOutputTranscript(text: String)
    fun onModelTextFallback(text: String)
    fun onToolCall(id: String, name: String, args: JSONObject)
    fun onError(message: String)
}

/**
 * Official Google Gemini Live API (BidiGenerateContent) WebSocket client.
 * Handles bidirectional audio streaming, setup config, tool declarations/responses,
 * barge-in interruptions, and automatic exponential-backoff reconnection.
 */
class GeminiLiveClient(
    private val listener: GeminiLiveListener
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Persistent WebSocket stream
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var userRequestedClose = false

    @Volatile
    private var isSetupDone = false

    private var activeApiKey: String = ""
    private var activeSettings: BumbleSettings = BumbleSettings()
    private var activeMemoryContext: String = ""
    private var reconnectAttempts = 0

    fun connect(
        apiKey: String,
        settings: BumbleSettings,
        memoryContext: String
    ) {
        userRequestedClose = false
        reconnectAttempts = 0
        activeApiKey = apiKey.trim()
        activeSettings = settings
        activeMemoryContext = memoryContext
        openSocket()
    }

    private fun openSocket() {
        reconnectJob?.cancel()
        closeInternal(isUserDisconnect = false)

        if (activeApiKey.isBlank()) {
            listener.onConnectionChanged(LiveConnectionState.ERROR, "Gemini API key not configured.")
            listener.onError("Please enter your Gemini API key in Settings before starting a Live session.")
            return
        }

        listener.onConnectionChanged(
            if (reconnectAttempts == 0) LiveConnectionState.CONNECTING else LiveConnectionState.RECONNECTING
        )

        val wsUrl = "$LIVE_WS_BASE?key=$activeApiKey"
        val request = Request.Builder().url(wsUrl).build()

        webSocket = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                isSetupDone = false
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingJson(bytes.string(StandardCharsets.UTF_8))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isSetupDone = false
                if (!userRequestedClose) {
                    handleUnexpectedClose("Live stream closed ($code): ${reason.ifBlank { "Connection closed" }}")
                } else {
                    listener.onConnectionChanged(LiveConnectionState.DISCONNECTED)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isSetupDone = false
                if (userRequestedClose) return
                val code = response?.code
                val msg = if (code == 401 || code == 403) {
                    "Authentication failed (HTTP $code). Verify your Gemini API key in Settings."
                } else {
                    t.localizedMessage ?: "Network connection error"
                }
                if (code == 401 || code == 403) {
                    listener.onConnectionChanged(LiveConnectionState.ERROR, msg)
                    listener.onError(msg)
                } else {
                    handleUnexpectedClose(msg)
                }
            }
        })
    }

    private fun handleUnexpectedClose(reason: String) {
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS && !userRequestedClose) {
            reconnectAttempts++
            val backoffMs = 1000L * (1 shl (reconnectAttempts - 1))
            listener.onConnectionChanged(
                LiveConnectionState.RECONNECTING,
                "Reconnecting ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)..."
            )
            reconnectJob = scope.launch {
                delay(backoffMs)
                if (!userRequestedClose) openSocket()
            }
        } else {
            listener.onConnectionChanged(LiveConnectionState.ERROR, reason)
            listener.onError(reason)
        }
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val normalizedModel = if (activeSettings.liveModel.startsWith("models/")) {
            activeSettings.liveModel
        } else {
            "models/${activeSettings.liveModel}"
        }

        val systemText = buildSystemInstruction(activeSettings, activeMemoryContext)

        val setupJson = JSONObject().put(
            "setup",
            JSONObject()
                .put("model", normalizedModel)
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
                                    JSONObject().put("voiceName", activeSettings.voiceName)
                                )
                            )
                        )
                )
                .put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemText))
                    )
                )
                .put("inputAudioTranscription", JSONObject())
                .put("outputAudioTranscription", JSONObject())
                .put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put(
                            "functionDeclarations",
                            AndroidActionExecutor.buildGeminiFunctionDeclarations()
                        )
                    )
                )
        )

        ws.send(setupJson.toString())
    }

    fun sendPcmAudio(pcm16kMono: ByteArray) {
        if (!isSetupDone || pcm16kMono.isEmpty()) return
        val b64 = Base64.encodeToString(pcm16kMono, Base64.NO_WRAP)
        val msg = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "mediaChunks",
                JSONArray().put(
                    JSONObject()
                        .put("mimeType", "audio/pcm;rate=16000")
                        .put("data", b64)
                )
            )
        )
        webSocket?.send(msg.toString())
    }

    fun sendImageFrame(jpegBytes: ByteArray) {
        if (!isSetupDone || jpegBytes.isEmpty()) return
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val msg = JSONObject().put(
            "realtimeInput",
            JSONObject().put(
                "mediaChunks",
                JSONArray().put(
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", b64)
                )
            )
        )
        webSocket?.send(msg.toString())
    }

    fun sendTextTurn(text: String) {
        if (!isSetupDone || text.isBlank()) return
        val msg = JSONObject().put(
            "clientContent",
            JSONObject()
                .put(
                    "turns",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text.trim())))
                    )
                )
                .put("turnComplete", true)
        )
        webSocket?.send(msg.toString())
    }

    fun sendToolResponse(callId: String, functionName: String, responsePayload: JSONObject) {
        if (webSocket == null) return
        val msg = JSONObject().put(
            "toolResponse",
            JSONObject().put(
                "functionResponses",
                JSONArray().put(
                    JSONObject()
                        .put("id", callId)
                        .put("name", functionName)
                        .put("response", responsePayload)
                )
            )
        )
        webSocket?.send(msg.toString())
    }

    fun disconnect() {
        userRequestedClose = true
        reconnectJob?.cancel()
        closeInternal(isUserDisconnect = true)
    }

    private fun closeInternal(isUserDisconnect: Boolean) {
        isSetupDone = false
        runCatching { webSocket?.close(1000, "Client closed") }
        webSocket = null
        if (isUserDisconnect) {
            listener.onConnectionChanged(LiveConnectionState.DISCONNECTED)
        }
    }

    private fun handleIncomingJson(raw: String) {
        try {
            val root = JSONObject(raw)

            if (root.has("setupComplete")) {
                isSetupDone = true
                listener.onConnectionChanged(LiveConnectionState.CONNECTED)
                listener.onSetupComplete()
                return
            }

            if (root.has("error")) {
                val errObj = root.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: "Gemini Live error"
                listener.onError(errMsg)
                return
            }

            root.optJSONObject("toolCall")?.let { toolCall ->
                val calls = toolCall.optJSONArray("functionCalls")
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val fn = calls.getJSONObject(i)
                        val id = fn.optString("id", "call_$i")
                        val name = fn.optString("name", "")
                        val args = fn.optJSONObject("args") ?: JSONObject()
                        if (name.isNotBlank()) {
                            listener.onToolCall(id, name, args)
                        }
                    }
                }
            }

            root.optJSONObject("serverContent")?.let { sc ->
                if (sc.optBoolean("interrupted", false)) {
                    listener.onInterrupted()
                }

                sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                    listener.onInputTranscript(it)
                }

                sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let {
                    listener.onOutputTranscript(it)
                }

                val modelTurn = sc.optJSONObject("modelTurn")
                val parts = modelTurn?.optJSONArray("parts")
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.getJSONObject(i)
                        val inlineData = part.optJSONObject("inlineData")
                        if (inlineData != null) {
                            val mime = inlineData.optString("mimeType", "")
                            val b64 = inlineData.optString("data", "")
                            if (mime.startsWith("audio/pcm") && b64.isNotEmpty()) {
                                val pcmBytes = Base64.decode(b64, Base64.DEFAULT)
                                listener.onAudioOutput(pcmBytes)
                            }
                        } else if (part.has("text")) {
                            val textPart = part.optString("text", "")
                            if (textPart.isNotBlank()) {
                                listener.onModelTextFallback(textPart)
                            }
                        }
                    }
                }

                if (sc.optBoolean("turnComplete", false)) {
                    listener.onTurnComplete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Gemini Live message", e)
        }
    }

    private fun buildSystemInstruction(settings: BumbleSettings, memoryContext: String): String {
        val personalityDirective = when (settings.personality) {
            "JARVIS Tactical" -> "You are BUMBLE, a sleek, intelligent, JARVIS-inspired personal AI assistant. Speak concisely, calmly, and clearly with subtle dry wit when appropriate."
            "Warm Companion" -> "You are BUMBLE, a warm, empathetic, attentive personal AI companion who gives thoughtful and encouraging spoken answers."
            "Concise Executive" -> "You are BUMBLE, an ultra-concise executive assistant. Lead with the answer, keep responses crisp, structured, and actionable."
            "Multilingual Tutor" -> "You are BUMBLE, a patient multilingual AI assistant who helps explain concepts clearly across languages."
            "Custom" -> settings.customSystemPrompt.ifBlank {
                "You are BUMBLE, a helpful futuristic AI assistant."
            }
            else -> "You are BUMBLE, a futuristic JARVIS-style AI assistant."
        }

        val languageDirective = when (settings.languageMode) {
            "English" -> "Respond in natural English."
            "Urdu (اردو)" -> "Respond naturally in Urdu (and Roman Urdu or English technical terms if helpful)."
            "Hindi (हिन्दी)" -> "Respond naturally in Hindi (and Hinglish or English technical terms if helpful)."
            else -> "Fluently understand and respond in English, Urdu, Hindi, and mixed conversational code-switching (Hinglish / Roman Urdu), matching the user's spoken language seamlessly."
        }

        return buildString {
            appendLine(personalityDirective)
            appendLine(languageDirective)
            appendLine("When the user asks you to perform a device action, set an alarm/timer, open an app, adjust volume/brightness, create a note or reminder, check weather, search the web, calculate, or read notifications, ALWAYS call the corresponding function tool and then briefly confirm the outcome verbally.")
            appendLine("Never claim you executed a device action unless the tool call succeeded.")
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("Approved User Context & Memories:")
                appendLine(memoryContext)
            }
        }
    }

    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val LIVE_WS_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }
}
