package com.example.assistant

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CapturedNotification(
    val key: String,
    val appPackage: String,
    val title: String,
    val text: String,
    val postTime: Long
)

object NotificationCache {
    private val _notifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
    val notifications: StateFlow<List<CapturedNotification>> = _notifications.asStateFlow()

    fun isListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val component = ComponentName(context, BumbleNotificationListenerService::class.java)
        return flat.split(":").any {
            ComponentName.unflattenFromString(it) == component
        }
    }

    fun updateFromStatusBar(items: List<CapturedNotification>) {
        _notifications.value = items.take(25)
    }

    fun addOrUpdate(item: CapturedNotification) {
        val current = _notifications.value.filterNot { it.key == item.key }
        _notifications.value = (listOf(item) + current).take(25)
    }

    fun removeByKey(key: String) {
        _notifications.value = _notifications.value.filterNot { it.key == key }
    }

    fun clear() {
        _notifications.value = emptyList()
    }
}

class BumbleNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            val active = activeNotifications?.mapNotNull { it.toCaptured() }.orEmpty()
            NotificationCache.updateFromStatusBar(active)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.toCaptured()?.let { NotificationCache.addOrUpdate(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { NotificationCache.removeByKey(it) }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationCache.clear()
    }

    private fun StatusBarNotification.toCaptured(): CapturedNotification? {
        if (packageName == applicationContext.packageName) return null
        val extras = notification.extras ?: return null
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return null
        return CapturedNotification(
            key = key,
            appPackage = packageName,
            title = title,
            text = text,
            postTime = postTime
        )
    }
}
