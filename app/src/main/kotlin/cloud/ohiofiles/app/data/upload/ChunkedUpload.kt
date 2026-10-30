package cloud.ohiofiles.app.data.upload

import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.UploadResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException

@Serializable
data class UploadItemOptions(val folderId: String? = null, val isPublic: Boolean = false, val isOneTime: Boolean = false, val password: String? = null)
@Serializable
data class ChunkSession(val uploadId: String, val slug: String, val chunkSize: Int, val totalChunks: Int, val acknowledged: Set<Int> = emptySet())
internal data class ChunkProgress(val fraction: Float, val acknowledged: Int, val total: Int, val finalizing: Boolean = false)
internal fun DurableUploadItem.options(batch: DurableUploadBatch) = options ?: UploadItemOptions(batch.folderId, batch.isPublic, batch.isOneTime, batch.password)

internal suspend fun uploadChunked(
    item: DurableUploadItem, options: UploadItemOptions, key: String, api: OhioApiClient,
    load: () -> ChunkSession?, save: (ChunkSession?) -> Unit,
    recover: suspend () -> UploadResponse?, progress: (ChunkProgress) -> Unit
): UploadResponse = withContext(Dispatchers.IO) {
    val file = File(item.path)
    check(file.canRead() && file.length() == item.size) { "The staged file changed or is unavailable. Choose it again." }
    var resets = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        var session = load()
        if (session != null && session.slug != item.slug) {
            api.abortChunkUpload(session.uploadId, key).getOrThrow()
            save(null); session = null
        }
        if (session == null) {
            val initFields = api.body("filename" to item.filename, "customFilename" to item.filename,
                "sizeBytes" to item.size, "mimeType" to item.mimeType, "chunkSize" to 5 * 1024 * 1024,
                "sessionId" to key, "folderId" to options.folderId, "customSlug" to item.slug, "checksumSha256" to item.checksum,
                "isPublic" to options.isPublic, "isOneTime" to options.isOneTime)
            val payload = JsonObject(initFields + listOfNotNull(options.password?.let { "password" to JsonPrimitive(it) }).toMap())
            val initialized = api.request("upload/chunks/init", "POST", payload).getOrThrow().jsonObject
            session = ChunkSession(initialized["uploadId"]!!.jsonPrimitive.content, item.slug, initialized["chunkSize"]!!.jsonPrimitive.int, initialized["totalChunks"]!!.jsonPrimitive.int)
            require(session.chunkSize in 5 * 1024 * 1024..15 * 1024 * 1024 && session.totalChunks == ((item.size + session.chunkSize - 1) / session.chunkSize).toInt()) { "The server returned invalid chunk settings" }
            save(session)
        }
        val initial = checkNotNull(session)
        try {
            var current = initial
            val mutex = Mutex()
            val inFlight = mutableMapOf<Int, Long>()
            val progressLock = Any()
            fun report() = synchronized(progressLock) {
                val acknowledgedBytes = current.acknowledged.sumOf { index -> minOf(current.chunkSize.toLong(), item.size - index.toLong() * current.chunkSize) }
                val transmitting = inFlight.filterKeys { it !in current.acknowledged }.values.sum()
                progress(ChunkProgress(((acknowledgedBytes + transmitting).toDouble() / item.size).coerceIn(0.0, 1.0).toFloat(), current.acknowledged.size, current.totalChunks))
            }
            report()
            coroutineScope {
                val permits = Semaphore(2)
                (0 until initial.totalChunks).filter { it !in initial.acknowledged }.map { index -> async {
                    permits.withPermit {
                        val offset = index.toLong() * initial.chunkSize
                        val length = minOf(initial.chunkSize.toLong(), item.size - offset)
                        api.appendUploadChunk(initial.uploadId, index, file, offset, length, key) { sent -> synchronized(progressLock) { inFlight[index] = sent; report() } }.getOrThrow()
                        mutex.withLock {
                            val next = current.copy(acknowledged = current.acknowledged + index)
                            save(next) // Persist each acknowledgement before reporting it as durable progress.
                            synchronized(progressLock) { current = next; inFlight.remove(index); report() }
                        }
                    }
                } }.awaitAll()
            }
            progress(ChunkProgress(1f, current.acknowledged.size, current.totalChunks, finalizing = true))
            return@withContext api.finalizeChunkUpload(initial.uploadId, key).getOrThrow()
        } catch (e: CancellationException) { throw e }
        catch (e: ApiException) {
            if (e.status !in listOf(404, 410) || resets++ >= 2) throw e
            recover()?.let { return@withContext it }
            save(null) // Only an authoritative expiry can abandon an acknowledged chunk session.
        }
    }
    @Suppress("UNREACHABLE_CODE") error("Upload did not finish")
}
