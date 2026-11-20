package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai.CoreState
import com.example.ui.BumbleViewModel
import com.example.ui.components.HudBackdrop
import com.example.ui.screens.AssistantChatScreen
import com.example.ui.screens.CoreDashboardScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.PrivacyDashboardScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.ToolsDashboardScreen
import com.example.ui.screens.VisionScreen
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleGraphite
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleTheme
import com.example.ui.theme.BumbleVoidBlack

object BumbleRoutes {
    const val CORE_DASHBOARD = "core_dashboard"
    const val ASSISTANT_CHAT = "assistant_chat"
    const val VISION = "vision"
    const val TOOLS = "tools_dashboard"
    const val MEMORY = "memory"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val PRIVACY = "privacy"
}

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {

    private val viewModel: BumbleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BumbleTheme {
                BumbleRootShell(viewModel = viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }
}

@Composable
private fun BumbleRootShell(viewModel: BumbleViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val app = context.applicationContext as BumbleApp

    val coreState by viewModel.coreState.collectAsStateWithLifecycle()
    val wakeWordStatus by viewModel.wakeWordStatus.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val partialVoiceText by viewModel.partialVoiceText.collectAsStateWithLifecycle()
    val audioAmplitude by viewModel.audioAmplitude.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val messages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val isSpeakerMuted by viewModel.isSpeakerMuted.collectAsStateWithLifecycle()
    val hasApiKey by viewModel.hasApiKey.collectAsStateWithLifecycle()
    val maskedApiKey by viewModel.maskedApiKey.collectAsStateWithLifecycle()
    val isTestingConnection by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val connectionTestResult by viewModel.connectionTestResult.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val selectedSessionMessages by viewModel.selectedSessionMessages.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val visionState by viewModel.visionState.collectAsStateWithLifecycle()
    val toolsState by viewModel.toolsState.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> viewModel.onAppBackgrounded()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.evaluateWakeWordStandby()
        if (granted) {
            viewModel.startVoiceCapture()
        } else {
            navController.navigate(BumbleRoutes.ASSISTANT_CHAT) {
                launchSingleTop = true
            }
        }
    }

    val onTapVoiceAction: () -> Unit = {
        when (coreState) {
            CoreState.LISTENING -> viewModel.stopVoiceCapture()
            CoreState.SPEAKING -> viewModel.stopSpeaking()
            else -> {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    viewModel.startVoiceCapture()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    val primaryNavItems = listOf(
        NavDestinationItem(BumbleRoutes.CORE_DASHBOARD, "Core", Icons.Default.Home, "nav_core"),
        NavDestinationItem(BumbleRoutes.ASSISTANT_CHAT, "Chat", Icons.AutoMirrored.Filled.Chat, "nav_chat"),
        NavDestinationItem(BumbleRoutes.VISION, "Vision", Icons.Default.CameraAlt, "nav_vision"),
        NavDestinationItem(BumbleRoutes.TOOLS, "Tools", Icons.Default.Build, "nav_tools"),
        NavDestinationItem(BumbleRoutes.SETTINGS, "Settings", Icons.Default.Settings, "nav_settings")
    )

    val allRailItems = primaryNavItems + listOf(
        NavDestinationItem(BumbleRoutes.MEMORY, "Memory", Icons.Default.Psychology, "nav_memory"),
        NavDestinationItem(BumbleRoutes.HISTORY, "History", Icons.Default.History, "nav_history"),
        NavDestinationItem(BumbleRoutes.PRIVACY, "Privacy", Icons.Default.Security, "nav_privacy")
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    HudBackdrop {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(
                            containerColor = BumbleGraphite.copy(alpha = 0.96f),
                            modifier = Modifier.navigationBarsPadding()
                        ) {
                            primaryNavItems.forEach { item ->
                                val selected = currentRoute == item.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTo(item.route) },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = BumbleVoidBlack,
                                        selectedTextColor = BumbleGoldBright,
                                        indicatorColor = BumbleGold,
                                        unselectedIconColor = BumbleTextSecondary,
                                        unselectedTextColor = BumbleTextSecondary
                                    ),
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(containerColor = BumbleGraphite.copy(alpha = 0.94f)) {
                            allRailItems.forEach { item ->
                                val selected = currentRoute == item.route
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTo(item.route) },
                                    icon = { Icon(item.icon, contentDescription = item.label) },
                                    label = { Text(item.label, maxLines = 1) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = BumbleVoidBlack,
                                        selectedTextColor = BumbleGoldBright,
                                        indicatorColor = BumbleGold,
                                        unselectedIconColor = BumbleTextSecondary,
                                        unselectedTextColor = BumbleTextSecondary
                                    ),
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = BumbleRoutes.CORE_DASHBOARD,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable(BumbleRoutes.CORE_DASHBOARD) {
                            CoreDashboardScreen(
                                coreState = coreState,
                                wakeWordStatus = wakeWordStatus,
                                statusBanner = statusBanner,
                                partialVoiceText = partialVoiceText,
                                audioAmplitude = audioAmplitude,
                                settings = settings,
                                hasApiKey = hasApiKey,
                                hasMicPermission = viewModel.hasMicPermission(),
                                latestEntry = messages.lastOrNull(),
                                onTapCoreVoice = onTapVoiceAction,
                                onSendQuickCommand = { cmd ->
                                    viewModel.sendMessage(cmd)
                                    navigateTo(BumbleRoutes.ASSISTANT_CHAT)
                                },
                                onNavigateToChat = { navigateTo(BumbleRoutes.ASSISTANT_CHAT) },
                                onNavigateToVision = { navigateTo(BumbleRoutes.VISION) },
                                onNavigateToTools = { navigateTo(BumbleRoutes.TOOLS) },
                                onNavigateToMemory = { navigateTo(BumbleRoutes.MEMORY) },
                                onNavigateToHistory = { navigateTo(BumbleRoutes.HISTORY) },
                                onNavigateToSettings = { navigateTo(BumbleRoutes.SETTINGS) },
                                onNavigateToPrivacy = { navigateTo(BumbleRoutes.PRIVACY) }
                            )
                        }

                        composable(BumbleRoutes.ASSISTANT_CHAT) {
                            AssistantChatScreen(
                                coreState = coreState,
                                statusBanner = statusBanner,
                                partialVoiceText = partialVoiceText,
                                audioAmplitude = audioAmplitude,
                                settings = settings,
                                messages = messages,
                                isProcessing = isProcessing,
                                isSpeakerMuted = isSpeakerMuted,
                                onTapVoice = onTapVoiceAction,
                                onStopSpeaking = { viewModel.stopSpeaking() },
                                onToggleSpeakerMute = { viewModel.toggleSpeakerMute() },
                                onNewChat = { viewModel.startNewChat() },
                                onSendText = { viewModel.sendMessage(it) }
                            )
                        }

                        composable(BumbleRoutes.HISTORY) {
                            HistoryScreen(
                                sessions = sessions,
                                selectedMessages = selectedSessionMessages,
                                onSelectSession = { viewModel.loadSessionMessages(it) },
                                onDeleteSession = { viewModel.deleteSession(it) },
                                onClearAll = { viewModel.clearAllHistory() }
                            )
                        }

                        composable(BumbleRoutes.TOOLS) {
                            ToolsDashboardScreen(
                                toolsState = toolsState,
                                notes = notes,
                                reminders = reminders,
                                installedApps = installedApps,
                                onOpenSystemSetting = { s ->
                                    viewModel.triggerDeviceAction { app.actionExecutor.openSystemSetting(s).summary }
                                },
                                onAdjustVolume = { pct ->
                                    viewModel.triggerDeviceAction { app.actionExecutor.adjustVolume(percent = pct).summary }
                                },
                                onAdjustBrightness = { pct ->
                                    viewModel.triggerDeviceAction { app.actionExecutor.adjustBrightness(pct).summary }
                                },
                                onLaunchCamera = {
                                    viewModel.triggerDeviceAction { app.actionExecutor.launchSystemCamera().summary }
                                },
                                onSetAlarm = { h, m ->
                                    viewModel.triggerDeviceAction { app.actionExecutor.setAlarm(h, m).summary }
                                },
                                onLaunchApp = { viewModel.launchInstalledApp(it) },
                                onAddNote = { t, c -> viewModel.addNote(t, c) },
                                onTogglePinNote = { viewModel.togglePinNote(it) },
                                onDeleteNote = { viewModel.deleteNote(it) },
                                onAddReminder = { t, m -> viewModel.addReminder(t, m) },
                                onToggleReminder = { viewModel.toggleReminderComplete(it) },
                                onDeleteReminder = { viewModel.deleteReminder(it) },
                                onFetchWeather = { viewModel.fetchWeather(it) },
                                onSearchWeb = { viewModel.runWebSearch(it) },
                                onEvaluateMath = { viewModel.evaluateMath(it) },
                                onTranslate = { txt, lang -> viewModel.runTranslation(txt, lang) },
                                onGeneratePlan = { viewModel.generateDailyPlan(it) }
                            )
                        }

                        composable(BumbleRoutes.MEMORY) {
                            MemoryScreen(
                                memories = memories,
                                onAddMemory = { c, cat -> viewModel.addMemory(c, cat) },
                                onApproveMemory = { viewModel.approveMemory(it) },
                                onDeleteMemory = { viewModel.deleteMemory(it) },
                                onClearAll = { viewModel.clearAllMemories() }
                            )
                        }

                        composable(BumbleRoutes.VISION) {
                            VisionScreen(
                                visionState = visionState,
                                onImageReady = { bmp, label -> viewModel.setVisionImage(bmp, label) },
                                onAnalyze = { viewModel.analyzeVisionBitmap(it) },
                                onSendToChat = { bmp, prompt ->
                                    viewModel.sendMessage(prompt, attachedBitmap = bmp)
                                    navigateTo(BumbleRoutes.ASSISTANT_CHAT)
                                },
                                onVisionError = { viewModel.setVisionError(it) }
                            )
                        }

                        composable(BumbleRoutes.SETTINGS) {
                            SettingsScreen(
                                settings = settings,
                                hasApiKey = hasApiKey,
                                maskedApiKey = maskedApiKey,
                                isTestingConnection = isTestingConnection,
                                connectionTestResult = connectionTestResult,
                                onSaveApiKey = { viewModel.saveApiKey(it) },
                                onClearApiKey = { viewModel.clearApiKey() },
                                onTestConnection = { viewModel.testApiConnection(it) },
                                onUpdateSettings = { viewModel.updateSettings(it) },
                                onClearHistory = { viewModel.clearAllHistory() },
                                onClearMemories = { viewModel.clearAllMemories() },
                                onNavigateToPrivacy = { navigateTo(BumbleRoutes.PRIVACY) }
                            )
                        }

                        composable(BumbleRoutes.PRIVACY) {
                            PrivacyDashboardScreen(
                                onPermissionStatusChanged = { viewModel.evaluateWakeWordStandby() },
                                onClearAllLocalData = { viewModel.clearAllLocalData() }
                            )
                        }
                    }
                }
            }
        }
    }
}
