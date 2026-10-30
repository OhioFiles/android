package cloud.ohiofiles.app.ui.preview

import android.content.Context
import kotlinx.coroutines.*
import okhttp3.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resumeWithException

object PreviewIO {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private suspend fun response(url: String): Response {
        require(url.startsWith("https://")) { "Preview requires an HTTPS address" }
        val call = client.newCall(Request.Builder().url(url).build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, resource, _ -> resource.close() } }
            })
        }
    }
    suspend fun bytes(url: String, limit: Int = 2 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        response(url).use { response ->
            check(response.isSuccessful) { "Preview could not load (${response.code})" }
            val body = response.body ?: error("Empty preview")
            require(body.contentLength() <= limit) { "Preview exceeds ${limit / (1024 * 1024)} MiB" }
            body.byteStream().use { input ->
                val result = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; require(result.size() + count <= limit) { "Preview exceeds the size limit" }; result.write(buffer, 0, count) }
                result.toByteArray()
            }
        }
    }
    suspend fun text(url: String, limit: Int = 2 * 1024 * 1024) = bytes(url, limit).toString(Charsets.UTF_8)
    suspend fun temporary(context: Context, url: String, limit: Long = 100L * 1024 * 1024): File {
        var owned: File? = null
        try { return withContext(Dispatchers.IO) {
            val file = File.createTempFile("ohio-preview-", ".asset", context.cacheDir); owned = file
            response(url).use { response ->
                check(response.isSuccessful) { "Preview could not load (${response.code})" }
                val body = response.body ?: error("Empty preview"); require(body.contentLength() <= limit) { "File exceeds the preview size limit" }
                body.byteStream().use { input -> file.outputStream().use { output -> val buffer = ByteArray(32768); var total = 0L
                    while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; total += count; require(total <= limit) { "File exceeds the preview size limit" }; output.write(buffer, 0, count) }
                } }
            }; file
        } } catch (e: Throwable) { owned?.delete(); throw e }
    }
}
