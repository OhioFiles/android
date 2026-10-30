package cloud.ohiofiles.app.data.api

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import cloud.ohiofiles.app.data.model.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, message: String) : Exception(message)

class OhioApiClient(private var baseUrl: String = "https://api.ohiofiles.cloud") {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val accessTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val folderTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun rememberFolderAccess(fileIds: List<String>, token: String?) { if (token != null) fileIds.forEach { folderTokens[it] = token } }
    private val oneTimeTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun setBaseUrl(newUrl: String) { require(newUrl.toHttpUrl().isHttps) { "Use an HTTPS server address" }; baseUrl = newUrl.trimEnd('/') }
    fun getBaseUrl() = baseUrl
    fun url(path: String, query: Map<String, String?> = emptyMap()): String = baseUrl.toHttpUrl().newBuilder().apply {
        path.trim('/').split('/').filter(String::isNotEmpty).forEach(::addPathSegment)
        query.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) }
    }.build().toString()
    fun getFileDownloadUrl(fileId: String, sessionKey: String? = null, slug: String = fileId): String = url("files/$fileId", mapOf(
        "sessionKey" to sessionKey, "fileAccessToken" to accessTokens[slug], "oneTimeToken" to oneTimeTokens[slug], "folderAccessToken" to folderTokens[fileId]))
    fun restoreFileAccess(fileId: String, slug: String, downloadUrl: String) {
        runCatching { downloadUrl.toHttpUrl() }.getOrNull()?.let { url ->
            url.queryParameter("oneTimeToken")?.let { oneTimeTokens[slug] = it }
            url.queryParameter("fileAccessToken")?.let { accessTokens[slug] = it }
            url.queryParameter("folderAccessToken")?.let { folderTokens[fileId] = it }
        }
    }
    fun getPublicFileUrl(slug: String) = "https://ohiofiles.cloud/" + Uri.encode(slug)
    fun getPublicFolderUrl(folder: FolderItem) = "https://ohiofiles.cloud/f/" + Uri.encode(folder.slug ?: folder.id)
    fun body(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
        values.forEach { (key, value) -> put(key, when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }) }
    }
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
    suspend fun request(path: String, method: String = "GET", payload: JsonObject? = null, query: Map<String, String?> = emptyMap(), headers: Map<String, String> = emptyMap()): Result<JsonElement> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url(path, query)).apply { headers.forEach { (name, value) -> header(name, value) } }.method(method,
                if (method == "GET" || method == "HEAD") null else (payload ?: body()).toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).awaitResponse().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(ApiException(response.code, apiErrorMessage(text, "Request failed (${response.code})")))
                }
                Result.success(if (text.isBlank()) body("success" to true) else json.parseToJsonElement(text))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    private inline fun <reified T> Result<JsonElement>.decode(): Result<T> = mapCatching { json.decodeFromJsonElement<T>(it) }
    suspend fun createSession(keyType: String = "secure"): Result<PublicSession> = request("public-sessions", "POST", body("keyType" to keyType)).decode<PublicSessionResponse>().map { it.session }
    suspend fun getSession(key: String): Result<PublicSession> = request("public-sessions/$key").decode<PublicSessionResponse>().map { it.session }
    suspend fun updateNickname(key: String, nickname: String): Result<PublicSession> = request("public-sessions/$key/nickname", "POST", body("nickname" to nickname)).decode<PublicSessionResponse>().map { it.session }
    suspend fun revokeSession(key: String): Result<Boolean> = request("public-sessions/$key/revoke", "POST").map { true }
    suspend fun renewSession(key: String): Result<Boolean> = request("public-sessions/$key/renew", "POST").map { true }
    suspend fun listFiles(sessionKey: String?, folderId: String? = null, search: String? = null, isPublic: Boolean = false, page: Int = 1, limit: Int = 100): Result<FileListResponse> = request("files", query = mapOf(
        "sessionKey" to sessionKey, "folderId" to if (isPublic) folderId else folderId ?: "root", "public" to if (isPublic) "true" else null,
        "page" to page.toString(), "limit" to limit.toString())).decode()
    suspend fun resolveFile(reference: String, key: String?): Result<FileItem> {
        val linked = request("files", query = mapOf("slug" to reference, "sessionKey" to key)).decode<FileListResponse>()
        if (linked.isFailure) return Result.failure(linked.exceptionOrNull()!!)
        linked.getOrThrow().files.firstOrNull()?.let { return Result.success(it) }
        if (key != null) {
            var page = 1
            do {
                val result = request("files", query = mapOf("sessionKey" to key, "page" to page.toString(), "limit" to "100")).decode<FileListResponse>()
                if (result.isFailure) return Result.failure(result.exceptionOrNull()!!)
                val data = result.getOrThrow()
                data.files.firstOrNull { it.id == reference || it.slug == reference }?.let { return Result.success(it) }
                if (page++ >= data.totalPages) break
            } while (true)
        }
        return Result.failure(IllegalArgumentException("This file is unavailable or you don't have access"))
    }
    suspend fun listFolders(sessionKey: String?, parentId: String? = null): Result<FolderListResponse> = request("folders", query = mapOf(
        "sessionKey" to sessionKey, "parentId" to (parentId ?: "root"), "includeStats" to "true", "includePath" to "true")).decode()
    suspend fun createFolder(sessionKey: String, name: String, parentId: String? = null, isPublic: Boolean = false, password: String? = null): Result<FolderItem> = request("folders", "POST", body(
        "sessionKey" to sessionKey, "name" to name, "parentId" to parentId, "isPublic" to isPublic, "password" to password,
        "folderType" to "NORMAL", "defaultAccess" to "NONE")).decode<FolderCreateResponse>().mapCatching { it.folder ?: error("Missing folder") }
    suspend fun updateFolder(id: String, key: String, name: String? = null, parentId: String? = null, move: Boolean = false): Result<Boolean> = request("folders/$id", "PATCH", buildJsonObject {
        put("sessionKey", key); name?.let { put("name", it) }; if (move) put("parentId", parentId?.let(::JsonPrimitive) ?: JsonNull)
    }).map { true }
    suspend fun updateFile(id: String, key: String, name: String? = null, folderId: String? = null, move: Boolean = false, isPublic: Boolean? = null): Result<Boolean> = request("files/$id", "PATCH", buildJsonObject {
        name?.let { put("name", it) }; if (move) put("folderId", folderId?.let(::JsonPrimitive) ?: JsonNull); isPublic?.let { put("isPublic", it) }
    }, mapOf("sessionKey" to key)).map { true }
    suspend fun configureFolder(id: String?, key: String, properties: JsonObject): Result<JsonElement> = request(if (id == null) "folders" else "folders/$id", if (id == null) "POST" else "PATCH", JsonObject(properties + ("sessionKey" to JsonPrimitive(key))))
    suspend fun moveFiles(ids: List<String>, destination: String?, key: String): Result<JsonElement> = request("files/batch-move", "POST", body("sessionKey" to key, "fileIds" to JsonArray(ids.map(::JsonPrimitive)), "targetFolderId" to destination))
    suspend fun exportSelectedFiles(context: Context, destination: Uri, ids: List<String>, key: String): Result<Long> = withContext(Dispatchers.IO) {
        try {
            require(ids.size in 1..500) { "Select between 1 and 500 files" }
            val payload = body("sessionKey" to key, "fileIds" to JsonArray(ids.map(::JsonPrimitive)))
            client.newCall(Request.Builder().url(url("files/batch-download-zip")).post(payload.toString().toRequestBody("application/json".toMediaType())).build()).awaitResponse().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(ApiException(response.code, apiErrorMessage(response.body?.string().orEmpty(), "ZIP export failed (${response.code})")))
                val input = response.body?.byteStream() ?: error("The server returned no archive")
                val output = context.contentResolver.openOutputStream(destination, "wt") ?: error("Could not open the selected destination")
                var bytes = 0L
                output.use { sink -> input.use { source -> val buffer = ByteArray(64 * 1024); while (true) { currentCoroutineContext().ensureActive(); val count = source.read(buffer); if (count < 0) break; sink.write(buffer, 0, count); bytes += count } } }
                Result.success(bytes)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun deleteFolder(id: String, key: String) = request("folders/$id", "DELETE", query = mapOf("sessionKey" to key)).map { true }
    suspend fun undoDeleteFolder(id: String, key: String) = request("folders/$id/undo-delete", "POST", query = mapOf("sessionKey" to key)).map { true }
    suspend fun deleteFile(slug: String, key: String) = request("files/$slug", "DELETE", query = mapOf("sessionKey" to key)).map { true }
    suspend fun undoDeleteFile(id: String, key: String) = request("files/$id/undo-delete", "POST", query = mapOf("sessionKey" to key)).map { true }
    suspend fun saveBookmark(slug: String, key: String) = request("files/$slug/save", "POST", body("sessionKey" to key)).map { true }
    suspend fun removeBookmark(slug: String, key: String) = request("files/$slug/save", "DELETE", query = mapOf("sessionKey" to key)).map { true }
    suspend fun unlockPassword(slug: String, password: String): Result<Boolean> = request("files/$slug/access", "POST", body("password" to password)).mapCatching {
        accessTokens[slug] = it.jsonObject["token"]?.jsonPrimitive?.content ?: error("Access token missing"); true
    }
    suspend fun claimOneTime(slug: String, key: String?): Result<Boolean> = request("files/$slug/one-time-view", "POST", body("sessionKey" to key, "fileAccessToken" to accessTokens[slug])).mapCatching {
        oneTimeTokens[slug] = it.jsonObject["token"]?.jsonPrimitive?.content ?: error("View token missing"); true
    }
    suspend fun releaseOneTime(slug: String) { oneTimeTokens.remove(slug)?.let { request("files/$slug/one-time-release", "POST", body("token" to it)) } }
    internal suspend fun sendUpload(multipart: MultipartBody): Result<UploadResponse> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url("upload")).post(multipart).build()).awaitResponse().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@withContext Result.failure(ApiException(response.code, apiErrorMessage(text, "Upload failed (${response.code})")))
                val uploaded = json.decodeFromString<UploadResponse>(text)
                if (uploaded.finalId.isNullOrBlank() || uploaded.finalSlug.isNullOrBlank()) {
                    return@withContext Result.failure(java.io.IOException("The server did not confirm the upload. Retry to check the saved link."))
                }
                Result.success(uploaded.copy(id = uploaded.finalId, slug = uploaded.finalSlug, filename = uploaded.finalFilename, url = uploaded.finalUrl))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun appendUploadChunk(uploadId: String, index: Int, file: File, offset: Long, length: Long, key: String, progress: (Long) -> Unit = {}): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val transferJob = currentCoroutineContext().job
            val range = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = length
                override fun isOneShot() = true
                override fun writeTo(sink: okio.BufferedSink) {
                    FileInputStream(file).use { input ->
                        input.channel.position(offset); val buffer = ByteArray(64 * 1024); var remaining = length
                        while (remaining > 0) { transferJob.ensureActive(); val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); if (count < 0) throw java.io.EOFException("The staged file ended early"); sink.write(buffer, 0, count); remaining -= count; progress(length - remaining) }
                    }
                }
            }
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("uploadId", uploadId).addFormDataPart("chunkIndex", index.toString()).addFormDataPart("file", "chunk-$index", range).build()
            client.newCall(Request.Builder().url(url("upload/chunks/append")).header("x-session-key", key).post(multipart).build()).awaitResponse().use { response ->
                if (!response.isSuccessful) Result.failure(ApiException(response.code, apiErrorMessage(response.body?.string().orEmpty(), "Chunk transfer failed (${response.code})"))) else Result.success(Unit)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    suspend fun finalizeChunkUpload(uploadId: String, key: String): Result<UploadResponse> = request("upload/chunks/finalize", "POST", body("uploadId" to uploadId), headers = mapOf("x-session-key" to key)).mapCatching {
        val uploaded = json.decodeFromJsonElement<UploadResponse>(it)
        if (uploaded.finalId.isNullOrBlank() || uploaded.finalSlug.isNullOrBlank()) throw java.io.IOException("The server did not confirm the upload. Retry to check the saved link.")
        uploaded.copy(id = uploaded.finalId, slug = uploaded.finalSlug, filename = uploaded.finalFilename, url = uploaded.finalUrl)
    }
    suspend fun abortChunkUpload(uploadId: String, key: String): Result<Unit> = request("upload/chunks/abort", "POST", body("uploadId" to uploadId), headers = mapOf("x-session-key" to key)).map { Unit }
    suspend fun uploadText(sessionKey: String, filename: String, content: String, folderId: String? = null): Result<FileItem> = withContext(Dispatchers.IO) {
        val multipart = uploadMultipart(sessionKey, filename, content.toRequestBody("text/plain; charset=utf-8".toMediaType()), folderId = folderId)
        sendUpload(multipart).map { uploaded -> FileItem(id = uploaded.finalId!!, slug = uploaded.finalSlug!!, filename = filename,
            mimeType = if (filename.endsWith(".md", true)) "text/markdown" else "text/plain", size = content.toByteArray().size.toLong(), folderId = folderId, isOwner = true) }
    }
    suspend fun uploadFile(
        context: Context,
        uri: Uri,
        sessionKey: String,
        customFilename: String? = null,
        customSlug: String? = null,
        folderId: String? = null,
        isPublic: Boolean = false,
        isOneTime: Boolean = false,
        password: String? = null,
        mimeTypeOverride: String? = null,
        onProgress: (progress: Float) -> Unit = {}
    ): Result<UploadResponse> = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            var originalName = "upload_file"
            var size = 0L

            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists()) {
                    originalName = f.name
                    size = f.length()
                }
            } else {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx != -1) originalName = cursor.getString(nameIdx) ?: originalName
                        if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                    }
                }
            }

            val mimeType = mimeTypeOverride ?: if (uri.scheme == "file") {
                val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                if (!ext.isNullOrBlank()) MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
                else "application/octet-stream"
            } else {
                contentResolver.getType(uri) ?: "application/octet-stream"
            }

            // Copy to temp file for streaming upload
            val temporary = uri.scheme != "file"
            val tempFile = if (temporary) File.createTempFile("ohio_up_", ".upload", context.cacheDir) else File(uri.path ?: error("Missing file path"))
            try {
                if (temporary) {
                val inputStream = if (uri.scheme == "file") {
                    FileInputStream(File(uri.path ?: ""))
                } else {
                    contentResolver.openInputStream(uri)
                }
                checkNotNull(inputStream) { "The selected file cannot be opened" }.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                }
                val actualSize = tempFile.length()
                val fileRequestBody = tempFile.asRequestBody(mimeType.toMediaType())
                val countingBody = CountingRequestBody(fileRequestBody) { written, total ->
                    val fraction = if (total > 0) written.toFloat() / total.toFloat() else 0f
                    onProgress(fraction)
                }

                val multipart = uploadMultipart(sessionKey, customFilename ?: originalName, countingBody,
                    isPublic, isOneTime, customSlug, customFilename, folderId, password)
                sendUpload(multipart)
            } finally {
                if (temporary) tempFile.delete()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listPublicFeed(sessionKey: String? = null, tag: String? = null, query: String? = null): Result<List<SocialPost>> = request("social/posts", query = mapOf("sessionKey" to sessionKey, "tag" to tag, "query" to query)).decode<SocialPostsResponse>().map { it.posts }
    suspend fun reactToPost(id: String, key: String, reaction: String?) = request("social/posts/$id/reaction", "PUT", body("sessionKey" to key, "reaction" to reaction)).map { true }
    suspend fun repostPost(id: String, key: String, reposted: Boolean) = request("social/posts/$id/repost", "PUT", body("sessionKey" to key, "reposted" to reposted)).map { true }
    suspend fun createPost(key: String, title: String, content: String, tags: List<String> = emptyList()): Result<SocialPost> = request("social/posts", "POST", body("sessionKey" to key, "title" to title, "content" to content, "format" to "MARKDOWN", "tags" to JsonArray(tags.map(::JsonPrimitive)))).decode<CreatePostResponse>().map { it.post }
}
