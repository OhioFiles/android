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
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.model.FileListResponse
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
            var ownedFiles: MutableList<FileItem>? = null
            val finished = withTimeoutOrNull(if (elevated) 6 * 60 * 60 * 1000L - 5000 else 8 * 60 * 1000L) {
            for (item in batch.items) {
                ensureActive()
                if (queue.results(batchId).any { it.itemId == item.id && it.response != null }) continue
                setProgress(workDataOf("filename" to item.filename, "progress" to 0f, "phase" to UploadPhase.PREPARING.name))
                if (item.checkDuplicates && !item.uploadAttempted && queue.results(batchId).none { it.itemId == item.id && it.duplicateDecision == "upload" }) {
                    if (ownedFiles == null) {
                        val collected = mutableListOf<FileItem>(); var page = 1
                        do {
                            val data = api.json.decodeFromJsonElement<FileListResponse>(api.request("files", query = mapOf("sessionKey" to batch.sessionKey, "page" to page.toString(), "limit" to "100")).getOrThrow())
                            collected += data.files.filter { it.isOwner }
                            if (page++ >= data.totalPages) break
                        } while (true)
                        ownedFiles = collected
                    }
                    ownedUploadDuplicate(item, ownedFiles.orEmpty())?.let { duplicate ->
                        val response = UploadResponse(id = duplicate.id, slug = duplicate.slug, filename = duplicate.displayName, url = api.getPublicFileUrl(duplicate.slug), isPublic = duplicate.isPublic, isOneTime = duplicate.isOneTime)
                        queue.record(batchId, DurableUploadResult(item.id, error = "The same content is already in your files", duplicate = response))
                        throw DuplicateUploadChoice()
                    }
                }
                if (elevated) setForeground(foreground(item.filename, 0))
                val options = item.options(batch)
                suspend fun lookup(slug: String): ExistingUpload? {
                    val existing = api.request("files", query = mapOf("slug" to slug, "sessionKey" to batch.sessionKey)).getOrThrow().jsonObject["files"]?.jsonArray?.firstOrNull()?.jsonObject
                    return existing?.let {
                        ExistingUpload(it["isOwner"]?.jsonPrimitive?.booleanOrNull == true, it["checksumSha256"]?.jsonPrimitive?.contentOrNull,
                            UploadResponse(id = it["id"]!!.jsonPrimitive.content, slug = slug, filename = it["filename"]?.jsonPrimitive?.content ?: item.filename,
                                url = api.getPublicFileUrl(slug), isPublic = options.isPublic, isOneTime = options.isOneTime))
                    }
                }
                var lastNotification = 0L
                var lastProgress = 0L
                var lastAcknowledged = -1
                val progressLock = Any()
                fun report(fraction: Float, finalizing: Boolean, acknowledged: Int = 0, total: Int = 0) = synchronized(progressLock) {
                    val now = System.nanoTime()
                    if (now - lastProgress > 200_000_000L || finalizing || acknowledged != lastAcknowledged) {
                    lastProgress = now; lastAcknowledged = acknowledged
                    setProgressAsync(workDataOf("filename" to item.filename, "progress" to fraction, "phase" to if (finalizing) UploadPhase.FINALIZING.name else UploadPhase.TRANSFERRING.name, "acknowledgedChunks" to acknowledged, "totalChunks" to total))
                    }
                    if (now - lastNotification > 500_000_000L || finalizing) {
                        lastNotification = now
                        val info = foreground(if (finalizing) "Finishing upload…" else item.filename, if (finalizing) -1 else (fraction * 100).toInt())
                        if (elevated) setForegroundAsync(info)
                        else if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(info.notificationId, info.notification)
                    }
                }
                val uploaded = uploadWithStableSlug(item, lookup = ::lookup,
                    persist = { replacement, previous -> queue.checkpointItem(batchId, replacement, previous) },
                    upload = { candidate ->
                        if (candidate.size > 0) uploadChunked(candidate, options, batch.sessionKey, api,
                            load = { queue.chunk(batchId, candidate.id) }, save = { queue.checkpointChunk(batchId, candidate.id, it) },
                            recover = { lookup(candidate.slug)?.takeIf { it.isOwner && it.checksum == candidate.checksum }?.response },
                            progress = { report(it.fraction, it.finalizing || it.fraction >= 1f, it.acknowledged, it.total) })
                        else api.uploadFile(applicationContext, Uri.fromFile(File(candidate.path)), batch.sessionKey, candidate.filename, candidate.slug, options.folderId,
                            options.isPublic, options.isOneTime, options.password, mimeTypeOverride = candidate.mimeType) { report(it, it >= 1f) }.getOrThrow()
                    })
                ownedFiles?.add(FileItem(uploaded.finalId!!, uploaded.finalSlug!!, item.filename, size = item.size, checksumSha256 = item.checksum, isOwner = true, isPublic = uploaded.isPublic, isOneTime = uploaded.isOneTime))
                queue.record(batchId, DurableUploadResult(item.id, uploaded))
                queue.checkpointChunk(batchId, item.id, null)
                File(item.path).delete()
            }
            true
            }
            if (finished != true) return@withContext paused(batch, "Android paused the transfer at its background time limit. Open the app and retry the remaining files.")
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is DuplicateUploadChoice) return@withContext Result.failure(workDataOf("duplicate" to true))
            val transient = e is IOException || (e is ApiException && (e.status >= 500 || e.status == 429 || (e.status == 409 && e.message.orEmpty().contains("in progress", true))))
            if (transient && runAttemptCount < 6) return@withContext Result.retry()
            val message = when {
                e is ApiException && e.status == 401 -> "Session access was rejected. Your upload queue is kept. Check Session & recovery in You, then retry."
                foregroundDenied(e) -> "Android paused this transfer. Open the app and retry."
                else -> e.message ?: "Upload failed. Your remaining files are kept."
            }
            val item = batch.items.firstOrNull { candidate -> queue.results(batchId).none { it.itemId == candidate.id && it.response != null } }
            item?.let { queue.record(batchId, DurableUploadResult(it.id, error = message)) }
            Result.failure(workDataOf("error" to message.take(1000)))
        }
    }
}
