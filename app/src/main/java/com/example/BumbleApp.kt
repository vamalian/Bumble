package com.example

import android.app.Application
import com.example.ai.GeminiLiveClient
import com.example.ai.GeminiRestClient
import com.example.ai.LiveAudioEngine
import com.example.ai.WakeWordManager
import com.example.assistant.AndroidActionExecutor
import com.example.assistant.ExternalToolsClient
import com.example.data.local.AdminCredentialStore
import com.example.data.local.BumbleDatabase
import com.example.data.local.SecureKeyStore
import com.example.data.local.SettingsDataStore

class BumbleApp : Application() {

    val database: BumbleDatabase by lazy { BumbleDatabase.getInstance(this) }
    val secureKeyStore: SecureKeyStore by lazy { SecureKeyStore(this) }
    val adminCredentialStore: AdminCredentialStore by lazy { AdminCredentialStore(this) }
    val settingsDataStore: SettingsDataStore by lazy { SettingsDataStore(this) }
    val externalTools: ExternalToolsClient by lazy { ExternalToolsClient() }
    val geminiRestClient: GeminiRestClient by lazy { GeminiRestClient() }
    val geminiLiveClient: GeminiLiveClient by lazy { GeminiLiveClient() }
    val liveAudioEngine: LiveAudioEngine by lazy { LiveAudioEngine(this) }
    val wakeWordManager: WakeWordManager by lazy { WakeWordManager(this) }

    val actionExecutor: AndroidActionExecutor by lazy {
        AndroidActionExecutor(
            context = this,
            noteDao = database.noteDao(),
            reminderDao = database.reminderDao(),
            memoryDao = database.memoryDao(),
            externalTools = externalTools
        )
    }
}
