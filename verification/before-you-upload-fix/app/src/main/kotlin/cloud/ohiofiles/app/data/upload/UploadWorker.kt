package cloud.ohiofiles.app.data.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import cloud.ohiofiles.app.MainActivity
import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.UploadResponse
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException

class UploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val queue = DurableUploadQueue(context)
    private fun foreground(name: String, percent: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("uploads", "File uploads", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(applicationContext, 100, Intent(applicationContext, MainActivity::class.java).putExtra("show_uploads", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "uploads").setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading to OhioFiles").setContentText(name).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .addAction(android.R.drawable.ic_delete, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo((id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo((id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1), notification)
    }
    private fun foregroundDenied(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(12).any {
        it.javaClass.name.endsWith("ForegroundServiceStartNotAllowedException") || it.javaClass.name.endsWith("BackgroundServiceStartNotAllowedException") ||
            (it.javaClass.name.endsWith("ServiceStartNotAllowedException") && it.message.orEmpty().contains("startForeground"))
    }
    private fun paused(batch: DurableUploadBatch, message: String): Result {
        batch.items.firstOrNull { item -> queue.results(batch.id).none { it.itemId == item.id && it.response != null } }?.let {
            queue.record(batch.id, DurableUploadResult(it.id, error = message))
        }
        return Result.failure(workDataOf("error" to message))
    }
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Preparing your files", -1)
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val batchId = inputData.getString("batchId") ?: return@withContext Result.failure()
        val batch = queue.read(batchId) ?: return@withContext Result.failure()
        val api = OhioApiClient(batch.server)
        try {
            var elevated = false
            try { setForeground(foreground("Preparing your files", -1)); elevated = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!foregroundDenied(e)) throw e }
            if (!elevated && batch.items.any { it.size > 32L * 1024 * 1024 && queue.results(batch.id).none { result -> result.itemId == it.id && result.response != null } }) {
                return@withContext paused(batch, "Android paused this large upload. Open OhioFiles and retry. Completed files are kept.")
            }
            val finished = withTimeoutOrNull(if (elevated) 6 * 60 * 60 * 1000L - 5000 else 8 * 60 * 1000L) {
            for (item in batch.items) {
                ensureActive()
                if (queue.results(batchId).any { it.itemId == item.id && it.response != null }) continue
                setProgress(workDataOf("filename" to item.filename, "progress" to 0f))
                if (elevated) setForeground(foreground(item.filename, 0))
                // Stable slug + checksum reconciliation makes retries safe after a lost HTTP response.
                val existing = api.request("files", query = mapOf("slug" to item.slug, "sessionKey" to batch.sessionKey)).getOrThrow()
                    .jsonObject["files"]?.jsonArray?.firstOrNull()?.jsonObject
                val uploaded = if (existing != null) {
                    check(existing["isOwner"]?.jsonPrimitive?.booleanOrNull == true && existing["checksumSha256"]?.jsonPrimitive?.content == item.checksum) { "This link is already used by another file" }
                    UploadResponse(id = existing["id"]!!.jsonPrimitive.content, slug = item.slug, filename = existing["filename"]?.jsonPrimitive?.content ?: item.filename,
                        url = api.getPublicFileUrl(item.slug), isPublic = batch.isPublic, isOneTime = batch.isOneTime)
                } else {
                    check(File(item.path).canRead()) { "The staged file is unavailable. Choose it again." }
                    var lastNotification = 0L
                    api.uploadFile(applicationContext, Uri.fromFile(File(item.path)), batch.sessionKey, item.filename, item.slug, batch.folderId,
                        batch.isPublic, batch.isOneTime, batch.password, mimeTypeOverride = item.mimeType) { progress ->
                        setProgressAsync(workDataOf("filename" to item.filename, "progress" to progress))
                        val now = System.nanoTime()
                        if (now - lastNotification > 500_000_000L || progress >= 1f) {
                            lastNotification = now
                            if (elevated) setForegroundAsync(foreground(item.filename, (progress * 100).toInt()))
                            else if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                val info = foreground(item.filename, (progress * 100).toInt())
                                (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(info.notificationId, info.notification)
                            }
                        }
                    }.getOrThrow()
                }
                queue.record(batchId, DurableUploadResult(item.id, uploaded))
                File(item.path).delete()
            }
            true
            }
            if (finished != true) return@withContext paused(batch, "Android paused the transfer at its background time limit. Open the app and retry the remaining files.")
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val transient = e is IOException || (e is ApiException && (e.status >= 500 || e.status == 429))
            if (transient && runAttemptCount < 6) return@withContext Result.retry()
            val item = batch.items.firstOrNull { candidate -> queue.results(batchId).none { it.itemId == candidate.id && it.response != null } }
            item?.let { queue.record(batchId, DurableUploadResult(it.id, error = if (foregroundDenied(e)) "Android paused this transfer. Open the app and retry." else e.message ?: "Upload failed")) }
            Result.failure(workDataOf("error" to (if (foregroundDenied(e)) "Android paused this transfer. Open the app and retry." else e.message ?: "Upload failed").take(1000)))
        }
    }
}
