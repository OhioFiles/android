package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.content.Context
import androidx.work.*
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun notificationAccount(server: String, key: String): String = MessageDigest.getInstance("SHA-256").digest("$server|$key".toByteArray()).joinToString("") { "%02x".format(it) }

class NativeNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!NotificationDelivery.enabled(applicationContext)) return Result.success()
        val api = OhioApiClient(); val sessions = SessionRepository(applicationContext, api)
        val key = sessions.getSessionKey() ?: return Result.success()
        return try {
            api.setBaseUrl(sessions.getServerUrl())
            val data = api.request("notifications", query = mapOf("sessionKey" to key, "unreadOnly" to "true", "limit" to "20")).getOrThrow().jsonObject
            val prefs = applicationContext.getSharedPreferences("native_notifications", Context.MODE_PRIVATE)
            val account = notificationAccount(api.getBaseUrl(), key)
            val previous = prefs.getStringSet("delivered_$account", emptySet()).orEmpty()
            val items = data.objects("notifications")
            val delivered = items.filter { it.text("id") !in previous }.take(5).filter { NotificationDelivery.recentlyPosted(applicationContext, it, account) || NotificationDelivery.deliver(applicationContext, it, account) }.map { it.text("id") }
            prefs.edit().putStringSet("delivered_$account", (previous + delivered).toList().takeLast(200).toSet()).apply()
            Result.success()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
    }
}

internal object NativeNotificationWork {
    fun schedule(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork("ohiofiles-notifications"); return }
        val work = PeriodicWorkRequestBuilder<NativeNotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        manager.enqueueUniquePeriodicWork("ohiofiles-notifications", ExistingPeriodicWorkPolicy.KEEP, work)
    }
}
