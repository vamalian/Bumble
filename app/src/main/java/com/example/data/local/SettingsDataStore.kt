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
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val visionModel: String = DEFAULT_VISION_MODEL,
    val voiceProfile: String = "JARVIS Crisp",
    val autoSpeakReplies: Boolean = true,
    val languageMode: String = "Multilingual (EN / UR / HI)",
    val personality: String = "JARVIS Tactical",
    val customSystemPrompt: String = "",
    val wakeWordEnabled: Boolean = true,
    val animationsEnabled: Boolean = true,
    val particleDensity: Float = 1.0f,
    val saveConversationHistory: Boolean = true,
    val allowMemorySuggestions: Boolean = true,
    val allowScreenCaptureTool: Boolean = false
) {
    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
        const val DEFAULT_VISION_MODEL = "gemini-2.5-flash"

        val GEMINI_MODEL_OPTIONS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro"
        )

        val VISION_MODEL_OPTIONS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite"
        )

        val VOICE_PROFILES = listOf(
            "JARVIS Crisp",
            "Deep Tactical",
            "Warm Balanced",
            "Bright Fast",
            "Calm Natural"
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

        fun sanitizeModelId(raw: String?): String {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isBlank() ||
                trimmed.contains("native-audio", ignoreCase = true) ||
                trimmed.contains("-live-", ignoreCase = true)
            ) {
                return DEFAULT_GEMINI_MODEL
            }
            return trimmed.removePrefix("models/")
        }
    }
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val VISION_MODEL = stringPreferencesKey("vision_model")
        val VOICE_PROFILE = stringPreferencesKey("voice_profile")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak_replies")
        val LANGUAGE_MODE = stringPreferencesKey("language_mode")
        val PERSONALITY = stringPreferencesKey("personality")
        val CUSTOM_PROMPT = stringPreferencesKey("custom_prompt")
        val WAKE_WORD = booleanPreferencesKey("wake_word_enabled")
        val ANIMATIONS = booleanPreferencesKey("animations_enabled")
        val PARTICLE_DENSITY = floatPreferencesKey("particle_density")
        val SAVE_HISTORY = booleanPreferencesKey("save_history")
        val ALLOW_MEMORY = booleanPreferencesKey("allow_memory")
        val ALLOW_SCREEN_CAPTURE = booleanPreferencesKey("allow_screen_capture")
    }

    val settingsFlow: Flow<BumbleSettings> = context.bumbleDataStore.data.map { prefs ->
        BumbleSettings(
            geminiModel = BumbleSettings.sanitizeModelId(prefs[Keys.GEMINI_MODEL]),
            visionModel = BumbleSettings.sanitizeModelId(prefs[Keys.VISION_MODEL]),
            voiceProfile = prefs[Keys.VOICE_PROFILE] ?: "JARVIS Crisp",
            autoSpeakReplies = prefs[Keys.AUTO_SPEAK] ?: true,
            languageMode = prefs[Keys.LANGUAGE_MODE] ?: "Multilingual (EN / UR / HI)",
            personality = prefs[Keys.PERSONALITY] ?: "JARVIS Tactical",
            customSystemPrompt = prefs[Keys.CUSTOM_PROMPT] ?: "",
            wakeWordEnabled = prefs[Keys.WAKE_WORD] ?: true,
            animationsEnabled = prefs[Keys.ANIMATIONS] ?: true,
            particleDensity = prefs[Keys.PARTICLE_DENSITY] ?: 1.0f,
            saveConversationHistory = prefs[Keys.SAVE_HISTORY] ?: true,
            allowMemorySuggestions = prefs[Keys.ALLOW_MEMORY] ?: true,
            allowScreenCaptureTool = prefs[Keys.ALLOW_SCREEN_CAPTURE] ?: false
        )
    }

    suspend fun updateSettings(transform: (BumbleSettings) -> BumbleSettings) {
        context.bumbleDataStore.edit { prefs ->
            val current = BumbleSettings(
                geminiModel = BumbleSettings.sanitizeModelId(prefs[Keys.GEMINI_MODEL]),
                visionModel = BumbleSettings.sanitizeModelId(prefs[Keys.VISION_MODEL]),
                voiceProfile = prefs[Keys.VOICE_PROFILE] ?: "JARVIS Crisp",
                autoSpeakReplies = prefs[Keys.AUTO_SPEAK] ?: true,
                languageMode = prefs[Keys.LANGUAGE_MODE] ?: "Multilingual (EN / UR / HI)",
                personality = prefs[Keys.PERSONALITY] ?: "JARVIS Tactical",
                customSystemPrompt = prefs[Keys.CUSTOM_PROMPT] ?: "",
                wakeWordEnabled = prefs[Keys.WAKE_WORD] ?: true,
                animationsEnabled = prefs[Keys.ANIMATIONS] ?: true,
                particleDensity = prefs[Keys.PARTICLE_DENSITY] ?: 1.0f,
                saveConversationHistory = prefs[Keys.SAVE_HISTORY] ?: true,
                allowMemorySuggestions = prefs[Keys.ALLOW_MEMORY] ?: true,
                allowScreenCaptureTool = prefs[Keys.ALLOW_SCREEN_CAPTURE] ?: false
            )
            val updated = transform(current)
            prefs[Keys.GEMINI_MODEL] = BumbleSettings.sanitizeModelId(updated.geminiModel)
            prefs[Keys.VISION_MODEL] = BumbleSettings.sanitizeModelId(updated.visionModel)
            prefs[Keys.VOICE_PROFILE] = updated.voiceProfile
            prefs[Keys.AUTO_SPEAK] = updated.autoSpeakReplies
            prefs[Keys.LANGUAGE_MODE] = updated.languageMode
            prefs[Keys.PERSONALITY] = updated.personality
            prefs[Keys.CUSTOM_PROMPT] = updated.customSystemPrompt
            prefs[Keys.WAKE_WORD] = updated.wakeWordEnabled
            prefs[Keys.ANIMATIONS] = updated.animationsEnabled
            prefs[Keys.PARTICLE_DENSITY] = updated.particleDensity
            prefs[Keys.SAVE_HISTORY] = updated.saveConversationHistory
            prefs[Keys.ALLOW_MEMORY] = updated.allowMemorySuggestions
            prefs[Keys.ALLOW_SCREEN_CAPTURE] = updated.allowScreenCaptureTool
        }
    }
}
