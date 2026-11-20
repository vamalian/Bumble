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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.assistant.InstalledAppInfo
import com.example.data.local.NoteEntity
import com.example.data.local.ReminderEntity
import com.example.ui.ToolsUiState
import com.example.ui.components.HudPanel
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleGraphite
import com.example.ui.theme.BumbleHudBorder
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolsDashboardScreen(
    toolsState: ToolsUiState,
    notes: List<NoteEntity>,
    reminders: List<ReminderEntity>,
    installedApps: List<InstalledAppInfo>,
    onOpenSystemSetting: (String) -> Unit,
    onAdjustVolume: (Int) -> Unit,
    onAdjustBrightness: (Int) -> Unit,
    onLaunchCamera: () -> Unit,
    onSetAlarm: (Int, Int) -> Unit,
    onLaunchApp: (InstalledAppInfo) -> Unit,
    onAddNote: (String, String) -> Unit,
    onTogglePinNote: (NoteEntity) -> Unit,
    onDeleteNote: (NoteEntity) -> Unit,
    onAddReminder: (String, Int) -> Unit,
    onToggleReminder: (ReminderEntity) -> Unit,
    onDeleteReminder: (Long) -> Unit,
    onFetchWeather: (String) -> Unit,
    onSearchWeb: (String) -> Unit,
    onEvaluateMath: (String) -> Unit,
    onTranslate: (String, String) -> Unit,
    onGeneratePlan: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Device & Apps", "Notes & Tasks", "Weather & Web", "AI Utilities")

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.widthIn(max = 760.dp).fillMaxSize()) {
            Text(
                text = "AI Tools & Device Control",
                style = MaterialTheme.typography.headlineMedium,
                color = BumbleGoldBright
            )
            Spacer(modifier = Modifier.height(8.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = BumbleGraphite,
                contentColor = BumbleGoldBright,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = BumbleGold
                    )
                }
            ) {
                tabs.forEachIndexed { idx, label ->
                    Tab(
                        selected = selectedTab == idx,
                        onClick = { selectedTab = idx },
                        text = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            if (!toolsState.actionFeedback.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                    Text(toolsState.actionFeedback, style = MaterialTheme.typography.bodyMedium, color = BumbleGoldBright)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when (selectedTab) {
                    0 -> DeviceAndAppsTab(
                        installedApps = installedApps,
                        onOpenSystemSetting = onOpenSystemSetting,
                        onAdjustVolume = onAdjustVolume,
                        onAdjustBrightness = onAdjustBrightness,
                        onLaunchCamera = onLaunchCamera,
                        onSetAlarm = onSetAlarm,
                        onLaunchApp = onLaunchApp
                    )
                    1 -> NotesAndRemindersTab(
                        notes = notes,
                        reminders = reminders,
                        onAddNote = onAddNote,
                        onTogglePinNote = onTogglePinNote,
                        onDeleteNote = onDeleteNote,
                        onAddReminder = onAddReminder,
                        onToggleReminder = onToggleReminder,
                        onDeleteReminder = onDeleteReminder
                    )
                    2 -> WeatherAndWebTab(
                        toolsState = toolsState,
                        onFetchWeather = onFetchWeather,
                        onSearchWeb = onSearchWeb
                    )
                    3 -> AiUtilitiesTab(
                        toolsState = toolsState,
                        onEvaluateMath = onEvaluateMath,
                        onTranslate = onTranslate,
                        onGeneratePlan = onGeneratePlan
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceAndAppsTab(
    installedApps: List<InstalledAppInfo>,
    onOpenSystemSetting: (String) -> Unit,
    onAdjustVolume: (Int) -> Unit,
    onAdjustBrightness: (Int) -> Unit,
    onLaunchCamera: () -> Unit,
    onSetAlarm: (Int, Int) -> Unit,
    onLaunchApp: (InstalledAppInfo) -> Unit
) {
    var volumePct by remember { mutableFloatStateOf(60f) }
    var brightnessPct by remember { mutableFloatStateOf(70f) }
    var appFilter by remember { mutableStateOf("") }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Text("ANDROID SYSTEM CONTROLS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onOpenSystemSetting("wifi") },
                label = { Text("Wi-Fi Settings") },
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(containerColor = BumbleGraphite, leadingIconContentColor = BumbleGold)
            )
            AssistChip(
                onClick = { onOpenSystemSetting("bluetooth") },
                label = { Text("Bluetooth") },
                leadingIcon = { Icon(Icons.Default.Bluetooth, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(containerColor = BumbleGraphite, leadingIconContentColor = BumbleGold)
            )
            AssistChip(
                onClick = onLaunchCamera,
                label = { Text("Launch Camera") },
                leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(containerColor = BumbleGraphite, leadingIconContentColor = BumbleGold)
            )
            AssistChip(
                onClick = { onSetAlarm(7, 30) },
                label = { Text("Alarm 07:30") },
                leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                colors = AssistChipDefaults.assistChipColors(containerColor = BumbleGraphite, leadingIconContentColor = BumbleGold)
            )
        }

        Text("Media Volume: ${volumePct.toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = volumePct,
            onValueChange = { volumePct = it },
            onValueChangeFinished = { onAdjustVolume(volumePct.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = BumbleGoldBright, activeTrackColor = BumbleGold)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Brightness6, contentDescription = null, tint = BumbleGold)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Brightness: ${brightnessPct.toInt()}% (requires Write Settings)", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = brightnessPct,
            onValueChange = { brightnessPct = it },
            onValueChangeFinished = { onAdjustBrightness(brightnessPct.toInt()) },
            valueRange = 5f..100f,
            colors = SliderDefaults.colors(thumbColor = BumbleGoldBright, activeTrackColor = BumbleGold)
        )
    }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Apps, contentDescription = null, tint = BumbleGold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("INSTALLED APPS (${installedApps.size})", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        }
        OutlinedTextField(
            value = appFilter,
            onValueChange = { appFilter = it },
            placeholder = { Text("Filter apps to launch...") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = BumbleGold, unfocusedBorderColor = BumbleHudBorder),
            modifier = Modifier.fillMaxWidth()
        )
        val visibleApps = remember(installedApps, appFilter) {
            installedApps.filter { appFilter.isBlank() || it.label.contains(appFilter, ignoreCase = true) }.take(18)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visibleApps.forEach { app ->
                AssistChip(
                    onClick = { onLaunchApp(app) },
                    label = { Text(app.label) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = BumbleGraphite)
                )
            }
        }
    }
}

@Composable
private fun NotesAndRemindersTab(
    notes: List<NoteEntity>,
    reminders: List<ReminderEntity>,
    onAddNote: (String, String) -> Unit,
    onTogglePinNote: (NoteEntity) -> Unit,
    onDeleteNote: (NoteEntity) -> Unit,
    onAddReminder: (String, Int) -> Unit,
    onToggleReminder: (ReminderEntity) -> Unit,
    onDeleteReminder: (Long) -> Unit
) {
    var noteTitle by remember { mutableStateOf("") }
    var noteBody by remember { mutableStateOf("") }
    var remTitle by remember { mutableStateOf("") }
    var remMinutes by remember { mutableStateOf("15") }
    val timeFmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Text("QUICK NOTE", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        OutlinedTextField(
            value = noteTitle,
            onValueChange = { noteTitle = it },
            placeholder = { Text("Note title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("note_title_input")
        )
        OutlinedTextField(
            value = noteBody,
            onValueChange = { noteBody = it },
            placeholder = { Text("Write note details...") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("note_body_input")
        )
        Button(
            onClick = {
                onAddNote(noteTitle, noteBody)
                noteTitle = ""
                noteBody = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
            modifier = Modifier.testTag("add_note_button")
        ) {
            Icon(Icons.Default.NoteAdd, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Save Note")
        }
        notes.forEach { note ->
            HudPanel(highlightBorder = note.isPinned, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(note.title, style = MaterialTheme.typography.titleMedium, color = BumbleGoldBright)
                        if (note.content.isNotBlank()) {
                            Text(note.content, style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
                        }
                    }
                    IconButton(onClick = { onTogglePinNote(note) }) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pin note",
                            tint = if (note.isPinned) BumbleGoldBright else BumbleTextSecondary
                        )
                    }
                    IconButton(onClick = { onDeleteNote(note) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete note", tint = BumbleCrimson)
                    }
                }
            }
        }
    }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Text("REMINDERS", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = remTitle,
                onValueChange = { remTitle = it },
                placeholder = { Text("Reminder task") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = remMinutes,
                onValueChange = { remMinutes = it.filter { ch -> ch.isDigit() } },
                placeholder = { Text("Min") },
                singleLine = true,
                modifier = Modifier.width(88.dp)
            )
        }
        Button(
            onClick = {
                val mins = remMinutes.toIntOrNull()?.coerceAtLeast(1) ?: 15
                onAddReminder(remTitle, mins)
                remTitle = ""
            },
            colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Schedule Reminder")
        }
        reminders.forEach { r ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.isCompleted, onCheckedChange = { onToggleReminder(r) })
                Column(modifier = Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.bodyLarge)
                    Text(timeFmt.format(Date(r.triggerAtMillis)), style = MaterialTheme.typography.labelSmall, color = BumbleTextSecondary)
                }
                IconButton(onClick = { onDeleteReminder(r.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete reminder", tint = BumbleCrimson)
                }
            }
        }
    }
}

@Composable
private fun WeatherAndWebTab(
    toolsState: ToolsUiState,
    onFetchWeather: (String) -> Unit,
    onSearchWeb: (String) -> Unit
) {
    var cityInput by remember { mutableStateOf("Karachi") }
    var searchInput by remember { mutableStateOf("") }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.WbSunny, contentDescription = null, tint = BumbleGold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("LIVE WEATHER (OPEN-METEO)", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cityInput,
                onValueChange = { cityInput = it },
                placeholder = { Text("Enter city name") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("weather_city_input")
            )
            Button(
                onClick = { onFetchWeather(cityInput) },
                colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                modifier = Modifier.testTag("weather_fetch_button")
            ) {
                Text("Check")
            }
        }
        if (toolsState.isLoadingWeather) CircularProgressIndicator(color = BumbleGold)
        toolsState.weatherError?.let { Text(it, color = BumbleCrimson, style = MaterialTheme.typography.bodyMedium) }
        toolsState.weatherResult?.let { w ->
            Text("${w.locationName}: ${w.temperatureC}°C • ${w.conditionLabel}", style = MaterialTheme.typography.titleMedium, color = BumbleGoldBright)
            Text(
                "Feels like ${w.feelsLikeC}°C | High ${w.highC}°C / Low ${w.lowC}°C | Humidity ${w.humidityPercent}% | Wind ${w.windKmh} km/h",
                style = MaterialTheme.typography.bodyMedium,
                color = BumbleTextSecondary
            )
        }
    }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = BumbleGold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("WEB SEARCH", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                placeholder = { Text("Search DuckDuckGo / Wikipedia...") },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("web_search_input")
            )
            Button(
                onClick = { onSearchWeb(searchInput) },
                colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                modifier = Modifier.testTag("web_search_button")
            ) {
                Text("Search")
            }
        }
        if (toolsState.isSearching) CircularProgressIndicator(color = BumbleGold)
        toolsState.searchError?.let { Text(it, color = BumbleCrimson, style = MaterialTheme.typography.bodyMedium) }
        toolsState.searchResult?.let { res ->
            Text(res.heading, style = MaterialTheme.typography.titleMedium, color = BumbleGoldBright)
            Text(res.summary, style = MaterialTheme.typography.bodyMedium)
            if (res.sourceUrl.isNotBlank()) {
                Text(res.sourceUrl, style = MaterialTheme.typography.labelSmall, color = BumbleEmerald)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiUtilitiesTab(
    toolsState: ToolsUiState,
    onEvaluateMath: (String) -> Unit,
    onTranslate: (String, String) -> Unit,
    onGeneratePlan: (String) -> Unit
) {
    var calcExpr by remember { mutableStateOf("(125 * 4) + sqrt(144)") }
    var translateInput by remember { mutableStateOf("") }
    var targetLang by remember { mutableStateOf("Urdu") }
    var goalsInput by remember { mutableStateOf("") }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Calculate, contentDescription = null, tint = BumbleGold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SCIENTIFIC CALCULATOR", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = calcExpr,
                onValueChange = { calcExpr = it },
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("calc_expression_input")
            )
            Button(
                onClick = { onEvaluateMath(calcExpr) },
                colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack),
                modifier = Modifier.testTag("calc_eval_button")
            ) {
                Text("=")
            }
        }
        if (toolsState.calcResult.isNotBlank()) {
            Text("Result: ${toolsState.calcResult}", style = MaterialTheme.typography.titleMedium, color = BumbleGoldBright)
        }
        toolsState.calcError?.let { Text(it, color = BumbleCrimson, style = MaterialTheme.typography.bodyMedium) }
    }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Translate, contentDescription = null, tint = BumbleGold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("MULTILINGUAL TRANSLATOR", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("English", "Urdu", "Hindi", "Arabic", "Spanish").forEach { lang ->
                FilterChip(
                    selected = targetLang == lang,
                    onClick = { targetLang = lang },
                    label = { Text(lang) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BumbleGold, selectedLabelColor = BumbleVoidBlack)
                )
            }
        }
        OutlinedTextField(
            value = translateInput,
            onValueChange = { translateInput = it },
            placeholder = { Text("Enter text in any language...") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onTranslate(translateInput, targetLang) },
            colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack)
        ) {
            Text("Translate to $targetLang")
        }
        if (toolsState.isTranslating) CircularProgressIndicator(color = BumbleGold)
        if (toolsState.translationResult.isNotBlank()) {
            Text(toolsState.translationResult, style = MaterialTheme.typography.bodyMedium)
        }
    }

    HudPanel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = BumbleGold)
            Spacer(modifier = Modifier.width(8.dp))
            Text("DAILY PLANNER", style = MaterialTheme.typography.labelLarge, color = BumbleGold)
        }
        OutlinedTextField(
            value = goalsInput,
            onValueChange = { goalsInput = it },
            placeholder = { Text("Today's priorities & deadlines...") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { onGeneratePlan(goalsInput) },
            colors = ButtonDefaults.buttonColors(containerColor = BumbleGold, contentColor = BumbleVoidBlack)
        ) {
            Text("Generate Daily Plan")
        }
        if (toolsState.isPlanning) CircularProgressIndicator(color = BumbleGold)
        if (toolsState.plannerResult.isNotBlank()) {
            Text(toolsState.plannerResult, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
