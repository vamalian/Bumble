package com.example.ai

enum class CoreState {
    IDLE,
    CONNECTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

enum class AssistantStatus {
    READY,
    CONNECTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

enum class LiveConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

enum class MicHardwareStatus {
    STANDBY,
    INITIALIZING,
    ACTIVE_16KHZ_PCM,
    PERMISSION_REQUIRED,
    HARDWARE_CONFLICT,
    ERROR
}

enum class LiveErrorCategory {
    NONE,
    MISSING_API_KEY,
    INVALID_API_KEY,
    QUOTA_EXCEEDED,
    UNSUPPORTED_MODEL,
    NETWORK_ERROR,
    MICROPHONE_ERROR,
    AUDIO_OUTPUT_ERROR,
    PROTOCOL_ERROR
}

data class LiveApiError(
    val category: LiveErrorCategory,
    val message: String,
    val httpCode: Int? = null,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class LiveDiagnostics(
    val connectionStatus: LiveConnectionStatus = LiveConnectionStatus.DISCONNECTED,
    val micStatus: MicHardwareStatus = MicHardwareStatus.STANDBY,
    val activeAudioSource: String = "None",
    val activeModel: String = "",
    val endpointLabel: String = "v1beta BidiGenerateContent",
    val reconnectAttempt: Int = 0,
    val maxReconnectAttempts: Int = 3,
    val lastConnectLatencyMs: Long? = null,
    val audioFramesSent: Long = 0L,
    val audioFramesReceived: Long = 0L,
    val lastError: LiveApiError? = null,
    val sessionResumable: Boolean = false
)
