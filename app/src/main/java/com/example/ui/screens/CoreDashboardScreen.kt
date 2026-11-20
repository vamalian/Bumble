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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.example.ai.WakeWordStatus
import com.example.data.local.BumbleSettings
import com.example.ui.ChatEntry
import com.example.ui.components.HolographicCore
import com.example.ui.components.HudPanel
import com.example.ui.components.HudTelemetryPill
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleGraphite
import com.example.ui.theme.BumbleHudBorder
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoreDashboardScreen(
    coreState: CoreState,
    wakeWordStatus: WakeWordStatus,
    statusBanner: String,
    partialVoiceText: String,
    audioAmplitude: Float,
    settings: BumbleSettings,
    hasApiKey: Boolean,
    hasMicPermission: Boolean,
    latestEntry: ChatEntry?,
    onTapCoreVoice: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                        text = "${settings.geminiModel.uppercase()} • ${settings.languageMode.substringBefore(" ")}",
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

            // Telemetry Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dotColor = when (coreState) {
                    CoreState.ERROR -> BumbleCrimson
                    CoreState.LISTENING, CoreState.SPEAKING -> BumbleEmerald
                    else -> BumbleGold
                }
                HudTelemetryPill(
                    label = statusBanner.uppercase(),
                    dotColor = dotColor
                )
            }

            // Central Golden Holographic Core
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp),
                contentAlignment = Alignment.Center
            ) {
                HolographicCore(
                    coreState = coreState,
                    audioAmplitude = audioAmplitude,
                    animationsEnabled = settings.animationsEnabled,
                    particleDensity = settings.particleDensity,
                    onClick = onTapCoreVoice,
                    modifier = Modifier.size(280.dp)
                )
            }

            if (partialVoiceText.isNotBlank()) {
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Text("HEARING", style = MaterialTheme.typography.labelSmall, color = BumbleGold)
                    Text(partialVoiceText, style = MaterialTheme.typography.bodyLarge, color = BumbleGoldBright)
                }
            }

            val wakeLabel = when (wakeWordStatus) {
                WakeWordStatus.STANDBY_LISTENING -> "WAKE PHRASE ACTIVE (FOREGROUND): \"HEY BUMBLE\""
                WakeWordStatus.TRIGGERED -> "WAKE PHRASE DETECTED — LISTENING"
                WakeWordStatus.UNAVAILABLE -> "TAP CORE TO SPEAK WITH GEMINI"
                WakeWordStatus.SUSPENDED_BACKGROUND -> "WAKE LISTENER SUSPENDED"
                WakeWordStatus.DISABLED -> if (!hasMicPermission) "GRANT MIC PERMISSION TO ENABLE VOICE & WAKE WORD" else "TAP CORE TO SPEAK TO BUMBLE"
            }
            Text(
                text = wakeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = BumbleTextSecondary,
                textAlign = TextAlign.Center
            )

            // API Key Setup Prompt if unconfigured
            if (!hasApiKey) {
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

            // Primary Voice & Vision Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onTapCoreVoice,
                    colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                    modifier = Modifier.weight(1f).height(52.dp).testTag("talk_bumble_button")
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        when (coreState) {
                            CoreState.LISTENING -> "Stop Listening"
                            CoreState.SPEAKING -> "Stop Speaking"
                            else -> "Speak to Bumble"
                        }
                    )
                }

                OutlinedButton(
                    onClick = onNavigateToVision,
                    border = BorderStroke(1.dp, BumbleGold.copy(alpha = 0.6f)),
                    modifier = Modifier.height(52.dp).testTag("quick_vision_button")
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Vision", tint = BumbleGoldBright)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Vision", color = BumbleGoldBright)
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
                placeholder = { Text("Ask Gemini in English, Urdu, or Hindi...") },
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
                    label = { Text("Gemini Chat") },
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
