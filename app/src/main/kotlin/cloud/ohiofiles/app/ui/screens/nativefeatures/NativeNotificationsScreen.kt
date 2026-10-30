package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
fun NativeNotificationsScreen(api: OhioApiClient, sessionRepo: SessionRepository, onNavigate: ((String) -> Unit)? = null) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var category by rememberSaveable { mutableStateOf("all") }; var unreadOnly by rememberSaveable { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(emptyList<JsonObject>()) }; var unread by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf<String?>(null) }; var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<JsonObject?>(null) }; var confirmClear by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(NotificationDelivery.enabled(context)) }
    var pushStatus by remember { mutableStateOf(NativePush.state(context)) }
    DisposableEffect(context) {
        val preferences = NativePush.prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> pushStatus = NativePush.state(context) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun enablePush(value: Boolean) {
        enabled = value
        if (!value) { NativePush.disable(context); return }
        scope.launch {
            val key = sessionRepo.getSessionKey()
            val activity = generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }.filterIsInstance<android.app.Activity>().firstOrNull()
            if (key != null && activity != null) NativePush.enable(activity, api.getBaseUrl(), key)
            else { enabled = false; error = "Open your session before enabling Android notifications" }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> enablePush(allowed) }
    suspend fun load(append: Boolean = false) {
        loading = true
        try {
            sessionRepo.ensureSession().getOrThrow(); val key = sessionRepo.getSessionKey() ?: error("Session unavailable")
            val data = api.request("notifications", query = mapOf("sessionKey" to key, "category" to category, "unreadOnly" to unreadOnly.toString(), "limit" to "40", "cursor" to if (append) cursor else null)).getOrThrow().jsonObject
            notifications = if (append) (notifications + data.objects("notifications")).distinctBy { it.text("id") } else data.objects("notifications")
            unread = data.number("unreadCount"); cursor = if (data.text("nextCursor").isNotBlank()) data.objects("notifications").lastOrNull()?.text("id") else null; error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Notifications could not load" } finally { loading = false }
    }
    suspend fun mark(ids: List<String>? = null) {
        val key = sessionRepo.getSessionKey() ?: error("Session unavailable")
        api.request("notifications/read", "POST", if (ids == null) api.body("sessionKey" to key, "all" to true) else api.body("sessionKey" to key, "notificationIds" to jsonStrings(ids))).getOrThrow()
        load()
    }
    LaunchedEffect(category, unreadOnly, reload, lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { load(); delay(30_000) } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Your updates", style = MaterialTheme.typography.headlineSmall); Text(if (unread == 0) "You're all caught up" else "$unread unread", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Refresh notifications") }
            IconButton(onClick = { confirmClear = true }, enabled = notifications.isNotEmpty()) { Icon(Icons.Outlined.DeleteSweep, "Clear notifications") }
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text("Android notifications", style = MaterialTheme.typography.titleSmall); Text(if (enabled) pushStatus else "Push notifications with periodic fallback checks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(enabled, { value ->
                if (value && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else enablePush(value)
            })
        }
        if (enabled) Row(Modifier.padding(horizontal = 4.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ enablePush(true) }) { Text("Reconnect push") }
            TextButton({ scope.launch {
                try { api.request("notifications/push/test", "POST", api.body("sessionKey" to sessionRepo.getSessionKey())).getOrThrow(); NativePush.status(context, "Test requested. Waiting for a background update…") }
                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Test notification couldn't be requested" }
            } }) { Text("Send test") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("all", "chats", "comments", "reactions", "shares", "system")) { value -> FilterChip(category == value, { category = value }, label = { Text(value.replaceFirstChar(Char::uppercase)) }) }
        }
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(unreadOnly, { unreadOnly = !unreadOnly }, label = { Text("Unread only") })
            Spacer(Modifier.weight(1f)); TextButton(onClick = { scope.launch { try { mark() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }, enabled = unread > 0) { Text("Mark all read") }
        }
        error?.let { FeatureError(it) { reload++ } }; if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        } }
            if (notifications.isEmpty() && !loading && error == null) item { FeatureEmpty("A quiet moment", "Messages, replies, reactions, and shared files will appear here.") }
            items(notifications, key = { it.text("id") }) { notification ->
                Card(onClick = { selected = notification; if (!notification.flag("isRead")) scope.launch { try { mark(listOf(notification.text("id"))) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } },
                    colors = CardDefaults.cardColors(containerColor = if (notification.flag("isRead")) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.secondaryContainer), shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(when { notification.text("type").startsWith("CHAT") -> Icons.Outlined.ChatBubbleOutline; notification.text("type").contains("REACTION") -> Icons.Outlined.FavoriteBorder; notification.text("type").contains("SHARE") -> Icons.Outlined.FolderShared; else -> Icons.Outlined.NotificationsNone }, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(notification.text("title"), style = MaterialTheme.typography.titleMedium); Text(notification.text("body"), style = MaterialTheme.typography.bodyMedium); Text(notification.text("createdAt").replace('T', ' ').take(16), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (!notification.flag("isRead")) Badge()
                    }
                }
            }
            if (cursor != null) item { TextButton(onClick = { scope.launch { load(true) } }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Load more") } }
    }
    selected?.let { item -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(item.text("title")) }, text = { Text(item.text("body")) },
        confirmButton = { if (onNavigate != null && item.text("link").isNotBlank()) TextButton(onClick = { onNavigate(item.text("link")); selected = null }) { Text("Open") } else TextButton(onClick = { selected = null }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { scope.launch { try { val key = sessionRepo.getSessionKey(); api.request("notifications", "DELETE", api.body("sessionKey" to key, "notificationIds" to jsonStrings(listOf(item.text("id"))))).getOrThrow(); selected = null; load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }) { Text("Delete") } }) }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear all updates?") }, text = { Text("This removes the notifications in your inbox.") }, confirmButton = { TextButton(onClick = { scope.launch { try { api.request("notifications", "DELETE", api.body("sessionKey" to sessionRepo.getSessionKey(), "all" to true)).getOrThrow(); confirmClear = false; load() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }) { Text("Clear all") } }, dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

internal object NotificationDelivery {
    private const val CHANNEL = "ohiofiles_updates"
    fun enabled(context: Context) = context.getSharedPreferences("native_notifications", Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("native_notifications", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        NativeNotificationWork.schedule(context, enabled)
    }
    private fun fingerprint(notification: JsonObject) = java.security.MessageDigest.getInstance("SHA-256").digest("${notification.text("title")}|${notification.text("body")}|${notification.text("link")}".toByteArray()).joinToString("") { "%02x".format(it) }
    fun recentlyPosted(context: Context, notification: JsonObject, account: String): Boolean {
        val created = runCatching { java.time.Instant.parse(notification.text("createdAt")).toEpochMilli() }.getOrNull() ?: return false
        val posted = context.getSharedPreferences("notification_delivery", Context.MODE_PRIVATE).getLong("$account-${fingerprint(notification)}", 0)
        return posted > 0 && kotlin.math.abs(created - posted) <= 30_000
    }
    fun deliver(context: Context, notification: JsonObject, account: String? = null): Boolean {
        if (!enabled(context)) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "OhioFiles updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP; putExtra("notification_link", notification.text("link")) } ?: return false
        val pending = PendingIntent.getActivity(context, notification.text("id").hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_more).setContentTitle(notification.text("title"))
            .setContentText(notification.text("body")).setStyle(NotificationCompat.BigTextStyle().bigText(notification.text("body"))).setAutoCancel(true).setContentIntent(pending).build()
        val posted = runCatching { manager.notify(notification.text("id").hashCode(), body); true }.getOrDefault(false)
        if (posted && account != null) {
            val prefs = context.getSharedPreferences("notification_delivery", Context.MODE_PRIVATE)
            val edit = prefs.edit().putLong("$account-${fingerprint(notification)}", System.currentTimeMillis())
            prefs.all.entries.sortedByDescending { it.value as? Long ?: 0 }.drop(200).forEach { edit.remove(it.key) }
            edit.apply()
        }
        return posted
    }
}
