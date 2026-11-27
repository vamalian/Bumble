package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.ai.ConnectionTestResult
import com.example.ai.LiveApiError
import com.example.ai.LiveDiagnostics
import com.example.ai.WakeWordStatus
import com.example.assistant.NotificationCache
import com.example.data.local.AdminCredentialStore
import com.example.data.local.BumbleSettings
import com.example.ui.AdminUiState
import com.example.ui.PrivacySafeStats
import com.example.ui.components.HudPanel
import com.example.ui.components.HudTelemetryPill
import com.example.ui.theme.BumbleAmber
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AdminScreen(
    adminState: AdminUiState,
    liveDiagnostics: LiveDiagnostics,
    recentErrors: List<LiveApiError>,
    stats: PrivacySafeStats,
    settings: BumbleSettings,
    hasApiKey: Boolean,
    maskedApiKey: String,
    isTestingRest: Boolean,
    restConnectionResult: ConnectionTestResult?,
    wakeWordStatus: WakeWordStatus,
    onLogin: (String) -> Unit,
    onLogout: () -> Unit,
    onTouchSession: () -> Unit,
    onChangePassword: (String, String, String) -> Unit,
    onTestLiveHandshake: () -> Unit,
    onTestRestConnection: () -> Unit,
    onRunMicTest: () -> Unit,
    onRunSpeakerTest: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onUpdateSettings: ((BumbleSettings) -> BumbleSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!adminState.isAuthenticated) {
        AdminLoginGate(
            adminState = adminState,
            onLogin = onLogin,
            onBack = onBack,
            modifier = modifier
        )
    } else {
        AdminAuthenticatedDashboard(
            adminState = adminState,
            liveDiagnostics = liveDiagnostics,
            recentErrors = recentErrors,
            stats = stats,
            settings = settings,
            hasApiKey = hasApiKey,
            maskedApiKey = maskedApiKey,
            isTestingRest = isTestingRest,
            restConnectionResult = restConnectionResult,
            wakeWordStatus = wakeWordStatus,
            onLogout = onLogout,
            onTouchSession = onTouchSession,
            onChangePassword = onChangePassword,
            onTestLiveHandshake = onTestLiveHandshake,
            onTestRestConnection = onTestRestConnection,
            onRunMicTest = onRunMicTest,
            onRunSpeakerTest = onRunSpeakerTest,
            onSaveApiKey = onSaveApiKey,
            onClearApiKey = onClearApiKey,
            onUpdateSettings = onUpdateSettings,
            onBack = onBack,
            modifier = modifier
        )
    }
}

@Composable
private fun AdminLoginGate(
    adminState: AdminUiState,
    onLogin: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var passwordDraft by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("admin_login_back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BumbleGold)
                }
                Text("Owner Console", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)
            }

            HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = BumbleGoldBright)
                    Column {
                        Text("OWNER AUTHENTICATION REQUIRED", style = MaterialTheme.typography.labelLarge, color = BumbleGoldBright)
                        Text(
                            "Enter the administrator password to unlock diagnostics, hardware audio tests, and system configuration.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BumbleTextSecondary
                        )
                    }
                }

                OutlinedTextField(
                    value = passwordDraft,
                    onValueChange = { passwordDraft = it },
                    label = { Text("Administrator Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password visibility"
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (adminState.lockoutRemainingSeconds == 0L) {
                            val candidate = passwordDraft
                            passwordDraft = ""
                            onLogin(candidate)
                        }
                    }),
                    modifier = Modifier.fillMaxWidth().testTag("admin_password_input")
                )

                adminState.authError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BumbleCrimson,
                        modifier = Modifier.testTag("admin_auth_error")
                    )
                }

                if (adminState.lockoutRemainingSeconds > 0L) {
                    Text(
                        text = "Authentication temporarily locked (${adminState.lockoutRemainingSeconds}s remaining).",
                        style = MaterialTheme.typography.labelMedium,
                        color = BumbleCrimson
                    )
                }

                Button(
                    onClick = {
                        val candidate = passwordDraft
                        passwordDraft = ""
                        onLogin(candidate)
                    },
                    enabled = adminState.lockoutRemainingSeconds == 0L,
                    colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                    modifier = Modifier.fillMaxWidth().height(48.dp).testTag("admin_login_button")
                ) {
                    Text("Unlock Owner Console")
                }
            }

            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = BumbleAmber)
                    Text("LOCAL SECURITY DISCLOSURE", style = MaterialTheme.typography.labelLarge, color = BumbleAmber)
                }
                Text(
                    "Bumble AI runs entirely locally without a backend authentication server. Passwords are verified using salted PBKDF2-HMAC-SHA256 (65,536 iterations) inside Android Keystore AES-256-GCM, with 5-attempt lockout and 5-minute session timeout. Local client authentication prevents casual access on-device, but cannot protect against rooted/decompiled binary tampering; move authentication to a backend server before multi-user release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BumbleTextSecondary
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdminAuthenticatedDashboard(
    adminState: AdminUiState,
    liveDiagnostics: LiveDiagnostics,
    recentErrors: List<LiveApiError>,
    stats: PrivacySafeStats,
    settings: BumbleSettings,
    hasApiKey: Boolean,
    maskedApiKey: String,
    isTestingRest: Boolean,
    restConnectionResult: ConnectionTestResult?,
    wakeWordStatus: WakeWordStatus,
    onLogout: () -> Unit,
    onTouchSession: () -> Unit,
    onChangePassword: (String, String, String) -> Unit,
    onTestLiveHandshake: () -> Unit,
    onTestRestConnection: () -> Unit,
    onRunMicTest: () -> Unit,
    onRunSpeakerTest: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onUpdateSettings: ((BumbleSettings) -> BumbleSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var apiKeyDraft by remember { mutableStateOf("") }
    var showApiKeyPlain by remember { mutableStateOf(false) }

    var currentPw by remember { mutableStateOf("") }
    var newPw by remember { mutableStateOf("") }
    var confirmPw by remember { mutableStateOf("") }
    var showPwFields by remember { mutableStateOf(false) }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    val micPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val camPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val notifPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true
    val notifListener = NotificationCache.isListenerEnabled(context)
    val writeSettings = Settings.System.canWrite(context)

    val rt = Runtime.getRuntime()
    val usedHeapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    val maxHeapMb = rt.maxMemory() / (1024 * 1024)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Owner Dashboard Header + Session Timer + Logout
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = BumbleGold)
                    }
                    Column {
                        Text("Owner Admin Dashboard", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)
                        val mins = adminState.sessionRemainingSeconds / 60
                        val secs = adminState.sessionRemainingSeconds % 60
                        Text(
                            text = "Session locks automatically in %d:%02d".format(mins, secs),
                            style = MaterialTheme.typography.labelSmall,
                            color = BumbleTextSecondary
                        )
                    }
                }
                OutlinedButton(
                    onClick = onLogout,
                    border = BorderStroke(1.dp, BumbleCrimson.copy(alpha = 0.7f)),
                    modifier = Modifier.testTag("logout_admin_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = BumbleCrimson)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Lock", color = BumbleCrimson)
                }
            }

            if (adminState.isUsingDefaultPassword) {
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = BumbleCrimson)
                        Text("ACTION REQUIRED: CHANGE INITIAL OWNER PASSWORD", style = MaterialTheme.typography.labelLarge, color = BumbleCrimson)
                    }
                    Text(
                        "You are currently using the initial setup password. Update it in the Owner Security section below immediately.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BumbleTextSecondary
                    )
                }
            }

            // 2 & 3. Gemini API Connection Status + Current Model Info
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = BumbleGold)
                    Text("GEMINI LIVE & MODEL TELEMETRY", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                }
                HudTelemetryPill(
                    label = "LIVE: ${liveDiagnostics.connectionStatus.name} • MIC: ${liveDiagnostics.micStatus.name}",
                    dotColor = if (hasApiKey) BumbleEmerald else BumbleCrimson
                )
                Text(
                    text = buildString {
                        appendLine("• Live Model: ${settings.liveModel}")
                        appendLine("• Vision/Tools Model: ${settings.visionModel}")
                        appendLine("• Live Voice: ${settings.liveVoice} • Endpoint: ${liveDiagnostics.endpointLabel}")
                        appendLine("• Connect Latency: ${liveDiagnostics.lastConnectLatencyMs?.let { "${it}ms" } ?: "Not connected yet"}")
                        appendLine("• Frames Sent: ${liveDiagnostics.audioFramesSent} • Audio Chunks Received: ${liveDiagnostics.audioFramesReceived}")
                        append("• Reconnect Attempts: ${liveDiagnostics.reconnectAttempt}/${liveDiagnostics.maxReconnectAttempts} • Resumable: ${liveDiagnostics.sessionResumable}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = BumbleTextSecondary
                )
            }

            // 4. API Quota & Error Status
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("API QUOTA & ERROR STATUS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                if (recentErrors.isEmpty()) {
                    Text(
                        "No API, quota (429), key, or WebSocket errors recorded in this session.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BumbleEmerald
                    )
                } else {
                    recentErrors.take(5).forEach { err ->
                        val t = timeFmt.format(Date(err.timestampMillis))
                        Text(
                            text = "[$t] ${err.category.name}${err.httpCode?.let { " (HTTP $it)" } ?: ""}: ${err.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = BumbleCrimson
                        )
                    }
                }
            }

            // 5. AI Connection Diagnostics (Live + REST)
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("AI CONNECTION DIAGNOSTICS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onTouchSession()
                            onTestLiveHandshake()
                        },
                        enabled = !adminState.isTestingLiveHandshake,
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                        modifier = Modifier.testTag("admin_test_live_button")
                    ) {
                        if (adminState.isTestingLiveHandshake) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BumbleVoidBlack, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.GraphicEq, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test Live WebSocket")
                    }

                    OutlinedButton(
                        onClick = {
                            onTouchSession()
                            onTestRestConnection()
                        },
                        enabled = !isTestingRest,
                        border = BorderStroke(1.dp, BumbleGold),
                        modifier = Modifier.testTag("admin_test_rest_button")
                    ) {
                        if (isTestingRest) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BumbleGold, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = BumbleGoldBright)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test REST + Live", color = BumbleGoldBright)
                    }
                }

                adminState.liveHandshakeResult?.let { res ->
                    DiagnosticRow(success = res.success, text = res.message)
                }
                restConnectionResult?.let { res ->
                    DiagnosticRow(success = res.success, text = res.message)
                }
            }

            // 6 & 7. Real Microphone Test & Real Speaker Audio Test
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("HARDWARE AUDIO DIAGNOSTICS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onTouchSession()
                            onRunMicTest()
                        },
                        enabled = !adminState.isRunningMicTest,
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                        modifier = Modifier.testTag("admin_mic_test_button")
                    ) {
                        if (adminState.isRunningMicTest) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BumbleVoidBlack, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Mic, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test 16 kHz Mic")
                    }

                    OutlinedButton(
                        onClick = {
                            onTouchSession()
                            onRunSpeakerTest()
                        },
                        enabled = !adminState.isRunningSpeakerTest,
                        border = BorderStroke(1.dp, BumbleGold),
                        modifier = Modifier.testTag("admin_speaker_test_button")
                    ) {
                        if (adminState.isRunningSpeakerTest) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BumbleGold, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = BumbleGoldBright)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test 24 kHz Speaker", color = BumbleGoldBright)
                    }
                }

                adminState.micDiagnostic?.let { m ->
                    DiagnosticRow(
                        success = m.success,
                        text = "${m.message} (Source=${m.audioSource}, AEC=${m.hasHardwareAec})"
                    )
                }
                adminState.speakerDiagnostic?.let { s ->
                    DiagnosticRow(success = s.success, text = s.message)
                }
            }

            // 8 & 9. Permission Status & App Health Status
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = BumbleGold)
                    Text("PERMISSIONS & APP HEALTH", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                }
                Text(
                    text = buildString {
                        appendLine("• RECORD_AUDIO: ${if (micPerm) "GRANTED" else "DENIED"} | CAMERA: ${if (camPerm) "GRANTED" else "DENIED"}")
                        appendLine("• POST_NOTIFICATIONS: ${if (notifPerm) "GRANTED" else "DENIED"} | Listener: ${if (notifListener) "ON" else "OFF"} | Write Settings: ${if (writeSettings) "ON" else "OFF"}")
                        appendLine("• App ID: ${BuildConfig.APPLICATION_ID} (v${BuildConfig.VERSION_NAME})")
                        appendLine("• Android Keystore AES-256 Vault: ${if (adminState.isHardwareVaultReady) "ACTIVE" else "UNAVAILABLE"}")
                        appendLine("• Foreground Wake-Word: ${wakeWordStatus.name} (No background mic service)")
                        append("• JVM Heap: ${usedHeapMb}MB / ${maxHeapMb}MB")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = BumbleTextSecondary
                )
            }

            // 10. Privacy-Safe Conversation Stats (Aggregate Counts Only)
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("PRIVACY-SAFE STATS (COUNTS ONLY)", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                Text(
                    "Aggregate database counts only — private message text and session titles are never displayed in the Owner Console.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BumbleTextSecondary
                )
                Text(
                    text = "Sessions: ${stats.sessionCount}  •  Messages: ${stats.totalMessages} (User ${stats.userMessages} / Bumble ${stats.assistantMessages} / Tool ${stats.toolMessages})\n" +
                        "Memories: ${stats.memoryCount}  •  Notes: ${stats.noteCount}  •  Reminders: ${stats.reminderCount}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BumbleGoldBright
                )
            }

            // 11. Owner Model Configuration
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = BumbleGold)
                    Text("MODEL & VOICE CONFIGURATION", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                }
                Text("Gemini Live Model", style = MaterialTheme.typography.bodySmall, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BumbleSettings.LIVE_MODEL_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = settings.liveModel == m,
                            onClick = {
                                onTouchSession()
                                onUpdateSettings { it.copy(liveModel = m) }
                            },
                            label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }
                Text("Vision Model", style = MaterialTheme.typography.bodySmall, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BumbleSettings.VISION_MODEL_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = settings.visionModel == m,
                            onClick = {
                                onTouchSession()
                                onUpdateSettings { it.copy(visionModel = m) }
                            },
                            label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }
                Text("Live Voice", style = MaterialTheme.typography.bodySmall, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BumbleSettings.LIVE_VOICE_OPTIONS.forEach { v ->
                        FilterChip(
                            selected = settings.liveVoice == v,
                            onClick = {
                                onTouchSession()
                                onUpdateSettings { it.copy(liveVoice = v) }
                            },
                            label = { Text(v) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }
            }

            // 12. Owner API Key Configuration
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = BumbleGold)
                    Text("API KEY CONFIGURATION", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                }
                Text(
                    text = if (hasApiKey) "Active encrypted key: $maskedApiKey" else "No Gemini API key stored.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasApiKey) BumbleEmerald else BumbleCrimson
                )
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    label = { Text("Enter or Replace Gemini API Key") },
                    singleLine = true,
                    visualTransformation = if (showApiKeyPlain) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKeyPlain = !showApiKeyPlain }) {
                            Icon(
                                imageVector = if (showApiKeyPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle API key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("admin_api_key_input")
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onTouchSession()
                            if (apiKeyDraft.isNotBlank()) {
                                onSaveApiKey(apiKeyDraft)
                                apiKeyDraft = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack)
                    ) {
                        Text("Save Encrypted Key")
                    }
                    if (hasApiKey) {
                        OutlinedButton(
                            onClick = {
                                onTouchSession()
                                onClearApiKey()
                            },
                            border = BorderStroke(1.dp, BumbleCrimson.copy(alpha = 0.6f))
                        ) {
                            Text("Remove Key", color = BumbleCrimson)
                        }
                    }
                }
            }

            // 13. App Settings Controls
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("APP RUNTIME CONTROLS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                AdminToggleRow(
                    label = "Auto-Reconnect Live WebSocket",
                    checked = settings.autoReconnect,
                    onChecked = { v -> onTouchSession(); onUpdateSettings { it.copy(autoReconnect = v) } }
                )
                AdminToggleRow(
                    label = "Voice Barge-In Interruption",
                    checked = settings.voiceBargeInEnabled,
                    onChecked = { v -> onTouchSession(); onUpdateSettings { it.copy(voiceBargeInEnabled = v) } }
                )
                AdminToggleRow(
                    label = "Foreground 'Hey Bumble' Wake Word",
                    checked = settings.wakeWordEnabled,
                    onChecked = { v -> onTouchSession(); onUpdateSettings { it.copy(wakeWordEnabled = v) } }
                )
                AdminToggleRow(
                    label = "Save Conversation Transcripts",
                    checked = settings.saveConversationHistory,
                    onChecked = { v -> onTouchSession(); onUpdateSettings { it.copy(saveConversationHistory = v) } }
                )
                AdminToggleRow(
                    label = "Holographic Core Animations",
                    checked = settings.animationsEnabled,
                    onChecked = { v -> onTouchSession(); onUpdateSettings { it.copy(animationsEnabled = v) } }
                )
            }

            // 14 & 15. Change Admin Password + Secure Logout
            HudPanel(highlightBorder = adminState.isUsingDefaultPassword, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = BumbleGoldBright)
                    Text("OWNER PASSWORD & SESSION SECURITY", style = MaterialTheme.typography.labelLarge, color = BumbleGoldBright)
                }
                Text(
                    "New password must be at least ${AdminCredentialStore.MIN_PASSWORD_LENGTH} characters and cannot reuse the initial setup password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BumbleTextSecondary
                )

                OutlinedTextField(
                    value = currentPw,
                    onValueChange = { currentPw = it },
                    label = { Text("Current Password") },
                    singleLine = true,
                    visualTransformation = if (showPwFields) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("admin_current_pw_input")
                )
                OutlinedTextField(
                    value = newPw,
                    onValueChange = { newPw = it },
                    label = { Text("New Owner Password (min ${AdminCredentialStore.MIN_PASSWORD_LENGTH} chars)") },
                    singleLine = true,
                    visualTransformation = if (showPwFields) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("admin_new_pw_input")
                )
                OutlinedTextField(
                    value = confirmPw,
                    onValueChange = { confirmPw = it },
                    label = { Text("Confirm New Password") },
                    singleLine = true,
                    visualTransformation = if (showPwFields) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPwFields = !showPwFields }) {
                            Icon(
                                imageVector = if (showPwFields) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password fields"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("admin_confirm_pw_input")
                )

                adminState.passwordChangeMessage?.let { msg ->
                    DiagnosticRow(success = adminState.passwordChangeSuccess, text = msg)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onChangePassword(currentPw, newPw, confirmPw)
                            currentPw = ""
                            newPw = ""
                            confirmPw = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                        modifier = Modifier.testTag("admin_change_pw_button")
                    ) {
                        Text("Update Password")
                    }

                    OutlinedButton(
                        onClick = onLogout,
                        border = BorderStroke(1.dp, BumbleCrimson),
                        modifier = Modifier.testTag("admin_bottom_logout_button")
                    ) {
                        Text("Secure Logout", color = BumbleCrimson)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DiagnosticRow(success: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (success) BumbleEmerald else BumbleCrimson,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (success) BumbleEmerald else BumbleCrimson
        )
    }
}

@Composable
private fun AdminToggleRow(
    label: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BumbleVoidBlack,
                checkedTrackColor = BumbleGold
            )
        )
    }
}
