package cloud.ohiofiles.app.data.upload

import android.content.Context
import android.net.Uri
import androidx.work.*
import cloud.ohiofiles.app.data.model.UploadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
data class DurableUploadItem(val id: String, val path: String, val filename: String, val size: Long, val checksum: String, val slug: String, val mimeType: String = "application/octet-stream")
@Serializable
data class DurableUploadBatch(val id: String, val server: String, val sessionKey: String, val items: List<DurableUploadItem>,
    val folderId: String? = null, val isPublic: Boolean = false, val isOneTime: Boolean = false, val password: String? = null)
@Serializable
data class DurableUploadResult(val itemId: String, val response: UploadResponse? = null, val error: String? = null)

class DurableUploadQueue(private val context: Context) {
    companion object { private val pointerLock = Any() }
    private val root = File(context.filesDir, "upload-queue").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("durable_upload_queue", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private fun scope(server: String, key: String) = MessageDigest.getInstance("SHA-256").digest("$server|$key".toByteArray()).joinToString("") { "%02x".format(it) }
    fun active(server: String, key: String): String? = prefs.getString(scope(server, key), null)
    fun forget(server: String, key: String, batchId: String? = null) = synchronized(pointerLock) {
        val entry = scope(server, key)
        if (batchId == null || prefs.getString(entry, null) == batchId) prefs.edit().remove(entry).commit()
        Unit
    }
    fun read(id: String): DurableUploadBatch? = runCatching { json.decodeFromString<DurableUploadBatch>(File(root, "$id.json").readText()) }.getOrNull()
    @Synchronized fun results(id: String): List<DurableUploadResult> = runCatching { json.decodeFromString<List<DurableUploadResult>>(File(root, "$id.results.json").readText()) }.getOrDefault(emptyList())
    @Synchronized fun record(id: String, result: DurableUploadResult) {
        val values = results(id).filterNot { it.itemId == result.itemId } + result
        atomic(File(root, "$id.results.json"), json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DurableUploadResult.serializer()), values))
    }
    private fun atomic(file: File, content: String) {
        val pending = File(file.parentFile, file.name + ".pending")
        pending.outputStream().use { output -> output.write(content.toByteArray()); output.fd.sync() }
        check(pending.renameTo(file)) { "Could not persist the upload queue" }
    }
    suspend fun prepare(server: String, key: String, sources: List<Triple<Uri, String, String?>>, folderId: String?, isPublic: Boolean, isOneTime: Boolean, password: String?): DurableUploadBatch = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val directory = File(root, id).apply { mkdirs() }
        try {
            val items = sources.mapIndexed { index, (uri, filename, slug) ->
                currentCoroutineContext().ensureActive()
                val itemId = UUID.randomUUID().toString()
                val file = File(directory, "$index.data")
                val digest = MessageDigest.getInstance("SHA-256")
                val input = if (uri.scheme == "file") File(uri.path ?: error("Missing source path")).inputStream() else context.contentResolver.openInputStream(uri)
                checkNotNull(input) { "Cannot open $filename" }.use { source -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { currentCoroutineContext().ensureActive(); val count = source.read(buffer); if (count < 0) break; output.write(buffer, 0, count); digest.update(buffer, 0, count) }
                    output.fd.sync()
                } }
                DurableUploadItem(itemId, file.absolutePath, filename, file.length(), digest.digest().joinToString("") { "%02x".format(it) }, slug ?: UUID.randomUUID().toString().replace("-", ""),
                    runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream")
            }
            val batch = DurableUploadBatch(id, server, key, items, folderId, isPublic, isOneTime, password)
            atomic(File(root, "$id.json"), json.encodeToString(DurableUploadBatch.serializer(), batch))
            synchronized(pointerLock) { prefs.edit().putString(scope(server, key), id).commit() }
            batch
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    fun enqueue(batch: DurableUploadBatch): UUID {
        val work = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("batchId" to batch.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-${batch.id}", ExistingWorkPolicy.REPLACE, work)
        prefs.edit().putString("work-${batch.id}", work.id.toString()).commit()
        return work.id
    }
    fun workId(id: String): UUID? = prefs.getString("work-$id", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    suspend fun discard(batch: DurableUploadBatch) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork("upload-${batch.id}").result.get()
        forget(batch.server, batch.sessionKey, batch.id)
        File(root, batch.id).deleteRecursively()
        File(root, "${batch.id}.json").delete()
        File(root, "${batch.id}.results.json").delete()
    }
    fun cancel(id: String) { WorkManager.getInstance(context).cancelUniqueWork("upload-$id") }
}
