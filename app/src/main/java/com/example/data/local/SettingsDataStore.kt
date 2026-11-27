package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.bumbleDataStore: DataStore<Preferences> by preferencesDataStore(name = "bumble_settings")

data class BumbleSettings(
    val liveModel: String = DEFAULT_LIVE_MODEL,
    val visionModel: String = DEFAULT_VISION_MODEL,
    val liveVoice: String = DEFAULT_LIVE_VOICE,
    val languageMode: String = "Multilingual (EN / UR / HI)",
    val personality: String = "JARVIS Tactical",
    val customSystemPrompt: String = "",
    val wakeWordEnabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val voiceBargeInEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val particleDensity: Float = 1.0f,
    val saveConversationHistory: Boolean = true,
    val allowMemorySuggestions: Boolean = true,
    val allowScreenCaptureTool: Boolean = false
) {
    companion object {
        const val DEFAULT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        const val DEFAULT_VISION_MODEL = "gemini-3.8-flash"
        const val DEFAULT_LIVE_VOICE = "Kore"

        val LIVE_MODEL_OPTIONS = listOf(
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025",
            "gemini-2.5-flash-native-audio-preview-09-2025"
        )

        val VISION_MODEL_OPTIONS = listOf(
            "gemini-3.8-flash",
            "gemini-4-argon",
            "gemini-3.5-flash-lite"
        )

        val LIVE_VOICE_OPTIONS = listOf(
            "Kore",
            "Puck",
            "Charon",
            "Fenrir",
            "Aoede",
            "Zephyr",
            "Orus",
            "Leda"
        )

        val LANGUAGE_OPTIONS = listOf(
            "Multilingual (EN / UR / HI)",
            "English",
            "Urdu (اردو)",
            "Hindi (हिन्दी)"
        )

        val PERSONALITY_OPTIONS = listOf(
            "JARVIS Tactical",
            "Warm Companion",
            "Concise Executive",
            "Multilingual Tutor",
            "Custom"
        )

        fun sanitizeLiveModelId(raw: String?): String {
            val cleaned = raw?.trim().orEmpty().removePrefix("models/")
            if (cleaned.isBlank() ||
                cleaned.startsWith("gemini-1.5-", ignoreCase = true) ||
                cleaned.startsWith("gemini-2.0-", ignoreCase = true) ||
                cleaned.equals("gemini-pro", ignoreCase = true)
            ) {
                return DEFAULT_LIVE_MODEL
            }
            val supportsLive = cleaned.contains("live", ignoreCase = true) ||
                cleaned.contains("native-audio", ignoreCase = true)
            return if (supportsLive) cleaned else DEFAULT_LIVE_MODEL
        }

        fun sanitizeVisionModelId(raw: String?): String {
            val cleaned = raw?.trim().orEmpty().removePrefix("models/")
            if (cleaned.isBlank() ||
                cleaned.contains("native-audio", ignoreCase = true) ||
                cleaned.contains("-live-", ignoreCase = true) ||
                cleaned.startsWith("gemini-1.5-", ignoreCase = true) ||
                cleaned.startsWith("gemini-2.0-", ignoreCase = true) ||
                cleaned.startsWith("gemini-2.5-", ignoreCase = true) ||
                cleaned.equals("gemini-pro", ignoreCase = true)
            ) {
                return DEFAULT_VISION_MODEL
            }
            return cleaned
        }
    }
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val LIVE_MODEL = stringPreferencesKey("live_model")
        val LEGACY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val VISION_MODEL = stringPreferencesKey("vision_model")
        val LIVE_VOICE = stringPreferencesKey("live_voice")
        val LANGUAGE_MODE = stringPreferencesKey("language_mode")
        val PERSONALITY = stringPreferencesKey("personality")
        val CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
        val WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        val AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        val BARGE_IN = booleanPreferencesKey("voice_barge_in_enabled")
        val ANIMATIONS = booleanPreferencesKey("animations_enabled")
        val PARTICLE_DENSITY = floatPreferencesKey("particle_density")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val ALLOW_MEMORY = booleanPreferencesKey("allow_memory")
        val ALLOW_SCREEN_CAPTURE = booleanPreferencesKey("allow_screen_capture")
    }

    val settingsFlow: Flow<BumbleSettings> = context.bumbleDataStore.data.map { prefs ->
        val rawLive = prefs[Keys.LIVE_MODEL] ?: prefs[Keys.LEGACY_GEMINI_MODEL]
        val voiceRaw = prefs[Keys.LIVE_VOICE]
        val validVoice = if (voiceRaw in BumbleSettings.LIVE_VOICE_OPTIONS) {
            voiceRaw!!
        } else {
            BumbleSettings.DEFAULT_LIVE_VOICE
        }
        BumbleSettings(
            liveModel = BumbleSettings.sanitizeLiveModelId(rawLive),
            visionModel = BumbleSettings.sanitizeVisionModelId(prefs[Keys.VISION_MODEL]),
            liveVoice = validVoice,
            languageMode = prefs[Keys.LANGUAGE_MODE] ?: "Multilingual (EN / UR / HI)",
            personality = prefs[Keys.PERSONALITY] ?: "JARVIS Tactical",
            customSystemPrompt = prefs[Keys.CUSTOM_PROMPT] ?: "",
            wakeWordEnabled = prefs[Keys.WAKE_WORD] ?: true,
            autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
            voiceBargeInEnabled = prefs[Keys.BARGE_IN] ?: true,
            animationsEnabled = prefs[Keys.ANIMATIONS] ?: true,
            particleDensity = prefs[Keys.PARTICLE_DENSITY] ?: 1.0f,
            saveConversationHistory = prefs[Keys.SAVE_HISTORY] ?: true,
            allowMemorySuggestions = prefs[Keys.ALLOW_MEMORY] ?: true,
            allowScreenCaptureTool = prefs[Keys.ALLOW_SCREEN_CAPTURE] ?: false
        )
    }

    suspend fun updateSettings(transform: (BumbleSettings) -> BumbleSettings) {
        context.bumbleDataStore.edit { prefs ->
            val rawLive = prefs[Keys.LIVE_MODEL] ?: prefs[Keys.LEGACY_GEMINI_MODEL]
            val voiceRaw = prefs[Keys.LIVE_VOICE]
            val validVoice = if (voiceRaw in BumbleSettings.LIVE_VOICE_OPTIONS) {
                voiceRaw!!
            } else {
                BumbleSettings.DEFAULT_LIVE_VOICE
            }
            val current = BumbleSettings(
                liveModel = BumbleSettings.sanitizeLiveModelId(rawLive),
                visionModel = BumbleSettings.sanitizeVisionModelId(prefs[Keys.VISION_MODEL]),
                liveVoice = validVoice,
                languageMode = prefs[Keys.LANGUAGE_MODE] ?: "Multilingual (EN / UR / HI)",
                personality = prefs[Keys.PERSONALITY] ?: "JARVIS Tactical",
                customSystemPrompt = prefs[Keys.CUSTOM_PROMPT] ?: "",
                wakeWordEnabled = prefs[Keys.WAKE_WORD] ?: true,
                autoReconnect = prefs[Keys.AUTO_RECONNECT] ?: true,
                voiceBargeInEnabled = prefs[Keys.BARGE_IN] ?: true,
                animationsEnabled = prefs[Keys.ANIMATIONS] ?: true,
                particleDensity = prefs[Keys.PARTICLE_DENSITY] ?: 1.0f,
                saveConversationHistory = prefs[Keys.SAVE_HISTORY] ?: true,
                allowMemorySuggestions = prefs[Keys.ALLOW_MEMORY] ?: true,
                allowScreenCaptureTool = prefs[Keys.ALLOW_SCREEN_CAPTURE] ?: false
            )
            val updated = transform(current)
            prefs[Keys.LIVE_MODEL] = BumbleSettings.sanitizeLiveModelId(updated.liveModel)
            prefs[Keys.VISION_MODEL] = BumbleSettings.sanitizeVisionModelId(updated.visionModel)
            prefs[Keys.LIVE_VOICE] = if (updated.liveVoice in BumbleSettings.LIVE_VOICE_OPTIONS) {
                updated.liveVoice
            } else {
                BumbleSettings.DEFAULT_LIVE_VOICE
            }
            prefs[Keys.LANGUAGE_MODE] = updated.languageMode
            prefs[Keys.PERSONALITY] = updated.personality
            prefs[Keys.CUSTOM_PROMPT] = updated.customSystemPrompt
            prefs[Keys.WAKE_WORD] = updated.wakeWordEnabled
            prefs[Keys.AUTO_RECONNECT] = updated.autoReconnect
            prefs[Keys.BARGE_IN] = updated.voiceBargeInEnabled
            prefs[Keys.ANIMATIONS] = updated.animationsEnabled
            prefs[Keys.PARTICLE_DENSITY] = updated.particleDensity
            prefs[Keys.SAVE_HISTORY] = updated.saveConversationHistory
            prefs[Keys.ALLOW_MEMORY] = updated.allowMemorySuggestions
            prefs[Keys.ALLOW_SCREEN_CAPTURE] = updated.allowScreenCaptureTool
        }
    }
}
