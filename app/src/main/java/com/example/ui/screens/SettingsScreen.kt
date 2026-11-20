package com.example.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ai.ConnectionTestResult
import com.example.data.local.BumbleSettings
import com.example.ui.components.HudPanel
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: BumbleSettings,
    hasApiKey: Boolean,
    maskedApiKey: String,
    isTestingConnection: Boolean,
    connectionTestResult: ConnectionTestResult?,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onTestConnection: (String?) -> Unit,
    onUpdateSettings: ((BumbleSettings) -> BumbleSettings) -> Unit,
    onClearHistory: () -> Unit,
    onClearMemories: () -> Unit,
    onNavigateToPrivacy: () -> Unit,
    modifier: Modifier = Modifier
) {
    var apiKeyDraft by remember { mutableStateOf("") }
    var showKeyPlaintext by remember { mutableStateOf(false) }
    var customLiveModel by remember(settings.liveModel) { mutableStateOf(settings.liveModel) }
    var customPromptDraft by remember(settings.customSystemPrompt) { mutableStateOf(settings.customSystemPrompt) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Settings & Gemini API", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)

            // 1. Secure API Key Configuration
            HudPanel(highlightBorder = !hasApiKey, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, tint = BumbleGold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("GEMINI API KEY (ANDROID KEYSTORE AES-256)", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                }

                Text(
                    text = if (hasApiKey) "Stored encrypted key: $maskedApiKey" else "No key stored yet. Keys are encrypted on-device and never logged.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (hasApiKey) BumbleEmerald else BumbleTextSecondary
                )

                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    label = { Text(if (hasApiKey) "Replace Gemini API Key" else "Enter Gemini API Key") },
                    singleLine = true,
                    visualTransformation = if (showKeyPlaintext) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showKeyPlaintext = !showKeyPlaintext }) {
                            Icon(
                                imageVector = if (showKeyPlaintext) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle API key visibility"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("api_key_input")
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (apiKeyDraft.isNotBlank()) {
                                onSaveApiKey(apiKeyDraft)
                                apiKeyDraft = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                        modifier = Modifier.testTag("save_api_key_button")
                    ) {
                        Text("Save Encrypted Key")
                    }

                    OutlinedButton(
                        onClick = { onTestConnection(apiKeyDraft.takeIf { it.isNotBlank() }) },
                        enabled = !isTestingConnection,
                        border = BorderStroke(1.dp, BumbleGold),
                        modifier = Modifier.testTag("test_api_connection_button")
                    ) {
                        if (isTestingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BumbleGold, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = BumbleGoldBright)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Test API Connection", color = BumbleGoldBright)
                    }

                    if (hasApiKey) {
                        OutlinedButton(
                            onClick = onClearApiKey,
                            border = BorderStroke(1.dp, BumbleCrimson.copy(alpha = 0.6f))
                        ) {
                            Text("Remove Key", color = BumbleCrimson)
                        }
                    }
                }

                connectionTestResult?.let { result ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = if (result.success) BumbleEmerald else BumbleCrimson
                        )
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (result.success) BumbleEmerald else BumbleCrimson
                        )
                    }
                }
            }

            // 2. Gemini Models
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("GEMINI LIVE & VISION MODELS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                Text("Live Audio Model", style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BumbleSettings.LIVE_MODEL_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = settings.liveModel == m,
                            onClick = { onUpdateSettings { it.copy(liveModel = m) } },
                            label = { Text(m.removePrefix("models/"), style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }
                OutlinedTextField(
                    value = customLiveModel,
                    onValueChange = {
                        customLiveModel = it
                        onUpdateSettings { s -> s.copy(liveModel = it) }
                    },
                    label = { Text("Custom Live Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Vision & Tools Model", style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BumbleSettings.VISION_MODEL_OPTIONS.forEach { m ->
                        FilterChip(
                            selected = settings.visionModel == m,
                            onClick = { onUpdateSettings { it.copy(visionModel = m) } },
                            label = { Text(m, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }
            }

            // 3. Voice, Language & Personality
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("VOICE, LANGUAGE & PERSONALITY", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                Text("Gemini Live Voice", style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BumbleSettings.VOICE_OPTIONS.forEach { v ->
                        FilterChip(
                            selected = settings.voiceName == v,
                            onClick = { onUpdateSettings { it.copy(voiceName = v) } },
                            label = { Text(v) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }

                Text("Language Mode", style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BumbleSettings.LANGUAGE_OPTIONS.forEach { lang ->
                        FilterChip(
                            selected = settings.languageMode == lang,
                            onClick = { onUpdateSettings { it.copy(languageMode = lang) } },
                            label = { Text(lang) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }

                Text("AI Personality", style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BumbleSettings.PERSONALITY_OPTIONS.forEach { p ->
                        FilterChip(
                            selected = settings.personality == p,
                            onClick = { onUpdateSettings { it.copy(personality = p) } },
                            label = { Text(p) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                        )
                    }
                }

                if (settings.personality == "Custom") {
                    OutlinedTextField(
                        value = customPromptDraft,
                        onValueChange = {
                            customPromptDraft = it
                            onUpdateSettings { s -> s.copy(customSystemPrompt = it) }
                        },
                        label = { Text("Custom System Personality Prompt") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 4. Wake-Word & Holographic Animations
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("WAKE-WORD & CORE ANIMATIONS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                SettingToggleRow(
                    title = "Foreground \"Hey Bumble\" Wake Word",
                    subtitle = "Only active while app is open on screen. Stops immediately on pause/exit.",
                    checked = settings.wakeWordEnabled,
                    onCheckedChange = { enabled -> onUpdateSettings { it.copy(wakeWordEnabled = enabled) } }
                )
                SettingToggleRow(
                    title = "Holographic Core Animations",
                    subtitle = "Rotate energy rings and pulse core aura.",
                    checked = settings.animationsEnabled,
                    onCheckedChange = { enabled -> onUpdateSettings { it.copy(animationsEnabled = enabled) } }
                )
                Text(
                    "Particle Density: ${(settings.particleDensity * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = settings.particleDensity,
                    onValueChange = { v -> onUpdateSettings { it.copy(particleDensity = v) } },
                    valueRange = 0f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = BumbleGoldBright, activeTrackColor = BumbleGold)
                )
            }

            // 5. History, Memory & Privacy Controls
            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Text("HISTORY, MEMORY & PRIVACY", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
                SettingToggleRow(
                    title = "Save Conversation History",
                    subtitle = "Persist session transcripts locally in encrypted app database.",
                    checked = settings.saveConversationHistory,
                    onCheckedChange = { enabled -> onUpdateSettings { it.copy(saveConversationHistory = enabled) } }
                )
                SettingToggleRow(
                    title = "Allow AI Memory Suggestions",
                    subtitle = "Require your explicit approval before adding suggested facts.",
                    checked = settings.allowMemorySuggestions,
                    onCheckedChange = { enabled -> onUpdateSettings { it.copy(allowMemorySuggestions = enabled) } }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClearHistory) {
                        Text("Clear History", color = BumbleCrimson)
                    }
                    OutlinedButton(onClick = onClearMemories) {
                        Text("Clear Memories", color = BumbleCrimson)
                    }
                }
                Button(
                    onClick = onNavigateToPrivacy,
                    colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Permissions & Privacy Dashboard")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BumbleVoidBlack,
                checkedTrackColor = BumbleGold
            )
        )
    }
}
