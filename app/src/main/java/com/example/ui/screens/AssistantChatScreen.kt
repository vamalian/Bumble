package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ai.CoreState
import com.example.ai.LiveDiagnostics
import com.example.data.local.BumbleSettings
import com.example.ui.ChatEntry
import com.example.ui.components.HolographicCore
import com.example.ui.components.HudTelemetryPill
import com.example.ui.theme.BumbleAmber
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleGoldMuted
import com.example.ui.theme.BumbleGraphite
import com.example.ui.theme.BumbleGraphiteElevated
import com.example.ui.theme.BumbleHudBorder
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack

@Composable
fun AssistantChatScreen(
    coreState: CoreState,
    liveDiagnostics: LiveDiagnostics,
    statusBanner: String,
    liveInputTranscript: String,
    liveOutputTranscript: String,
    audioAmplitude: Float,
    settings: BumbleSettings,
    messages: List<ChatEntry>,
    isProcessing: Boolean,
    isMicMuted: Boolean,
    isSpeakerMuted: Boolean,
    onTapVoice: () -> Unit,
    onRetryConnection: () -> Unit,
    onStopSpeaking: () -> Unit,
    onToggleMicMute: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onNewChat: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var messageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, liveInputTranscript, liveOutputTranscript) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Compact Reactive Holographic Core Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HolographicCore(
                    coreState = coreState,
                    audioAmplitude = audioAmplitude,
                    animationsEnabled = settings.animationsEnabled,
                    particleDensity = settings.particleDensity,
                    onClick = onTapVoice,
                    modifier = Modifier.size(84.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "BUMBLE GEMINI LIVE",
                        style = MaterialTheme.typography.titleMedium,
                        color = BumbleGoldBright
                    )
                    HudTelemetryPill(
                        label = "${coreState.name} • ${statusBanner.uppercase()}",
                        dotColor = when (coreState) {
                            CoreState.LISTENING, CoreState.SPEAKING -> BumbleEmerald
                            CoreState.CONNECTING -> BumbleAmber
                            CoreState.ERROR -> BumbleCrimson
                            CoreState.PROCESSING, CoreState.IDLE -> BumbleGold
                        }
                    )
                    Text(
                        text = "${settings.liveModel} • Voice: ${settings.liveVoice} • ${liveDiagnostics.micStatus.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BumbleTextSecondary
                    )
                }
                IconButton(
                    onClick = onNewChat,
                    modifier = Modifier.testTag("new_chat_button")
                ) {
                    Icon(Icons.Default.AddComment, contentDescription = "New session", tint = BumbleGold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Conversation Feed
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (messages.isEmpty() && liveInputTranscript.isBlank() && liveOutputTranscript.isBlank()) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = BumbleGraphite.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, BumbleHudBorder),
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Bumble Gemini Live Assistant",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = BumbleGoldBright
                                )
                                Text(
                                    text = "Tap Start Live or the Golden Core for real-time spoken bidirectional conversation in English, Urdu, or Hindi. Bumble streams 16 kHz voice input, speaks aloud at 24 kHz PCM, and executes real Android actions.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = BumbleTextSecondary
                                )
                            }
                        }
                    }
                }
                items(messages, key = { it.id }) { entry ->
                    ChatBubble(entry)
                }
                if (liveInputTranscript.isNotBlank()) {
                    item {
                        ChatBubble(
                            ChatEntry(role = "user", text = "$liveInputTranscript ...")
                        )
                    }
                }
                if (liveOutputTranscript.isNotBlank()) {
                    item {
                        ChatBubble(
                            ChatEntry(role = "assistant", text = "$liveOutputTranscript ...")
                        )
                    }
                }
                if (isProcessing || coreState == CoreState.CONNECTING) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = BumbleGold, strokeWidth = 2.dp)
                            Text(
                                text = if (coreState == CoreState.CONNECTING) "Connecting Gemini Live..." else "Bumble is processing...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BumbleGoldBright
                            )
                        }
                    }
                }
            }

            // Voice & Live Controls Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTapVoice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (coreState == CoreState.LISTENING) BumbleCrimson else BumbleGold,
                        contentColor = if (coreState == CoreState.LISTENING) MaterialTheme.colorScheme.onError else BumbleVoidBlack
                    ),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("chat_voice_button")
                ) {
                    Icon(
                        imageVector = if (coreState == CoreState.LISTENING) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when (coreState) {
                            CoreState.CONNECTING -> "Connecting..."
                            CoreState.LISTENING -> "End Live"
                            CoreState.SPEAKING -> "Stop Speaking"
                            CoreState.ERROR -> "Retry Live"
                            CoreState.PROCESSING -> "Processing..."
                            CoreState.IDLE -> "Start Live"
                        }
                    )
                }

                if (coreState == CoreState.ERROR) {
                    FilledTonalIconButton(
                        onClick = onRetryConnection,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = BumbleGold.copy(alpha = 0.25f)),
                        modifier = Modifier.size(48.dp).testTag("chat_retry_live_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry Live session", tint = BumbleGoldBright)
                    }
                }

                if (coreState == CoreState.LISTENING || coreState == CoreState.SPEAKING) {
                    FilledTonalIconButton(
                        onClick = onToggleMicMute,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isMicMuted) BumbleCrimson.copy(alpha = 0.25f) else BumbleGraphiteElevated
                        ),
                        modifier = Modifier.size(48.dp).testTag("chat_mic_mute_button")
                    ) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMicMuted) "Unmute microphone" else "Mute microphone",
                            tint = if (isMicMuted) BumbleCrimson else BumbleGoldBright
                        )
                    }
                }

                if (coreState == CoreState.SPEAKING) {
                    FilledTonalIconButton(
                        onClick = onStopSpeaking,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = BumbleCrimson.copy(alpha = 0.25f)),
                        modifier = Modifier.size(48.dp).testTag("chat_stop_tts_button")
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Interrupt speech", tint = BumbleCrimson)
                    }
                }

                FilledTonalIconButton(
                    onClick = onToggleSpeakerMute,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isSpeakerMuted) BumbleCrimson.copy(alpha = 0.25f) else BumbleGraphiteElevated
                    ),
                    modifier = Modifier.size(48.dp).testTag("chat_mute_tts_button")
                ) {
                    Icon(
                        imageVector = if (isSpeakerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isSpeakerMuted) "Unmute speaker" else "Mute speaker",
                        tint = if (isSpeakerMuted) BumbleCrimson else BumbleGoldBright
                    )
                }
            }

            // Text Input Bar
            OutlinedTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                placeholder = { Text("Send message to Gemini Live...") },
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
                            if (messageInput.isNotBlank()) {
                                val msg = messageInput
                                messageInput = ""
                                onSendText(msg)
                            }
                        },
                        modifier = Modifier.testTag("chat_send_text_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send text", tint = BumbleGold)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (messageInput.isNotBlank()) {
                        val msg = messageInput
                        messageInput = ""
                        onSendText(msg)
                    }
                }),
                modifier = Modifier.fillMaxWidth().testTag("chat_text_input")
            )
        }
    }
}

@Composable
private fun ChatBubble(entry: ChatEntry) {
    val isUser = entry.role == "user"
    val isTool = entry.role == "tool"
    val isSystem = entry.role == "system"

    val bg = when {
        isUser -> BumbleGoldMuted.copy(alpha = 0.25f)
        isTool -> BumbleEmerald.copy(alpha = 0.14f)
        isSystem -> BumbleCrimson.copy(alpha = 0.15f)
        else -> BumbleGraphiteElevated
    }
    val border = when {
        isUser -> BumbleGold.copy(alpha = 0.45f)
        isTool -> BumbleEmerald.copy(alpha = 0.4f)
        isSystem -> BumbleCrimson.copy(alpha = 0.4f)
        else -> BumbleHudBorder
    }
    val headerLabel = when {
        isUser -> "YOU"
        isTool -> "ACTION // ${entry.toolName ?: "TOOL"}"
        isSystem -> "SYSTEM"
        else -> "BUMBLE"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = bg,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.widthIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isUser -> BumbleGoldBright
                        isTool -> BumbleEmerald
                        isSystem -> BumbleCrimson
                        else -> BumbleGold
                    }
                )
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
