package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.app.Activity
import android.content.Context
import androidx.work.*
import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.api.OhioApiClient
import kotlinx.coroutines.*
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.serialization.json.*
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object NativePush {
    fun prefs(context: Context) = context.getSharedPreferences("native_push", Context.MODE_PRIVATE)
    fun current(context: Context) = prefs(context).getString("current", null)
    fun state(context: Context) = prefs(context).getString("state", null) ?: "Background checks are available"
    private fun instance(server: String, key: String) = "ohio-" + MessageDigest.getInstance("SHA-256").digest("$server|$key".toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    fun record(context: Context, instance: String): JsonObject? = prefs(context).getString("account_$instance", null)?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
    fun update(context: Context, instance: String, value: JsonObject) { prefs(context).edit().putString("account_$instance", value.toString()).commit() }
    fun status(context: Context, text: String) { prefs(context).edit().putString("state", text).apply() }
    fun work(context: Context, instance: String, action: String) {
        val request = OneTimeWorkRequestBuilder<NativePushWorker>().setInputData(workDataOf("instance" to instance, "action" to action))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("push-sync-$instance", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
    fun bind(context: Context, server: String, key: String?) {
        val next = key?.let { instance(server, it) }
        val previous = current(context)
        if (previous != null && previous != next) {
            runCatching { UnifiedPush.unregister(context, previous) }
            work(context, previous, "unsubscribe")
        }
        if (next == null) { prefs(context).edit().remove("current").apply(); return }
        val prior = record(context, next).orEmpty()
        update(context, next, JsonObject(prior + mapOf("server" to JsonPrimitive(server), "key" to JsonPrimitive(key))))
        prefs(context).edit().putString("current", next).commit()
        if (NotificationDelivery.enabled(context) && UnifiedPush.getSavedDistributor(context) != null) work(context, next, "register")
    }
    fun enable(activity: Activity, server: String, key: String) {
        NotificationDelivery.setEnabled(activity, true)
        bind(activity, server, key)
        status(activity, "Choose a push service to receive background updates")
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
            val id = current(activity)
            if (success && id != null) { status(activity, "Connecting push notifications…"); work(activity, id, "register") }
            else status(activity, "Push service unavailable. Background checks remain active.")
        }
    }
    fun disable(context: Context) {
        NotificationDelivery.setEnabled(context, false)
        current(context)?.let { id -> runCatching { UnifiedPush.unregister(context, id) }; work(context, id, "unsubscribe") }
        status(context, "Android notifications are off")
    }
}

class NativePushWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("instance") ?: return Result.failure()
        val action = inputData.getString("action") ?: return Result.failure()
        val account = NativePush.record(applicationContext, id) ?: return Result.success()
        val api = OhioApiClient(account.text("server")); val key = account.text("key")
        return try {
            if (action == "unsubscribe" || action == "cleanup") {
                val obsolete = NativePush.prefs(applicationContext).getStringSet("obsolete_$id", emptySet()).orEmpty()
                for (endpoint in obsolete) api.request("notifications/push/unsubscribe", "POST", api.body("sessionKey" to key, "endpoint" to endpoint)).getOrThrow()
                NativePush.prefs(applicationContext).edit().remove("obsolete_$id").apply()
                if (action == "cleanup") return Result.success()
                account.text("endpoint").takeIf(String::isNotBlank)?.let { api.request("notifications/push/unsubscribe", "POST", api.body("sessionKey" to key, "endpoint" to it)).getOrThrow() }
                if (NativePush.current(applicationContext) != id || !NotificationDelivery.enabled(applicationContext)) NativePush.prefs(applicationContext).edit().remove("account_$id").apply()
                Result.success()
            } else {
                if (NativePush.current(applicationContext) != id || !NotificationDelivery.enabled(applicationContext)) return Result.success()
                val sessions = SessionRepository(applicationContext, OhioApiClient())
                if (sessions.getSessionKey() != key || sessions.getServerUrl() != account.text("server")) { NativePush.work(applicationContext, id, "unsubscribe"); return Result.success() }
                if (action == "register") {
                    val publicKey = api.request("notifications/push/vapid-public-key").getOrThrow().jsonObject.text("publicKey").trimEnd('=')
                    require(publicKey.isNotBlank()) { "This server has no push key" }
                    UnifiedPush.register(applicationContext, instance = id, messageForDistributor = "OhioFiles updates", vapid = publicKey)
                } else if (action == "subscribe") {
                    api.request("notifications/push/subscribe", "POST", api.body("sessionKey" to key, "subscription" to api.body("endpoint" to account.text("endpoint"), "keys" to api.body("p256dh" to account.text("p256dh"), "auth" to account.text("auth"))))).getOrThrow()
                    if (NativePush.current(applicationContext) == id && NotificationDelivery.enabled(applicationContext)) NativePush.status(applicationContext, "Push connected · background checks are also available") else NativePush.work(applicationContext, id, "unsubscribe")
                }
                Result.success()
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (action == "unsubscribe" && e is ApiException && e.status in listOf(401, 403, 410)) { NativePush.prefs(applicationContext).edit().remove("account_$id").remove("obsolete_$id").apply(); return Result.success() }
            if (NativePush.current(applicationContext) == id && action != "unsubscribe") NativePush.status(applicationContext, "Push couldn't connect. Background checks remain active; retry when connected.")
            if (e is ApiException && e.status in listOf(400, 401, 403, 410)) Result.failure() else Result.retry()
        }
    }
}

class OhioPushService : PushService() {
    private val deliveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onDestroy() { deliveryScope.cancel(); super.onDestroy() }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val account = NativePush.record(this, instance) ?: return
        if (NativePush.current(this) != instance || !NotificationDelivery.enabled(this)) { runCatching { UnifiedPush.unregister(this, instance) }; return }
        val keys = endpoint.pubKeySet ?: run { NativePush.status(this, "This push service doesn't support encrypted updates. Background checks remain active."); return }
        val previous = account.text("endpoint")
        if (previous.isNotBlank() && previous != endpoint.url) {
            val prefs = NativePush.prefs(this)
            prefs.edit().putStringSet("obsolete_$instance", prefs.getStringSet("obsolete_$instance", emptySet()).orEmpty() + previous).commit()
            NativePush.work(this, instance, "cleanup")
        }
        NativePush.update(this, instance, JsonObject(account + mapOf("endpoint" to JsonPrimitive(endpoint.url), "p256dh" to JsonPrimitive(keys.pubKey), "auth" to JsonPrimitive(keys.auth))))
        NativePush.work(this, instance, "subscribe")
    }
    override fun onMessage(message: PushMessage, instance: String) {
        if (NativePush.current(this) != instance || !NotificationDelivery.enabled(this)) return
        if (!message.decrypted) { NativePush.status(this, "An encrypted update couldn't be read. Refresh push registration."); return }
        val value = runCatching { Json.parseToJsonElement(message.content.decodeToString()).jsonObject }.getOrNull() ?: return
        val notification = buildJsonObject {
            put("id", value.text("tag").ifBlank { message.content.contentHashCode().toString() })
            put("title", value.text("title").ifBlank { "OhioFiles" }); put("body", value.text("body")); put("link", (value["data"] as? JsonObject)?.text("url")?.ifBlank { null } ?: value.text("link").ifBlank { "/notifications" })
        }
        val account = NativePush.record(this, instance) ?: return
        // Connector keeps this local service bound while callbacks run; the identity check stays off the UI thread.
        deliveryScope.launch {
            val sessions = SessionRepository(applicationContext, OhioApiClient())
            if (sessions.getSessionKey() != account.text("key") || sessions.getServerUrl() != account.text("server") || NativePush.current(applicationContext) != instance) return@launch
            val scope = notificationAccount(account.text("server"), account.text("key"))
            if (NotificationDelivery.deliver(applicationContext, notification, scope)) NativePush.prefs(applicationContext).edit().putLong("last_delivery", System.currentTimeMillis()).apply()
        }
    }
    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        if (NativePush.current(this) == instance && NotificationDelivery.enabled(this)) NativePush.status(this, if (reason == FailedReason.ACTION_REQUIRED) "Open your push service to finish setup. Background checks remain active." else "Push is temporarily unavailable. Background checks remain active.")
    }
    override fun onUnregistered(instance: String) {
        if (NativePush.current(this) == instance && NotificationDelivery.enabled(this)) NativePush.status(this, "Push is disconnected. Background checks remain active.")
    }
}
