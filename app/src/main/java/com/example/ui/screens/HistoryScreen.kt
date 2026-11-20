package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.local.ConversationMessageEntity
import com.example.data.local.ConversationSessionEntity
import com.example.ui.components.HudPanel
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleGraphite
import com.example.ui.theme.BumbleHudBorder
import com.example.ui.theme.BumbleTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    sessions: List<ConversationSessionEntity>,
    selectedMessages: List<ConversationMessageEntity>,
    onSelectSession: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeSessionId by remember { mutableStateOf<Long?>(null) }
    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    BackHandler(enabled = activeSessionId != null) {
        activeSessionId = null
    }

    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 720.dp).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (activeSessionId != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { activeSessionId = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to sessions", tint = BumbleGold)
                        }
                        Text("Session Transcript", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)
                    }
                } else {
                    Text("Conversation History", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)
                    if (sessions.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearAll,
                            border = BorderStroke(1.dp, BumbleCrimson.copy(alpha = 0.6f)),
                            modifier = Modifier.testTag("clear_all_history_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = BumbleCrimson)
                            Text(" Clear All", color = BumbleCrimson)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (activeSessionId == null) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search sessions...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BumbleGold) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BumbleGold,
                        unfocusedBorderColor = BumbleHudBorder,
                        focusedContainerColor = BumbleGraphite,
                        unfocusedContainerColor = BumbleGraphite
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("history_search_input")
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (filtered.isEmpty()) {
                    HudPanel(modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.History, contentDescription = null, tint = BumbleGold)
                        Text("No Saved Sessions", style = MaterialTheme.typography.titleMedium, color = BumbleGoldBright)
                        Text(
                            "Your Gemini conversations appear here automatically when history saving is enabled in Settings.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = BumbleTextSecondary
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { session ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = BumbleGraphite,
                                border = BorderStroke(1.dp, BumbleHudBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        activeSessionId = session.id
                                        onSelectSession(session.id)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = session.title,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "${dateFmt.format(Date(session.updatedAt))} • ${session.languageMode}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BumbleTextSecondary
                                        )
                                    }
                                    IconButton(onClick = { onDeleteSession(session.id) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete session", tint = BumbleCrimson)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedMessages, key = { it.id }) { msg ->
                        HudPanel(
                            highlightBorder = msg.role == "user",
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = msg.role.uppercase() + (msg.toolName?.let { " • $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BumbleGold
                                )
                                Text(
                                    text = dateFmt.format(Date(msg.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BumbleTextSecondary
                                )
                            }
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}
