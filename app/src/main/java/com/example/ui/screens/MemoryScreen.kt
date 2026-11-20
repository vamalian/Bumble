package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.data.local.MemoryEntity
import com.example.ui.components.HudPanel
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryScreen(
    memories: List<MemoryEntity>,
    onAddMemory: (String, String) -> Unit,
    onApproveMemory: (Long) -> Unit,
    onDeleteMemory: (Long) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Preference") }
    val categories = listOf("Preference", "Fact", "Routine", "Note")

    val pending = remember(memories) { memories.filter { !it.isApproved } }
    val approved = remember(memories) { memories.filter { it.isApproved } }

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
                Text("Personal Memory", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)
                if (memories.isNotEmpty()) {
                    OutlinedButton(
                        onClick = onClearAll,
                        border = BorderStroke(1.dp, BumbleCrimson.copy(alpha = 0.6f))
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = BumbleCrimson)
                        Text(" Clear", color = BumbleCrimson)
                    }
                }
            }
            Text(
                text = "Only user-approved memories are injected into Gemini context. All storage remains strictly on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = BumbleTextSecondary
            )

            Spacer(modifier = Modifier.height(10.dp))

            HudPanel(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Add a fact or preference (e.g., 'I prefer Urdu and English replies')") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("memory_input")
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BumbleGold,
                                selectedLabelColor = BumbleVoidBlack
                            )
                        )
                    }
                }
                Button(
                    onClick = {
                        onAddMemory(inputText, selectedCategory)
                        inputText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                    modifier = Modifier.testTag("add_memory_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Approved Memory")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (pending.isNotEmpty()) {
                    item {
                        Text(
                            text = "PENDING APPROVAL (${pending.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = BumbleGoldBright
                        )
                    }
                    items(pending, key = { it.id }) { mem ->
                        HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                            Text(mem.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = BumbleGold)
                            Text(mem.content, style = MaterialTheme.typography.bodyLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = { onApproveMemory(mem.id) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BumbleEmerald, contentColor = BumbleVoidBlack)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Approve")
                                }
                                OutlinedButton(
                                    onClick = { onDeleteMemory(mem.id) },
                                    border = BorderStroke(1.dp, BumbleCrimson.copy(alpha = 0.6f))
                                ) {
                                    Text("Dismiss", color = BumbleCrimson)
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "APPROVED MEMORIES (${approved.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = BumbleGold
                    )
                }

                if (approved.isEmpty()) {
                    item {
                        HudPanel(modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = BumbleGold)
                            Text("No Approved Memories Yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Add preferences or ask Bumble to remember something—you always control what gets saved.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = BumbleTextSecondary
                            )
                        }
                    }
                } else {
                    items(approved, key = { it.id }) { mem ->
                        HudPanel(modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mem.category.uppercase(), style = MaterialTheme.typography.labelSmall, color = BumbleGold)
                                    Text(mem.content, style = MaterialTheme.typography.bodyLarge)
                                }
                                IconButton(onClick = { onDeleteMemory(mem.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete memory", tint = BumbleCrimson)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
