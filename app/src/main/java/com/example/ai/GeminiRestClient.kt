package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.assistant.AndroidActionExecutor
import com.example.assistant.ToolExecutionResult
import com.example.data.local.BumbleSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class ConnectionTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val availableModelsCount: Int = 0
)

data class GeminiChatTurn(
    val role: String, // "user" or "model"
    val text: String
)

/**
 * Standard Google Gemini API client (generateContent + streamGenerateContent SSE)
 * supporting multi-turn chat, function calling for Android assistant tools,
 * multimodal vision analysis, and API key/model verification.
 */
class GeminiRestClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(
        apiKey: String,
        model: String = BumbleSettings.DEFAULT_GEMINI_MODEL
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) {
            return@withContext ConnectionTestResult(
                success = false,
                latencyMs = 0L,
                message = "API key is empty. Enter your Gemini API key first."
            )
        }
        val start = System.currentTimeMillis()
        runCatching {
            val modelsUrl = "$API_BASE/models?key=$cleanKey&pageSize=30"
            val request = Request.Builder().url(modelsUrl).get().build()
            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - start
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errDetail = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull() ?: response.message
                    return@withContext ConnectionTestResult(
                        success = false,
                        latencyMs = elapsed,
                        message = "HTTP ${response.code}: $errDetail"
                    )
                }
                val modelsArr = JSONObject(body).optJSONArray("models")
                val count = modelsArr?.length() ?: 0
                val cleanModel = model.removePrefix("models/")
                    .ifBlank { BumbleSettings.DEFAULT_GEMINI_MODEL }
                val modelCheckUrl = "$API_BASE/models/$cleanModel?key=$cleanKey"
                val modelReq = Request.Builder().url(modelCheckUrl).get().build()
                val modelVerified = client.newCall(modelReq).execute().use { mResp ->
                    if (mResp.isSuccessful) {
                        true to ""
                    } else {
                        val mErr = runCatching {
                            JSONObject(mResp.body?.string().orEmpty()).optJSONObject("error")?.optString("message")
                        }.getOrNull() ?: "Model '$cleanModel' returned HTTP ${mResp.code}"
                        false to mErr
                    }
                }
                if (!modelVerified.first) {
                    return@withContext ConnectionTestResult(
                        success = false,
                        latencyMs = System.currentTimeMillis() - start,
                        message = "Key valid ($count models), but '$cleanModel' failed: ${modelVerified.second}",
                        availableModelsCount = count
                    )
                }
                val totalElapsed = System.currentTimeMillis() - start
                ConnectionTestResult(
                    success = true,
                    latencyMs = totalElapsed,
                    message = "Connected! Verified key & model '$cleanModel' ($count models available) in ${totalElapsed}ms.",
                    availableModelsCount = count
                )
            }
        }.getOrElse { e ->
            ConnectionTestResult(
                success = false,
                latencyMs = System.currentTimeMillis() - start,
                message = "Connection failed: ${e.localizedMessage ?: "Network error"}"
            )
        }
    }

    /**
     * Executes a full Normal Gemini assistant turn with conversation history,
     * optional attached image, system instructions, and multi-round function calling.
     */
    suspend fun sendAssistantTurn(
        apiKey: String,
        settings: BumbleSettings,
        memoryContext: String,
        history: List<GeminiChatTurn>,
        userText: String,
        attachedBitmap: Bitmap? = null,
        onToolExecuted: suspend (toolName: String, result: ToolExecutionResult) -> Unit,
        executeTool: suspend (name: String, args: JSONObject) -> ToolExecutionResult,
        onPartialText: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotBlank()) {
                "Gemini API key is required. Configure it in Settings."
            }

            val modelId = settings.geminiModel.removePrefix("models/")
                .ifBlank { BumbleSettings.DEFAULT_GEMINI_MODEL }

            val contents = JSONArray()
            history.takeLast(16).forEach { turn ->
                if (turn.text.isNotBlank()) {
                    contents.put(
                        JSONObject()
                            .put("role", if (turn.role == "user") "user" else "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                    )
                }
            }

            // Current user turn (text + optional image)
            val currentParts = JSONArray().put(JSONObject().put("text", userText))
            if (attachedBitmap != null) {
                currentParts.put(bitmapToInlinePart(attachedBitmap))
            }
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", currentParts)
            )

            val systemInstruction = JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", buildSystemInstruction(settings, memoryContext)))
            )

            val tools = JSONArray().put(
                JSONObject().put("functionDeclarations", AndroidActionExecutor.buildGeminiFunctionDeclarations())
            )

            var finalText = ""
            var round = 0
            while (round < MAX_TOOL_ROUNDS) {
                round++
                val payload = JSONObject()
                    .put("systemInstruction", systemInstruction)
                    .put("contents", contents)
                    .put("tools", tools)

                val responseJson = postGenerateContent(cleanKey, modelId, payload)
                val blockReason = responseJson.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val reason = if (blockReason.isNotBlank()) " (blocked: $blockReason)" else ""
                    throw IllegalStateException("Gemini returned an empty response$reason.")
                }

                val candidateObj = candidates.getJSONObject(0)
                val candidateContent = candidateObj.optJSONObject("content")
                    ?: throw IllegalStateException(
                        "Missing candidate content (${candidateObj.optString("finishReason", "UNKNOWN")})."
                    )
                val parts = candidateContent.optJSONArray("parts") ?: JSONArray()

                val functionCalls = mutableListOf<Pair<String, JSONObject>>()
                val roundText = StringBuilder()

                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    if (part.optBoolean("thought", false)) continue

                    val fc = part.optJSONObject("functionCall")
                    if (fc != null) {
                        val fnName = fc.optString("name", "")
                        val fnArgs = fc.optJSONObject("args") ?: JSONObject()
                        if (fnName.isNotBlank()) {
                            functionCalls.add(fnName to fnArgs)
                        }
                    } else if (part.has("text")) {
                        val txt = part.optString("text", "")
                        if (txt.isNotBlank()) roundText.append(txt)
                    }
                }

                if (functionCalls.isEmpty()) {
                    finalText = roundText.toString().trim()
                    if (finalText.isNotBlank()) {
                        onPartialText(finalText)
                    }
                    break
                }

                // Preserve exact model turn (including thoughtSignature if present)
                contents.put(candidateContent)

                // Execute all requested tools and append functionResponse parts
                val responseParts = JSONArray()
                for ((fnName, fnArgs) in functionCalls) {
                    val toolResult = executeTool(fnName, fnArgs)
                    onToolExecuted(fnName, toolResult)
                    responseParts.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject()
                                .put("name", fnName)
                                .put("response", toolResult.payloadJson)
                        )
                    )
                }

                contents.put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", responseParts)
                )
            }

            finalText.ifBlank { "Action completed." }
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun analyzeImage(
        apiKey: String,
        model: String,
        bitmap: Bitmap,
        prompt: String,
        languageMode: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotBlank()) { "Gemini API key is required. Configure it in Settings." }

            val parts = JSONArray()
                .put(
                    JSONObject().put(
                        "text",
                        "$prompt\nLanguage preference: $languageMode. Be accurate, observant, and concise."
                    )
                )
                .put(bitmapToInlinePart(bitmap))

            val bodyJson = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )

            extractTextFromResponse(postGenerateContent(cleanKey, model.removePrefix("models/"), bodyJson))
        }.onFailure { if (it is CancellationException) throw it }
    }

    suspend fun generateText(
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanKey = apiKey.trim()
            require(cleanKey.isNotBlank()) { "Gemini API key is required. Configure it in Settings." }

            val bodyJson = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            if (!systemInstruction.isNullOrBlank()) {
                bodyJson.put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", systemInstruction))
                    )
                )
            }

            extractTextFromResponse(postGenerateContent(cleanKey, model.removePrefix("models/"), bodyJson))
        }.onFailure { if (it is CancellationException) throw it }
    }

    private fun postGenerateContent(apiKey: String, modelId: String, payload: JSONObject): JSONObject {
        val url = "$API_BASE/models/$modelId:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errMsg = runCatching {
                    JSONObject(raw).optJSONObject("error")?.optString("message")
                }.getOrNull() ?: "HTTP ${response.code}: ${response.message}"
                throw IllegalStateException(errMsg)
            }
            return JSONObject(raw)
        }
    }

    private fun extractTextFromResponse(root: JSONObject): String {
        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException("No response candidates returned by Gemini.")
        if (candidates.length() == 0) {
            throw IllegalStateException("Empty response from Gemini.")
        }
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: throw IllegalStateException("Missing response parts.")

        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optBoolean("thought", false)) continue
            val txt = part.optString("text", "")
            if (txt.isNotBlank()) sb.append(txt)
        }
        return sb.toString().ifBlank { "No text response generated." }
    }

    private fun bitmapToInlinePart(bitmap: Bitmap): JSONObject {
        val scaled = scaleBitmapIfNeeded(bitmap, maxDim = 1280)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val imageB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return JSONObject().put(
            "inlineData",
            JSONObject()
                .put("mimeType", "image/jpeg")
                .put("data", imageB64)
        )
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun buildSystemInstruction(settings: BumbleSettings, memoryContext: String): String {
        val personalityDirective = when (settings.personality) {
            "JARVIS Tactical" -> "You are BUMBLE, a sleek, intelligent, JARVIS-inspired personal AI assistant. Respond concisely, calmly, and clearly with subtle dry wit when appropriate."
            "Warm Companion" -> "You are BUMBLE, a warm, empathetic, attentive personal AI companion who gives thoughtful and encouraging answers."
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
            else -> "Fluently understand and respond in English, Urdu, Hindi, and mixed conversational code-switching (Hinglish / Roman Urdu), matching the user's language seamlessly."
        }

        return buildString {
            appendLine(personalityDirective)
            appendLine(languageDirective)
            appendLine("Keep spoken replies clear and natural without excessive markdown symbols.")
            appendLine("When the user asks you to perform a device action, set an alarm/timer, open an app, adjust volume/brightness, create a note or reminder, check weather, search the web, calculate, or read notifications, ALWAYS call the corresponding function tool and briefly confirm the real outcome.")
            appendLine("Never claim you executed a device action unless the tool call succeeded.")
            if (memoryContext.isNotBlank()) {
                appendLine()
                appendLine("Approved User Context & Memories:")
                appendLine(memoryContext)
            }
        }
    }

    companion object {
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val MAX_TOOL_ROUNDS = 4
    }
}
