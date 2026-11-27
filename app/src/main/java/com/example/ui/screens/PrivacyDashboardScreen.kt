package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.assistant.NotificationCache
import com.example.ui.components.HudPanel
import com.example.ui.theme.BumbleCrimson
import com.example.ui.theme.BumbleEmerald
import com.example.ui.theme.BumbleGold
import com.example.ui.theme.BumbleGoldBright
import com.example.ui.theme.BumbleTextSecondary
import com.example.ui.theme.BumbleVoidBlack

@Composable
fun PrivacyDashboardScreen(
    onPermissionStatusChanged: () -> Unit,
    onClearAllLocalData: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTick++
                onPermissionStatusChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshTick++
        onPermissionStatusChanged()
    }

    val micGranted = remember(refreshTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val camGranted = remember(refreshTick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }
    val notifGranted = remember(refreshTick) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    val notifListenerEnabled = remember(refreshTick) { NotificationCache.isListenerEnabled(context) }
    val writeSettingsGranted = remember(refreshTick) { Settings.System.canWrite(context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Permissions & Privacy", style = MaterialTheme.typography.headlineMedium, color = BumbleGoldBright)

            HudPanel(highlightBorder = true, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = BumbleEmerald)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("ZERO-BACKGROUND PRIVACY ARCHITECTURE", style = MaterialTheme.typography.labelLarge, color = BumbleEmerald)
                }
                Text(
                    "• Microphone & wake-word run strictly in foreground and stop immediately when you leave the app.\n" +
                        "• Zero floating overlays or hidden background services.\n" +
                        "• Gemini API keys are encrypted on-device with Android Keystore AES-256-GCM.\n" +
                        "• Notifications are read only if you explicitly enable Android Notification Access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BumbleTextSecondary
                )
            }

            PermissionRowCard(
                icon = Icons.Default.Mic,
                title = "Microphone (RECORD_AUDIO)",
                description = "Required for Gemini Live real-time audio streaming (16 kHz PCM) and foreground 'Hey Bumble' wake word.",
                granted = micGranted,
                actionLabel = if (micGranted) "App Settings" else "Grant Mic",
                onAction = {
                    if (!micGranted) {
                        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                }
            )

            PermissionRowCard(
                icon = Icons.Default.CameraAlt,
                title = "Camera (CAMERA)",
                description = "Used only while you open Camera Vision for live object recognition.",
                granted = camGranted,
                actionLabel = if (camGranted) "App Settings" else "Grant Camera",
                onAction = {
                    if (!camGranted) {
                        permLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                }
            )

            PermissionRowCard(
                icon = Icons.Default.Notifications,
                title = "Post Notifications",
                description = "Used to deliver your local scheduled reminders and consented screen-capture notice.",
                granted = notifGranted,
                actionLabel = if (notifGranted) "Granted" else "Grant",
                onAction = {
                    if (!notifGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                }
            )

            PermissionRowCard(
                icon = Icons.Default.NotificationsActive,
                title = "Notification Listener Access",
                description = "Explicit opt-in so Bumble can summarize recent notifications when asked.",
                granted = notifListenerEnabled,
                actionLabel = if (notifListenerEnabled) "Manage" else "Enable Access",
                onAction = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                }
            )

            PermissionRowCard(
                icon = Icons.Default.Tune,
                title = "Modify System Settings (Brightness)",
                description = "Allows Bumble to adjust screen brightness directly when requested.",
                granted = writeSettingsGranted,
                actionLabel = if (writeSettingsGranted) "Manage" else "Grant",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                        )
                    }
                }
            )

            HudPanel(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = BumbleCrimson)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("DATA ERASURE", style = MaterialTheme.typography.labelLarge, color = BumbleCrimson)
                }
                Text(
                    "Permanently erase all local conversation sessions, personal memories, notes, and reminders.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BumbleTextSecondary
                )
                OutlinedButton(
                    onClick = onClearAllLocalData,
                    border = BorderStroke(1.dp, BumbleCrimson),
                    modifier = Modifier.testTag("wipe_all_local_data_button")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = BumbleCrimson)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Erase All Local Data", color = BumbleCrimson)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PermissionRowCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    HudPanel(highlightBorder = granted, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (granted) BumbleEmerald else BumbleGold)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (granted) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "Granted", tint = BumbleEmerald)
                    }
                }
                Text(description, style = MaterialTheme.typography.bodyMedium, color = BumbleTextSecondary)
            }
        }
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (granted) BumbleGold.copy(alpha = 0.18f) else BumbleGold,
                contentColor = if (granted) BumbleGoldBright else BumbleVoidBlack
            )
        ) {
            Text(actionLabel)
        }
    }
}
