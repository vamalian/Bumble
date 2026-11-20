package com.example.assistant

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.example.data.local.MemoryDao
import com.example.data.local.MemoryEntity
import com.example.data.local.NoteDao
import com.example.data.local.NoteEntity
import com.example.data.local.ReminderDao
import com.example.data.local.ReminderEntity
import org.json.JSONArray
import org.json.JSONObject

data class InstalledAppInfo(
    val label: String,
    val packageName: String
)

data class ToolExecutionResult(
    val success: Boolean,
    val summary: String,
    val payloadJson: JSONObject
)

/**
 * Executes real Android OS intents, system controls, and local assistant tools.
 * Never simulates or fakes an unsupported action.
 */
class AndroidActionExecutor(
    private val context: Context,
    private val noteDao: NoteDao,
    private val reminderDao: ReminderDao,
    private val memoryDao: MemoryDao,
    private val externalTools: ExternalToolsClient
) {

    fun listInstalledApps(): List<InstalledAppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }
        return resolved.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = info.loadLabel(pm)?.toString().orEmpty().ifBlank { pkg }
            InstalledAppInfo(label = label, packageName = pkg)
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    fun openApp(appQuery: String): ToolExecutionResult {
        val query = appQuery.trim().lowercase()
        if (query.isBlank()) {
            return ToolExecutionResult(
                success = false,
                summary = "Please specify an app name to open.",
                payloadJson = JSONObject().put("ok", false).put("error", "Empty app query")
            )
        }
        val apps = listInstalledApps()
        val match = apps.firstOrNull { it.label.lowercase() == query || it.packageName.lowercase() == query }
            ?: apps.firstOrNull { it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query) }

        if (match == null) {
            return ToolExecutionResult(
                success = false,
                summary = "No installed launchable app matching '$appQuery' was found.",
                payloadJson = JSONObject().put("ok", false).put("error", "App not found: $appQuery")
            )
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(match.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launchIntent != null) {
            runCatching {
                context.startActivity(launchIntent)
                ToolExecutionResult(
                    success = true,
                    summary = "Opened ${match.label}.",
                    payloadJson = JSONObject().put("ok", true).put("app", match.label).put("package", match.packageName)
                )
            }.getOrElse { e ->
                ToolExecutionResult(
                    success = false,
                    summary = "Failed to open ${match.label}: ${e.localizedMessage}",
                    payloadJson = JSONObject().put("ok", false).put("error", e.localizedMessage ?: "Launch error")
                )
            }
        } else {
            ToolExecutionResult(
                success = false,
                summary = "${match.label} cannot be launched directly.",
                payloadJson = JSONObject().put("ok", false).put("error", "No launch intent for ${match.packageName}")
            )
        }
    }

    fun openSystemSetting(settingName: String): ToolExecutionResult {
        val normalized = settingName.trim().lowercase()
        val action = when {
            normalized.contains("wifi") || normalized.contains("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            normalized.contains("bluetooth") -> Settings.ACTION_BLUETOOTH_SETTINGS
            normalized.contains("display") || normalized.contains("brightness") -> Settings.ACTION_DISPLAY_SETTINGS
            normalized.contains("sound") || normalized.contains("volume") -> Settings.ACTION_SOUND_SETTINGS
            normalized.contains("location") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            normalized.contains("battery") -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            normalized.contains("notification") -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            normalized.contains("app") -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }

        val intent = if (action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
            Intent(action, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(action)
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(
                success = true,
                summary = "Opened Android $settingName settings.",
                payloadJson = JSONObject().put("ok", true).put("openedSetting", action)
            )
        }.getOrElse { error ->
            ToolExecutionResult(
                success = false,
                summary = "Failed to open settings: ${error.localizedMessage}",
                payloadJson = JSONObject().put("ok", false).put("error", error.localizedMessage ?: "Failed")
            )
        }
    }

    fun adjustVolume(stream: String = "media", percent: Int? = null, direction: String? = null): ToolExecutionResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val streamType = when (stream.lowercase()) {
            "alarm" -> AudioManager.STREAM_ALARM
            "ring", "ringtone" -> AudioManager.STREAM_RING
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }

        val maxVol = audioManager.getStreamMaxVolume(streamType).coerceAtLeast(1)
        val currentVol = audioManager.getStreamVolume(streamType)

        val targetVol = when {
            percent != null -> ((percent.coerceIn(0, 100) / 100.0) * maxVol).toInt()
            direction?.lowercase() == "up" -> (currentVol + maxOf(1, maxVol / 7)).coerceAtMost(maxVol)
            direction?.lowercase() == "down" -> (currentVol - maxOf(1, maxVol / 7)).coerceAtLeast(0)
            direction?.lowercase() == "mute" -> 0
            else -> currentVol
        }

        return runCatching {
            audioManager.setStreamVolume(streamType, targetVol, AudioManager.FLAG_SHOW_UI)
            val newPercent = ((targetVol.toFloat() / maxVol) * 100).toInt()
            ToolExecutionResult(
                success = true,
                summary = "Set $stream volume to $newPercent%.",
                payloadJson = JSONObject()
                    .put("ok", true)
                    .put("stream", stream)
                    .put("volumePercent", newPercent)
            )
        }.getOrElse { e ->
            ToolExecutionResult(
                success = false,
                summary = "Could not adjust volume: ${e.localizedMessage}",
                payloadJson = JSONObject().put("ok", false).put("error", e.localizedMessage ?: "SecurityException")
            )
        }
    }

    fun adjustBrightness(percent: Int): ToolExecutionResult {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
            return ToolExecutionResult(
                success = false,
                summary = "Modify System Settings permission is required to change brightness. Opened Android permission settings.",
                payloadJson = JSONObject()
                    .put("ok", false)
                    .put("requiresPermission", "WRITE_SETTINGS")
                    .put("message", "Opened Write Settings permission screen for user approval.")
            )
        }

        val clamped = percent.coerceIn(5, 100)
        val value255 = ((clamped / 100.0) * 255).toInt().coerceIn(10, 255)
        return runCatching {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value255
            )
            ToolExecutionResult(
                success = true,
                summary = "Set screen brightness to $clamped%.",
                payloadJson = JSONObject().put("ok", true).put("brightnessPercent", clamped)
            )
        }.getOrElse { e ->
            ToolExecutionResult(
                success = false,
                summary = "Unable to set brightness: ${e.localizedMessage}",
                payloadJson = JSONObject().put("ok", false).put("error", e.localizedMessage ?: "Failed")
            )
        }
    }

    fun launchSystemCamera(): ToolExecutionResult {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(
                success = true,
                summary = "Launched device camera.",
                payloadJson = JSONObject().put("ok", true)
            )
        }.getOrElse { e ->
            ToolExecutionResult(
                success = false,
                summary = "Could not launch camera app: ${e.localizedMessage}",
                payloadJson = JSONObject().put("ok", false).put("error", e.localizedMessage ?: "No camera app")
            )
        }
    }

    fun setAlarm(hour: Int, minute: Int, message: String = "Bumble Alarm"): ToolExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Bumble Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            val timeStr = String.format(java.util.Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))
            ToolExecutionResult(
                success = true,
                summary = "Opened Clock to set alarm for $timeStr ($message).",
                payloadJson = JSONObject().put("ok", true).put("time", timeStr).put("label", message)
            )
        }.getOrElse { e ->
            ToolExecutionResult(
                success = false,
                summary = "Unable to set alarm: ${e.localizedMessage}",
                payloadJson = JSONObject().put("ok", false).put("error", e.localizedMessage ?: "No clock app")
            )
        }
    }

    fun setTimer(seconds: Int, message: String = "Bumble Timer"): ToolExecutionResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_MESSAGE, message.ifBlank { "Bumble Timer" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ToolExecutionResult(
                success = true,
                summary = "Set timer for ${seconds}s ($message).",
                payloadJson = JSONObject().put("ok", true).put("seconds", seconds).put("label", message)
            )
        }.getOrElse { e ->
            ToolExecutionResult(
                success = false,
                summary = "Unable to set timer: ${e.localizedMessage}",
                payloadJson = JSONObject().put("ok", false).put("error", e.localizedMessage ?: "No clock app")
            )
        }
    }

    suspend fun createNote(title: String, content: String): ToolExecutionResult {
        val cleanTitle = title.trim().ifBlank { "Quick Note" }
        val cleanContent = content.trim()
        val id = noteDao.insertNote(NoteEntity(title = cleanTitle, content = cleanContent))
        return ToolExecutionResult(
            success = true,
            summary = "Saved note '$cleanTitle'.",
            payloadJson = JSONObject().put("ok", true).put("noteId", id).put("title", cleanTitle)
        )
    }

    suspend fun listNotes(): ToolExecutionResult {
        val notes = noteDao.getNotesOnce().take(10)
        val arr = JSONArray()
        notes.forEach { note ->
            arr.put(
                JSONObject()
                    .put("id", note.id)
                    .put("title", note.title)
                    .put("content", note.content)
            )
        }
        return ToolExecutionResult(
            success = true,
            summary = "Found ${notes.size} saved notes.",
            payloadJson = JSONObject().put("ok", true).put("notes", arr)
        )
    }

    suspend fun createReminder(title: String, minutesFromNow: Int): ToolExecutionResult {
        val delayMinutes = minutesFromNow.coerceAtLeast(1)
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
        val id = reminderDao.insertReminder(
            ReminderEntity(title = title.trim().ifBlank { "Reminder" }, triggerAtMillis = triggerAt)
        )
        scheduleReminderAlarm(id, title, triggerAt)
        return ToolExecutionResult(
            success = true,
            summary = "Created reminder '$title' in $delayMinutes minute(s).",
            payloadJson = JSONObject()
                .put("ok", true)
                .put("reminderId", id)
                .put("title", title)
                .put("minutesFromNow", delayMinutes)
        )
    }

    fun scheduleReminderAlarm(reminderId: Long, title: String, triggerAtMillis: Long) {
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
                putExtra(ReminderReceiver.EXTRA_REMINDER_TITLE, title)
            }
            val requestCode = (reminderId and 0x7FFFFFFFL).toInt()
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    suspend fun saveSuggestedMemory(content: String, category: String = "Fact", autoApprove: Boolean = false): ToolExecutionResult {
        val id = memoryDao.insertMemory(
            MemoryEntity(
                content = content.trim(),
                category = category.ifBlank { "Fact" },
                isApproved = autoApprove,
                source = if (autoApprove) "user" else "ai_suggested"
            )
        )
        val msg = if (autoApprove) {
            "Saved memory: $content"
        } else {
            "Added '$content' to your Personal Memory queue for approval."
        }
        return ToolExecutionResult(
            success = true,
            summary = msg,
            payloadJson = JSONObject().put("ok", true).put("memoryId", id).put("requiresApproval", !autoApprove)
        )
    }

    fun readNotifications(): ToolExecutionResult {
        if (!NotificationCache.isListenerEnabled(context)) {
            return ToolExecutionResult(
                success = false,
                summary = "Notification Access is not enabled. The user must enable it in Permissions & Privacy.",
                payloadJson = JSONObject()
                    .put("ok", false)
                    .put("requiresPermission", "BIND_NOTIFICATION_LISTENER_SERVICE")
                    .put("message", "Notification access not granted.")
            )
        }
        val items = NotificationCache.notifications.value.take(10)
        val arr = JSONArray()
        items.forEach { n ->
            arr.put(
                JSONObject()
                    .put("app", n.appPackage)
                    .put("title", n.title)
                    .put("text", n.text)
            )
        }
        return ToolExecutionResult(
            success = true,
            summary = if (items.isEmpty()) "No recent notifications." else "Read ${items.size} active notifications.",
            payloadJson = JSONObject().put("ok", true).put("count", items.size).put("notifications", arr)
        )
    }

    suspend fun executeFunctionCall(name: String, args: JSONObject): ToolExecutionResult {
        return when (name) {
            "open_app" -> openApp(args.optString("app_name"))
            "open_settings" -> openSystemSetting(args.optString("setting"))
            "adjust_volume" -> adjustVolume(
                stream = args.optString("stream", "media"),
                percent = if (args.has("percent")) args.optInt("percent") else null,
                direction = args.optString("direction").takeIf { it.isNotBlank() }
            )
            "adjust_brightness" -> adjustBrightness(args.optInt("percent", 50))
            "launch_camera" -> launchSystemCamera()
            "set_alarm" -> setAlarm(
                hour = args.optInt("hour", 7),
                minute = args.optInt("minute", 0),
                message = args.optString("label", "Bumble Alarm")
            )
            "set_timer" -> setTimer(
                seconds = args.optInt("seconds", 60),
                message = args.optString("label", "Bumble Timer")
            )
            "create_note" -> createNote(
                title = args.optString("title", "Note"),
                content = args.optString("content", "")
            )
            "list_notes" -> listNotes()
            "create_reminder" -> createReminder(
                title = args.optString("title", "Reminder"),
                minutesFromNow = args.optInt("minutes_from_now", 10)
            )
            "save_memory" -> saveSuggestedMemory(
                content = args.optString("content", ""),
                category = args.optString("category", "Fact"),
                autoApprove = false
            )
            "read_notifications" -> readNotifications()
            "calculate" -> {
                val expr = args.optString("expression")
                MathEvaluator.evaluate(expr).fold(
                    onSuccess = { v ->
                        val formatted = MathEvaluator.formatResult(v)
                        ToolExecutionResult(
                            success = true,
                            summary = "$expr = $formatted",
                            payloadJson = JSONObject().put("ok", true).put("expression", expr).put("result", formatted)
                        )
                    },
                    onFailure = { e ->
                        ToolExecutionResult(
                            success = false,
                            summary = "Calculation error: ${e.message}",
                            payloadJson = JSONObject().put("ok", false).put("error", e.message ?: "Invalid math")
                        )
                    }
                )
            }
            "get_weather" -> {
                val city = args.optString("city", "")
                externalTools.fetchWeatherByCity(city.ifBlank { "London" }).fold(
                    onSuccess = { w ->
                        ToolExecutionResult(
                            success = true,
                            summary = "${w.locationName}: ${w.temperatureC}°C, ${w.conditionLabel}",
                            payloadJson = JSONObject()
                                .put("ok", true)
                                .put("location", w.locationName)
                                .put("temperatureC", w.temperatureC)
                                .put("feelsLikeC", w.feelsLikeC)
                                .put("condition", w.conditionLabel)
                                .put("humidityPercent", w.humidityPercent)
                                .put("windKmh", w.windKmh)
                                .put("highC", w.highC)
                                .put("lowC", w.lowC)
                        )
                    },
                    onFailure = { e ->
                        ToolExecutionResult(
                            success = false,
                            summary = "Weather lookup failed: ${e.message}",
                            payloadJson = JSONObject().put("ok", false).put("error", e.message ?: "Weather failed")
                        )
                    }
                )
            }
            "web_search" -> {
                val q = args.optString("query", "")
                externalTools.searchWeb(q).fold(
                    onSuccess = { res ->
                        ToolExecutionResult(
                            success = true,
                            summary = "Searched web for '${res.query}'",
                            payloadJson = JSONObject()
                                .put("ok", true)
                                .put("heading", res.heading)
                                .put("summary", res.summary)
                                .put("sourceUrl", res.sourceUrl)
                        )
                    },
                    onFailure = { e ->
                        ToolExecutionResult(
                            success = false,
                            summary = "Web search failed: ${e.message}",
                            payloadJson = JSONObject().put("ok", false).put("error", e.message ?: "Search failed")
                        )
                    }
                )
            }
            else -> ToolExecutionResult(
                success = false,
                summary = "Unsupported tool: $name",
                payloadJson = JSONObject().put("ok", false).put("error", "Unknown function $name")
            )
        }
    }

    companion object {
        /**
         * Standard Gemini REST API function declarations sent with generateContent requests.
         */
        fun buildGeminiFunctionDeclarations(): JSONArray {
            return JSONArray().apply {
                put(fn("open_app", "Open an installed Android application by name or package.",
                    mapOf("app_name" to ("STRING" to "Human-readable name of the app to launch, e.g. Spotify, YouTube, Settings"))))
                put(fn("open_settings", "Open a specific Android system settings screen.",
                    mapOf("setting" to ("STRING" to "Target setting: wifi, bluetooth, display, sound, location, battery, notification, or general"))))
                put(fn("adjust_volume", "Adjust device audio volume for media, alarm, or ringtone.",
                    mapOf(
                        "stream" to ("STRING" to "Audio stream: media, alarm, ring, notification"),
                        "percent" to ("INTEGER" to "Target volume percentage from 0 to 100 (optional)"),
                        "direction" to ("STRING" to "up, down, or mute (optional)")
                    )))
                put(fn("adjust_brightness", "Adjust device screen brightness percentage if Write Settings permission is granted.",
                    mapOf("percent" to ("INTEGER" to "Brightness percentage from 5 to 100"))))
                put(fn("launch_camera", "Launch the device camera app.", emptyMap()))
                put(fn("set_alarm", "Set an alarm on the Android Clock app.",
                    mapOf(
                        "hour" to ("INTEGER" to "Hour in 24-hour format (0-23)"),
                        "minute" to ("INTEGER" to "Minute (0-59)"),
                        "label" to ("STRING" to "Optional alarm label")
                    )))
                put(fn("set_timer", "Start a countdown timer on the Android Clock app.",
                    mapOf(
                        "seconds" to ("INTEGER" to "Timer duration in seconds"),
                        "label" to ("STRING" to "Optional timer label")
                    )))
                put(fn("create_note", "Save a new personal note in Bumble local storage.",
                    mapOf(
                        "title" to ("STRING" to "Short title for the note"),
                        "content" to ("STRING" to "Detailed note content")
                    )))
                put(fn("list_notes", "Retrieve saved personal notes.", emptyMap()))
                put(fn("create_reminder", "Create a local reminder that notifies the user after a specified number of minutes.",
                    mapOf(
                        "title" to ("STRING" to "Reminder title"),
                        "minutes_from_now" to ("INTEGER" to "Minutes from now to trigger")
                    )))
                put(fn("save_memory", "Queue a personal fact or preference to the user's Personal Memory screen for explicit user approval.",
                    mapOf(
                        "content" to ("STRING" to "Concise fact or preference to remember"),
                        "category" to ("STRING" to "Preference, Fact, Routine, or Note")
                    )))
                put(fn("read_notifications", "Read recent Android notifications only if the user explicitly granted Notification Access.", emptyMap()))
                put(fn("calculate", "Evaluate a mathematical expression accurately.",
                    mapOf("expression" to ("STRING" to "Math expression e.g. (125 * 4.5) + sqrt(144)"))))
                put(fn("get_weather", "Fetch real-time weather conditions for a city.",
                    mapOf("city" to ("STRING" to "City name, e.g. Karachi, Mumbai, London, New York"))))
                put(fn("web_search", "Search the web for factual summaries and quick answers.",
                    mapOf("query" to ("STRING" to "Search query"))))
            }
        }

        private fun fn(
            name: String,
            description: String,
            params: Map<String, Pair<String, String>>
        ): JSONObject {
            val props = JSONObject()
            params.forEach { (key, typeDesc) ->
                props.put(
                    key,
                    JSONObject()
                        .put("type", typeDesc.first)
                        .put("description", typeDesc.second)
                )
            }
            val parameters = JSONObject()
                .put("type", "OBJECT")
                .put("properties", props)
            return JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters)
        }
    }
}
