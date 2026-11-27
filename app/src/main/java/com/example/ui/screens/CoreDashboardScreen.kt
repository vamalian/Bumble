package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ai.CoreState
import com.example.ai.LiveConnectionStatus
import com.example.ai.LiveDiagnostics
import com.example.ai.MicHardwareStatus
import com.example.ai.WakeWordStatus
import com.example.data.local.BumbleSettings
import com.example.ui.ChatEntry
import com.example.ui.components.HolographicCore
import com.example.ui.components.HudPanel
import com.example.ui.components.HudTelemetryPill
import com.example.ui.theme.BumbleAmber
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleGraphite
import com.example.ui.theme.BumbleGraphiteElevated
import com.example.ui.theme.BumbleHudBorder
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoreDashboardScreen(
    coreState: CoreState,
    liveDiagnostics: LiveDiagnostics,
    wakeWordStatus: WakeWordStatus,
    statusBanner: String,
    liveInputTranscript: String,
    liveOutputTranscript: String,
    audioAmplitude: Float,
    settings: BumbleSettings,
    hasApiKey: Boolean,
    hasMicPermission: Boolean,
    isMicMuted: Boolean,
    isSpeakerMuted: Boolean,
    latestEntry: ChatEntry?,
    onTapCoreVoice: () -> Unit,
    onRetryConnection: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onSendQuickCommand: (String) -> Unit,
    onNavigateToChat: () -> Unit,
    onNavigateToVision: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    modifier: Modifier = Modifier
) {
    var quickInput by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top HUD Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "BUMBLE AI",
                        style = MaterialTheme.typography.headlineLarge,
                        color = BumbleGoldBright
                    )
                    Text(
                        text = "${settings.liveModel.uppercase()} • VOICE ${settings.liveVoice.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BumbleTextSecondary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onNavigateToPrivacy,
                        modifier = Modifier.testTag("header_privacy_button")
                    ) {
                        Icon(Icons.Default.Security, contentDescription = "Permissions and Privacy", tint = BumbleGold)
                    }
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("header_settings_button")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = BumbleGold)
                    }
                }
            }

            // Primary 6-State Telemetry Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dotColor = when (coreState) {
                    CoreState.ERROR -> BumbleCrimson
                    CoreState.LISTENING, CoreState.SPEAKING -> BumbleEmerald
                    CoreState.CONNECTING -> BumbleAmber
                    CoreState.PROCESSING, CoreState.IDLE -> BumbleGold
                }
                HudTelemetryPill(
                    label = "${coreState.name} • ${statusBanner.uppercase()}",
                    dotColor = dotColor
                )
            }

            // Live Link & Hardware Mic Status Strip
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val connLabel = when (liveDiagnostics.connectionStatus) {
                    LiveConnectionStatus.CONNECTED -> "LIVE: CONNECTED"
                    LiveConnectionStatus.CONNECTING -> "LIVE: HANDSHAKING"
                    LiveConnectionStatus.RECONNECTING -> "LIVE: RECONNECTING (${liveDiagnostics.reconnectAttempt}/${liveDiagnostics.maxReconnectAttempts})"
                    LiveConnectionStatus.ERROR -> "LIVE: ERROR"
                    LiveConnectionStatus.DISCONNECTED -> "LIVE: STANDBY"
                }
                val connDot = when (liveDiagnostics.connectionStatus) {
                    LiveConnectionStatus.CONNECTED -> BumbleEmerald
                    LiveConnectionStatus.ERROR -> BumbleCrimson
                    LiveConnectionStatus.CONNECTING, LiveConnectionStatus.RECONNECTING -> BumbleAmber
                    LiveConnectionStatus.DISCONNECTED -> BumbleGold
                }
                HudTelemetryPill(label = connLabel, dotColor = connDot)

                val micLabel = when (liveDiagnostics.micStatus) {
                    MicHardwareStatus.ACTIVE_16KHZ_PCM -> "MIC: 16KHZ ${liveDiagnostics.activeAudioSource}"
                    MicHardwareStatus.INITIALIZING -> "MIC: STARTING"
                    MicHardwareStatus.PERMISSION_REQUIRED -> "MIC: PERMISSION NEEDED"
                    MicHardwareStatus.HARDWARE_CONFLICT -> "MIC: CONFLICT"
                    MicHardwareStatus.ERROR -> "MIC: ERROR"
                    MicHardwareStatus.STANDBY -> "MIC: READY"
                }
                val micDot = when (liveDiagnostics.micStatus) {
                    MicHardwareStatus.ACTIVE_16KHZ_PCM -> BumbleEmerald
                    MicHardwareStatus.PERMISSION_REQUIRED, MicHardwareStatus.HARDWARE_CONFLICT, MicHardwareStatus.ERROR -> BumbleCrimson
                    MicHardwareStatus.INITIALIZING -> BumbleAmber
                    MicHardwareStatus.STANDBY -> BumbleGold
                }
                HudTelemetryPill(label = micLabel, dotColor = micDot)
            }

            // Central Golden Holographic Core
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                contentAlignment = Alignment.Center
            ) {
                HolographicCore(
                    coreState = coreState,
                    audioAmplitude = audioAmplitude,
                    animationsEnabled = settings.animationsEnabled,
                    particleDensity = settings.particleDensity,
                    onClick = onTapCoreVoice,
                    modifier = Modifier.size(270.dp)
                )
            }

            if (liveInputTranscript.isNotBlank()) {
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Text("LIVE MIC TRANSCRIPT", style = MaterialTheme.typography.labelSmall, color = BumbleGold)
                    Text(liveInputTranscript, style = MaterialTheme.typography.bodyLarge, color = BumbleGoldBright)
                }
            }

            if (liveOutputTranscript.isNotBlank()) {
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Text("BUMBLE LIVE", style = MaterialTheme.typography.labelSmall, color = BumbleEmerald)
                    Text(liveOutputTranscript, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            val wakeLabel = when (wakeWordStatus) {
                WakeWordStatus.STANDBY_LISTENING -> "WAKE PHRASE ACTIVE (FOREGROUND): \"HEY BUMBLE\""
                WakeWordStatus.TRIGGERED -> "WAKE PHRASE DETECTED — OPENING LIVE SESSION"
                WakeWordStatus.UNAVAILABLE -> "TAP CORE TO START GEMINI LIVE VOICE"
                WakeWordStatus.SUSPENDED_BACKGROUND -> "WAKE LISTENER SUSPENDED"
                WakeWordStatus.DISABLED -> if (!hasMicPermission) "TAP CORE TO GRANT MICROPHONE PERMISSION" else "TAP CORE TO START GEMINI LIVE SESSION"
            }
            Text(
                text = wakeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = BumbleTextSecondary,
                textAlign = TextAlign.Center
            )

            // Error Recovery Panel with Retry Control
            if (coreState == CoreState.ERROR) {
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = liveDiagnostics.lastError?.message ?: statusBanner,
                        style = MaterialTheme.typography.bodyMedium,
                        color = BumbleCrimson
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onRetryConnection,
                            colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                            modifier = Modifier.testTag("retry_live_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Live")
                        }
                        if (!hasApiKey) {
                            OutlinedButton(
                                onClick = onNavigateToSettings,
                                border = BorderStroke(1.dp, BumbleGold)
                            ) {
                                Text("Settings", color = BumbleGoldBright)
                            }
                        }
                    }
                }
            }

            // API Key Setup Prompt if unconfigured
            if (!hasApiKey && coreState != CoreState.ERROR) {
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = BumbleGoldBright)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Gemini API Key Required",
                                style = MaterialTheme.typography.titleMedium,
                                color = BumbleGoldBright
                            )
                            Text(
                                text = "Enter your Gemini API key in Settings. It is encrypted locally using Android Keystore AES-256.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BumbleTextSecondary
                            )
                        }
                    }
                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                        modifier = Modifier.fillMaxWidth().testTag("setup_api_key_button")
                    ) {
                        Text("Configure API Key")
                    }
                }
            }

            // Primary Voice, Mute, and Vision Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isLiveActive = coreState == CoreState.LISTENING ||
                    coreState == CoreState.CONNECTING ||
                    coreState == CoreState.SPEAKING

                Button(
                    onClick = onTapCoreVoice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (coreState == CoreState.LISTENING) BumbleCrimson else BumbleGold,
                        contentColor = if (coreState == CoreState.LISTENING) MaterialTheme.colorScheme.onError else BumbleVoidBlack
                    ),
                    modifier = Modifier.weight(1f).height(52.dp).testTag("talk_bumble_button")
                ) {
                    Icon(
                        imageVector = if (isLiveActive) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when (coreState) {
                            CoreState.CONNECTING -> "Connecting..."
                            CoreState.LISTENING -> "End Live"
                            CoreState.SPEAKING -> "Stop Speaking"
                            CoreState.PROCESSING -> "Processing..."
                            CoreState.ERROR -> "Retry Live"
                            CoreState.IDLE -> "Start Live"
                        }
                    )
                }

                if (coreState == CoreState.LISTENING || coreState == CoreState.SPEAKING) {
                    FilledTonalIconButton(
                        onClick = onToggleMicMute,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isMicMuted) BumbleCrimson.copy(alpha = 0.25f) else BumbleGraphiteElevated
                        ),
                        modifier = Modifier.size(52.dp).testTag("dashboard_mic_mute_button")
                    ) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMicMuted) "Unmute Microphone" else "Mute Microphone",
                            tint = if (isMicMuted) BumbleCrimson else BumbleGoldBright
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = onToggleSpeakerMute,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSpeakerMuted) BumbleCrimson.copy(alpha = 0.25f) else BumbleGraphiteElevated
                    ),
                    modifier = Modifier.size(52.dp).testTag("dashboard_speaker_mute_button")
                ) {
                    Icon(
                        imageVector = if (isSpeakerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isSpeakerMuted) "Unmute Speaker" else "Mute Speaker",
                        tint = if (isSpeakerMuted) BumbleCrimson else BumbleGoldBright
                    )
                }

                OutlinedButton(
                    onClick = onNavigateToVision,
                    border = BorderStroke(1.dp, BumbleGold.copy(alpha = 0.6f)),
                    modifier = Modifier.height(52.dp).testTag("quick_vision_button")
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Vision", tint = BumbleGoldBright)
                }
            }

            // Latest Response / Transcript Snippet
            if (latestEntry != null) {
                HudPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = latestEntry.role.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = BumbleGold
                    )
                    Text(
                        text = latestEntry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4
                    )
                }
            }

            // Direct Text Command Input
            OutlinedTextField(
                value = quickInput,
                onValueChange = { quickInput = it },
                placeholder = { Text("Send text to Gemini Live (EN / UR / HI)...") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BumbleGold,
                    unfocusedBorderColor = BumbleHudBorder,
                    focusedContainerColor = BumbleGraphite,
                    unfocusedContainerColor = BumbleGraphite
                ),
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (quickInput.isNotBlank()) {
                                val cmd = quickInput
                                quickInput = ""
                                onSendQuickCommand(cmd)
                            }
                        },
                        modifier = Modifier.testTag("quick_send_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send command", tint = BumbleGold)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (quickInput.isNotBlank()) {
                        val cmd = quickInput
                        quickInput = ""
                        onSendQuickCommand(cmd)
                    }
                }),
                modifier = Modifier.fillMaxWidth().testTag("quick_command_input")
            )

            // Quick Module Navigation Grid
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val chipColors = AssistChipDefaults.assistChipColors(
                    containerColor = BumbleGraphite,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    leadingIconContentColor = BumbleGold
                )
                AssistChip(
                    onClick = onNavigateToChat,
                    label = { Text("Live Chat") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = chipColors
                )
                AssistChip(
                    onClick = onNavigateToTools,
                    label = { Text("AI Tools") },
                    leadingIcon = { Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = chipColors
                )
                AssistChip(
                    onClick = onNavigateToMemory,
                    label = { Text("Memory") },
                    leadingIcon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = chipColors
                )
                AssistChip(
                    onClick = onNavigateToHistory,
                    label = { Text("History") },
                    leadingIcon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    colors = chipColors
                )
            }
        }
    }
}
