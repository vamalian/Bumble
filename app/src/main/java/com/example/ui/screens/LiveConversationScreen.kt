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
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.example.ai.LiveConnectionState
import com.example.data.local.BumbleSettings
import com.example.ui.LiveTranscriptEntry
import com.example.ui.components.HolographicCore
import com.example.ui.components.HudTelemetryPill
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
fun LiveConversationScreen(
    coreState: CoreState,
    connectionState: LiveConnectionState,
    statusBanner: String,
    audioAmplitude: Float,
    settings: BumbleSettings,
    transcript: List<LiveTranscriptEntry>,
    isMicPaused: Boolean,
    isSpeakerMuted: Boolean,
    onStartSession: () -> Unit,
    onEndSession: () -> Unit,
    onToggleMicPause: () -> Unit,
    onToggleSpeakerMute: () -> Unit,
    onInterrupt: () -> Unit,
    onSendText: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var messageInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.lastIndex)
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
                    onClick = {
                        if (coreState == CoreState.SPEAKING) onInterrupt()
                        else if (connectionState == LiveConnectionState.DISCONNECTED) onStartSession()
                    },
                    modifier = Modifier.size(88.dp)
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "GEMINI LIVE VOICE",
                        style = MaterialTheme.typography.titleMedium,
                        color = BumbleGoldBright
                    )
                    HudTelemetryPill(
                        label = statusBanner.uppercase(),
                        dotColor = when (connectionState) {
                            LiveConnectionState.CONNECTED -> BumbleEmerald
                            LiveConnectionState.ERROR -> BumbleCrimson
                            else -> BumbleGold
                        }
                    )
                    Text(
                        text = "${settings.liveModel.removePrefix("models/")} • ${settings.voiceName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = BumbleTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Real-time Transcript Stream
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (transcript.isEmpty()) {
                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = BumbleGraphite.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, BumbleHudBorder),
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Real-Time Bidirectional Audio",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = BumbleGoldBright
                                )
                                Text(
                                    text = "Tap Connect Live below to stream voice directly to Gemini Live. You can interrupt Bumble mid-sentence, execute Android actions by voice, or type commands below.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = BumbleTextSecondary
                                )
                            }
                        }
                    }
                }
                items(transcript, key = { it.id }) { entry ->
                    TranscriptBubble(entry)
                }
            }

            // Voice Controls Bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (connectionState == LiveConnectionState.DISCONNECTED || connectionState == LiveConnectionState.ERROR) {
                    Button(
                        onClick = onStartSession,
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                        modifier = Modifier.weight(1f).height(50.dp).testTag("live_connect_button")
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start Gemini Live")
                    }
                } else {
                    FilledTonalIconButton(
                        onClick = onToggleMicPause,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isMicPaused) BumbleCrimson.copy(alpha = 0.25f) else BumbleGraphiteElevated
                        ),
                        modifier = Modifier.size(50.dp).testTag("live_pause_mic_button")
                    ) {
                        Icon(
                            imageVector = if (isMicPaused) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = if (isMicPaused) "Resume Microphone" else "Pause Microphone",
                            tint = if (isMicPaused) BumbleCrimson else BumbleGoldBright
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onToggleSpeakerMute,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = if (isSpeakerMuted) BumbleCrimson.copy(alpha = 0.25f) else BumbleGraphiteElevated
                        ),
                        modifier = Modifier.size(50.dp).testTag("live_mute_speaker_button")
                    ) {
                        Icon(
                            imageVector = if (isSpeakerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = if (isSpeakerMuted) "Unmute Audio" else "Mute Audio",
                            tint = if (isSpeakerMuted) BumbleCrimson else BumbleGoldBright
                        )
                    }

                    FilledTonalIconButton(
                        onClick = onInterrupt,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = BumbleGraphiteElevated),
                        modifier = Modifier.size(50.dp).testTag("live_interrupt_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PanTool,
                            contentDescription = "Interrupt Bumble",
                            tint = BumbleGold
                        )
                    }

                    Button(
                        onClick = onEndSession,
                        colors = ButtonDefaults.buttonColors(containerColor = BumbleCrimson, contentColor = MaterialTheme.colorScheme.onError),
                        modifier = Modifier.height(50.dp).testTag("live_end_button")
                    ) {
                        Icon(Icons.Default.CallEnd, contentDescription = "End Conversation")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("End")
                    }
                }
            }

            // Text / Command Fallback Bar inside Live Screen
            OutlinedTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                placeholder = { Text("Type message into Live stream...") },
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
                        modifier = Modifier.testTag("live_send_text_button")
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
                modifier = Modifier.fillMaxWidth().testTag("live_text_input")
            )
        }
    }
}

@Composable
private fun TranscriptBubble(entry: LiveTranscriptEntry) {
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
