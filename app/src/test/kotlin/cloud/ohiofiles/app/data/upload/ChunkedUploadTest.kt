package cloud.ohiofiles.app.data.upload

import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.UploadResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ChunkedUploadTest {
    private val chunkSize = 5 * 1024 * 1024
    private fun fixture(size: Int): Pair<File, DurableUploadItem> {
        val file = File.createTempFile("chunk-protocol-", ".data")
        file.outputStream().use { out -> repeat(size / 8192) { out.write(ByteArray(8192) { (it % 251).toByte() }) }; out.write(ByteArray(size % 8192) { (it % 251).toByte() }) }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return file to DurableUploadItem("item", file.path, "private-note.dat", file.length(), hash, "Az19Xy", isAutoSlug = true, uploadAttempted = true)
    }
    private fun successful() = MockResponse().setBody("""{"file":{"id":"confirmed-file","slug":"Az19Xy","filename":"private-note.dat"}}""")
    private fun init(total: Int, request: RecordedRequest): MockResponse {
        val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
        if (body.containsKey("password") && (body["password"] !is JsonPrimitive || body["password"] is JsonNull)) return MockResponse().setResponseCode(400).setBody("{\"error\":\"password must be a string when supplied\"}")
        if (body["sizeBytes"]?.jsonPrimitive?.longOrNull?.let { it > 0 } != true || body["checksumSha256"]?.jsonPrimitive?.content?.matches(Regex("[0-9a-f]{64}")) != true) return MockResponse().setResponseCode(400).setBody("{\"error\":\"Invalid size or checksum\"}")
        return MockResponse().setBody("""{"uploadId":"persisted-session-123","chunkSize":$chunkSize,"totalChunks":$total,"slug":"Az19Xy"}""")
    }
    private fun index(request: RecordedRequest): Int {
        val text = request.body.clone().readUtf8()
        val header = text.indexOf("name=\"chunkIndex\"")
        return text.substring(text.indexOf("\r\n\r\n", header) + 4).substringBefore("\r\n").toInt()
    }

    @Test fun restartSendsOnlyMissingOffsetsWithMetadataFirstAndMandatoryAuth() = runBlocking<Unit> {
        val (file, item) = fixture(chunkSize * 2 + 43)
        try { MockWebServer().use { server ->
            val posted = ConcurrentHashMap<Int, ByteArray>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/upload/chunks/append" -> {
                        assertEquals("captured-session-key", request.getHeader("x-session-key"))
                        val raw = request.body.clone().readByteArray(); val text = raw.toString(Charsets.ISO_8859_1)
                        assertTrue(text.indexOf("name=\"uploadId\"") < text.indexOf("name=\"file\""))
                        assertTrue(text.indexOf("name=\"chunkIndex\"") < text.indexOf("name=\"file\""))
                        val idx = index(request); val headerEnd = text.indexOf("\r\n\r\n", text.indexOf("name=\"file\"")) + 4
                        val count = minOf(chunkSize, (item.size - idx.toLong() * chunkSize).toInt())
                        posted[idx] = raw.copyOfRange(headerEnd, headerEnd + count)
                        MockResponse().setBody("{\"success\":true}")
                    }
                    "/upload/chunks/finalize" -> { assertEquals("captured-session-key", request.getHeader("x-session-key")); successful() }
                    else -> error("A resumed upload must not initialize or send the whole file: ${request.path}")
                }
            }
            val journal = File.createTempFile("chunk-journal-", ".json")
            var persisted = ChunkSession("persisted-session-123", item.slug, chunkSize, 3, setOf(0))
            journal.writeText(Json.encodeToString(ChunkSession.serializer(), persisted))
            val result = uploadChunked(item, UploadItemOptions("folder", false, true, "private-password"), "captured-session-key", OhioApiClient(server.url("/").toString().trimEnd('/')),
                load = { Json.decodeFromString<ChunkSession>(journal.readText()) }, save = { state -> persisted = state!!; journal.writeText(Json.encodeToString(ChunkSession.serializer(), state)) }, recover = { null }, progress = { assertTrue(it.fraction in 0f..1f) })
            assertEquals("confirmed-file", result.finalId); assertEquals(setOf(1, 2), posted.keys); assertEquals(setOf(0, 1, 2), persisted.acknowledged)
            val all = file.readBytes(); posted.forEach { (idx, bytes) -> assertArrayEquals(all.copyOfRange(idx * chunkSize, idx * chunkSize + bytes.size), bytes) }
            journal.delete()
        } } finally { file.delete() }
    }
    @Test fun lostAppendAcknowledgementRepeatsExactlyThatChunkAndKeepsUploadIdentity() = runBlocking<Unit> {
        val (file, item) = fixture(1024 * 1024 + 17)
        try { MockWebServer().use { server ->
            val initCount = AtomicInteger(); val appendCount = AtomicInteger(); var state: ChunkSession? = null
            val losingAck = java.util.concurrent.atomic.AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/upload/chunks/init" -> { initCount.incrementAndGet(); init(1, request) }
                    "/upload/chunks/append" -> { appendCount.incrementAndGet(); if (losingAck.get()) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else MockResponse().setBody("{}") }
                    else -> successful()
                }
            }
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            val first = runCatching { uploadChunked(item, UploadItemOptions(), "key", api, { state }, { state = it }, { null }, {}) }
            assertTrue(first.exceptionOrNull().toString(), first.exceptionOrNull() is IOException); losingAck.set(false); assertNotNull(state); assertTrue(state!!.acknowledged.isEmpty())
            val restored = Json.decodeFromString<ChunkSession>(Json.encodeToString(ChunkSession.serializer(), state!!))
            state = restored
            assertEquals("confirmed-file", uploadChunked(item, UploadItemOptions(), "key", api, { state }, { state = it }, { null }, {}).finalId)
            assertEquals(1, initCount.get()); assertTrue(appendCount.get() >= 2); assertEquals(item.slug, state!!.slug)
        } } finally { file.delete() }
    }
    @Test fun lostFinalizeAckRecoversOwnedFileWithoutResendingBytes() = runBlocking<Unit> {
        val (file, item) = fixture(4097)
        try { MockWebServer().use { server ->
            var state: ChunkSession? = null; val finalized = AtomicInteger(); val appended = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/upload/chunks/init" -> init(1, request)
                    "/upload/chunks/append" -> { appended.incrementAndGet(); MockResponse().setBody("{}") }
                    else -> if (finalized.incrementAndGet() == 1) successful().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else MockResponse().setResponseCode(404).setBody("{\"message\":\"Upload expired\"}")
                }
            }
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching { uploadChunked(item, UploadItemOptions(), "key", api, { state }, { state = it }, { null }, {}) }.exceptionOrNull() is IOException)
            assertEquals(setOf(0), state!!.acknowledged)
            val recovered = uploadChunked(item, UploadItemOptions(), "key", api, { state }, { state = it }, { UploadResponse(id = "already-confirmed", slug = item.slug) }, {})
            assertEquals("already-confirmed", recovered.finalId); assertEquals(1, appended.get()); assertEquals(2, finalized.get())
        } } finally { file.delete() }
    }
    @Test fun expiryReinitializesSameCandidateAndAuthorizationFailureKeepsJournal() = runBlocking<Unit> {
        for (status in listOf(404, 410, 401, 403)) {
            val (file, item) = fixture(129)
            try { MockWebServer().use { server ->
                var state: ChunkSession? = ChunkSession("expired-id", item.slug, chunkSize, 1)
                val inits = AtomicInteger(); val appends = AtomicInteger()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/upload/chunks/append" -> if (appends.incrementAndGet() == 1) MockResponse().setResponseCode(status).setBody("{}") else MockResponse().setBody("{}")
                        "/upload/chunks/init" -> { inits.incrementAndGet(); assertTrue(request.body.clone().readUtf8().contains(item.slug)); init(1, request) }
                        else -> successful()
                    }
                }
                val result = runCatching { uploadChunked(item, UploadItemOptions(), "key", OhioApiClient(server.url("/").toString().trimEnd('/')), { state }, { state = it }, { null }, {}) }
                if (status in listOf(401, 403)) { assertEquals(status, (result.exceptionOrNull() as ApiException).status); assertEquals("expired-id", state!!.uploadId); assertEquals(0, inits.get()) }
                else { assertEquals("confirmed-file", result.getOrThrow().finalId); assertEquals(1, inits.get()) }
            } } finally { file.delete() }
        }
    }
    @Test fun lostInitializationAckReusesCandidateAndNeverSendsAnUncheckpointedChunk() = runBlocking<Unit> {
        val (file, item) = fixture(701)
        try { MockWebServer().use { server ->
            var state: ChunkSession? = null
            val lose = java.util.concurrent.atomic.AtomicBoolean(true)
            val initialized = AtomicInteger(); val appended = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/upload/chunks/init" -> {
                        initialized.incrementAndGet()
                        val payload = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                        assertEquals(item.slug, payload["customSlug"]!!.jsonPrimitive.content)
                        assertFalse(payload.containsKey("password"))
                        if (lose.get()) init(1, request).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else init(1, request)
                    }
                    "/upload/chunks/append" -> { assertNotNull(state); appended.incrementAndGet(); MockResponse().setBody("{}") }
                    else -> successful()
                }
            }
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            assertTrue(runCatching { uploadChunked(item, UploadItemOptions(), "key", api, { state }, { state = it }, { null }, {}) }.exceptionOrNull() is IOException)
            assertNull(state); assertEquals(0, appended.get())
            lose.set(false)
            assertEquals("confirmed-file", uploadChunked(item, UploadItemOptions(), "key", api, { state }, { state = it }, { null }, {}).finalId)
            assertEquals(2, initialized.get()); assertEquals(1, appended.get()); assertEquals(item.slug, state!!.slug)
        } } finally { file.delete() }
    }
    @Test fun rejectedFinalChecksumOrSizeNeverConfirmsOrRemovesStagedBytes() = runBlocking<Unit> {
        val (file, item) = fixture(709)
        try { MockWebServer().use { server ->
            var state: ChunkSession? = ChunkSession("already-initialized", item.slug, chunkSize, 1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/upload/chunks/append") MockResponse().setBody("{}") else MockResponse().setResponseCode(400).setBody("{\"error\":\"Final file size or checksum mismatch\"}")
            }
            val failure = runCatching { uploadChunked(item, UploadItemOptions(), "key", OhioApiClient(server.url("/").toString().trimEnd('/')), { state }, { state = it }, { null }, {}) }.exceptionOrNull()
            assertEquals(400, (failure as ApiException).status)
            assertEquals(setOf(0), state!!.acknowledged); assertTrue(file.canRead()); assertEquals(item.size, file.length()); assertEquals(item.slug, state!!.slug)
        } } finally { file.delete() }
    }

}
