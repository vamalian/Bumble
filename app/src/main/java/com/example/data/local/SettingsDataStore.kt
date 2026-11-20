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
    val voiceName: String = "Kore",
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
        const val DEFAULT_LIVE_MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        const val DEFAULT_VISION_MODEL = "gemini-2.5-flash"

        val LIVE_MODEL_OPTIONS = listOf(
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            "models/gemini-2.5-flash-native-audio-preview-09-2025",
            "models/gemini-2.0-flash-live-001"
        )

        val VISION_MODEL_OPTIONS = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-2.5-flash-lite"
        )

        val VOICE_OPTIONS = listOf("Kore", "Puck", "Charon", "Fenrir", "Aoede", "Zephyr")

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
    }
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val LIVE_MODEL = stringPreferencesKey("live_model")
        val VISION_MODEL = stringPreferencesKey("vision_model")
        val VOICE_NAME = stringPreferencesKey("voice_name")
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
            liveModel = prefs[Keys.LIVE_MODEL] ?: BumbleSettings.DEFAULT_LIVE_MODEL,
            visionModel = prefs[Keys.VISION_MODEL] ?: BumbleSettings.DEFAULT_VISION_MODEL,
            voiceName = prefs[Keys.VOICE_NAME] ?: "Kore",
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
                liveModel = prefs[Keys.LIVE_MODEL] ?: BumbleSettings.DEFAULT_LIVE_MODEL,
                visionModel = prefs[Keys.VISION_MODEL] ?: BumbleSettings.DEFAULT_VISION_MODEL,
                voiceName = prefs[Keys.VOICE_NAME] ?: "Kore",
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
            prefs[Keys.LIVE_MODEL] = updated.liveModel
            prefs[Keys.VISION_MODEL] = updated.visionModel
            prefs[Keys.VOICE_NAME] = updated.voiceName
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
