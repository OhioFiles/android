package cloud.ohiofiles.app.data.api

import cloud.ohiofiles.app.data.upload.UploadPhase
import cloud.ohiofiles.app.data.upload.uploadPhase
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UploadProtocolTest {
    @Test fun allAuthAndOptionsPrecedeTheLargeFileStream() {
        val payload = ByteArray(2 * 1024 * 1024 + 43) { (it % 251).toByte() }
        val body = uploadMultipart("captured-key", "photo.dat", payload.toRequestBody("application/octet-stream".toMediaType()),
            isPublic = true, isOneTime = true, customSlug = "stable-link", customFilename = "Photo.dat", folderId = "folder-id", password = " secret Ω ")
        val names = body.parts.map { part -> Regex("name=\"([^\"]+)\"").find(part.headers!!["Content-Disposition"]!!)!!.groupValues[1] }
        assertEquals(listOf("sessionId", "isPublic", "isOneTime", "customSlug", "customFilename", "folderId", "password", "file"), names)
        val values = body.parts.dropLast(1).map { part -> Buffer().also(part.body::writeTo).readUtf8() }
        assertEquals(listOf("captured-key", "true", "true", "stable-link", "Photo.dat", "folder-id", " secret Ω "), values)
        assertArrayEquals(payload, Buffer().also(body.parts.last().body::writeTo).readByteArray())
    }
    @Test fun textCreationPutsDestinationBeforeFileToo() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"created-id","slug":"created-slug"}"""))
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            assertTrue(api.uploadText("captured-key", "guide.md", "# Guide\n" + "a".repeat(1024 * 1024), "docs-folder").isSuccess)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.indexOf("name=\"sessionId\"") < body.indexOf("name=\"file\""))
            assertTrue(body.indexOf("name=\"folderId\"") < body.indexOf("name=\"file\""))
            assertTrue(body.contains("docs-folder"))
        }
    }
    @Test fun nestedServerErrorsAreReadableAndStatusRemains401() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"ERROR","message":"A valid session key or login is required"}}"""))
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            val error = api.request("upload", "POST").exceptionOrNull() as ApiException
            assertEquals(401, error.status)
            assertEquals("A valid session key or login is required", error.message)
        }
        assertEquals("fallback", apiErrorMessage("<html>bad gateway</html>", "fallback"))
    }
    @Test fun delayedServerAcknowledgementControlsSuccessAndFailure() = runBlocking {
        for (status in listOf(200, 401)) MockWebServer().use { server ->
            val response = if (status == 200) """{"id":"saved-id","slug":"saved-slug"}""" else """{"error":{"code":"ERROR","message":"Session access rejected"}}"""
            server.enqueue(MockResponse().setResponseCode(status).setHeadersDelay(600, TimeUnit.MILLISECONDS).setBody(response))
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            val bytesSent = CompletableDeferred<Unit>()
            val body = CountingRequestBody(ByteArray(1024 * 1024).toRequestBody("application/octet-stream".toMediaType())) { written, total -> if (written == total) bytesSent.complete(Unit) }
            val operation = async(Dispatchers.Default) { api.sendUpload(uploadMultipart("captured-key", "large.dat", body)) }
            withTimeout(5000) { bytesSent.await() }
            assertFalse("Writing every byte must not complete the upload before the server acknowledges it", operation.isCompleted)
            val result = withTimeout(5000) { operation.await() }
            if (status == 200) assertEquals("saved-id", result.getOrThrow().finalId)
            else { assertTrue(result.isFailure); assertEquals(401, (result.exceptionOrNull() as ApiException).status) }
        }
    }
    @Test fun bytesCompleteMeansFinalizingAndRetryNeverShowsStale100Percent() {
        assertEquals(UploadPhase.FINALIZING, uploadPhase("RUNNING", "TRANSFERRING", 0, 1f))
        assertEquals(UploadPhase.FINALIZING, uploadPhase("RUNNING", "FINALIZING", 0, 1f))
        assertEquals(UploadPhase.RETRYING, uploadPhase("ENQUEUED", "FINALIZING", 1, 1f))
        assertEquals(UploadPhase.QUEUED, uploadPhase("BLOCKED", "TRANSFERRING", 0, 1f))
    }
}
