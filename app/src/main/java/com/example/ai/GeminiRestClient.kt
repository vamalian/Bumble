package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
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

/**
 * Official Gemini REST API client for:
 * - API key & connection testing
 * - Multimodal Vision analysis (CameraX, Photo Picker, Consented Screen Capture)
 * - Standalone AI Tools (Translation, Daily Planner, Knowledge Q&A)
 */
class GeminiRestClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun testConnection(apiKey: String): ConnectionTestResult = withContext(Dispatchers.IO) {
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
            val url = "$API_BASE/models?key=$cleanKey&pageSize=20"
            val request = Request.Builder().url(url).get().build()
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
                val models = JSONObject(body).optJSONArray("models")?.length() ?: 0
                ConnectionTestResult(
                    success = true,
                    latencyMs = elapsed,
                    message = "Verified! Connected to Gemini API ($models models visible) in ${elapsed}ms.",
                    availableModelsCount = models
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

            val scaled = scaleBitmapIfNeeded(bitmap, maxDim = 1280)
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val imageB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            val parts = JSONArray()
                .put(
                    JSONObject().put(
                        "text",
                        "$prompt\nLanguage preference: $languageMode. Be accurate, observant, and concise."
                    )
                )
                .put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", imageB64)
                    )
                )

            val bodyJson = JSONObject().put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )

            callGenerateContent(cleanKey, model, bodyJson)
        }
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

            callGenerateContent(cleanKey, model, bodyJson)
        }
    }

    private fun callGenerateContent(apiKey: String, model: String, payload: JSONObject): String {
        val modelId = model.removePrefix("models/")
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
            val root = JSONObject(raw)
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
                val txt = parts.getJSONObject(i).optString("text", "")
                if (txt.isNotBlank()) sb.append(txt)
            }
            return sb.toString().ifBlank { "No text response generated." }
        }
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDim && h <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }

    companion object {
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}
