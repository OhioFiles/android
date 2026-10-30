package cloud.ohiofiles.app

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.PublicSession
import cloud.ohiofiles.app.data.model.UploadResponse
import cloud.ohiofiles.app.data.repository.UploadSessionAccess
import cloud.ohiofiles.app.data.upload.*
import cloud.ohiofiles.app.ui.screens.upload.UploadViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UploadQueueRegressionTest {
    @Test fun shortSlugRotationSurvivesARepositoryRestartWithoutChangingCustomLinks() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "slug-regression-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        val repository = DurableUploadQueue(isolated)
        val source = File(directory, "note.txt").apply { writeText("Unchanged staged source") }
        val server = "https://127.0.0.1:1"; val key = "isolated-slug-key"
        try {
            val batch = repository.prepare(server, key, listOf(Triple(Uri.fromFile(source), source.name, null), Triple(Uri.fromFile(source), source.name, "my-existing-custom-link")), "kept-folder", false, true, "kept-password")
            val auto = batch.items.first(); val custom = batch.items.last()
            assertTrue(auto.isAutoSlug); assertEquals(6, auto.slug.length); assertFalse(auto.uploadAttempted)
            assertFalse(custom.isAutoSlug); assertEquals("my-existing-custom-link", custom.slug)
            val rotated = auto.copy(slug = "Az19Xy", slugCollisionCount = 1)
            repository.checkpointItem(batch.id, rotated, auto)
            repository.checkpointItem(batch.id, rotated.copy(uploadAttempted = true), rotated)
            val restarted = DurableUploadQueue(isolated).read(batch.id)!!
            assertEquals(batch.copy(items = listOf(rotated.copy(uploadAttempted = true), custom)), restarted)
            val staleWrite = runCatching { repository.checkpointItem(batch.id, auto.copy(uploadAttempted = true), auto) }
            assertTrue(staleWrite.exceptionOrNull() is IllegalStateException)
            assertEquals(restarted, DurableUploadQueue(isolated).read(batch.id))
            assertEquals("Unchanged staged source", File(restarted.items.first().path).readText())
            assertEquals(batch.id, repository.active(server, key))
        } finally { directory.deleteRecursively() }
    }
    @Test fun incomingShareWaitsForOldRestoreAndPersistsANewBatch() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "queue-regression-${UUID.randomUUID()}"
        val directory = File(base.cacheDir, prefix).apply { mkdirs() }
        val isolated = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$prefix-$name", mode)
        }
        val key = "isolated-regression-key"
        val server = "https://127.0.0.1:1"
        val gate = CompletableDeferred<Unit>()
        var ensureCalls = 0
        val identity = object : UploadSessionAccess {
            override val sessionKeyFlow = flow<String?> { gate.await(); emit(key); awaitCancellation() }
            override suspend fun getSessionKey() = key
            override suspend fun getServerUrl() = server
            override suspend fun ensureSession(): Result<PublicSession> { ensureCalls++; return Result.failure(IllegalStateException("Offline test must not initialize a session")) }
        }
        val repository = DurableUploadQueue(isolated)
        val oldFile = File(directory, "old-completed.txt").apply { writeText("old completed fixture") }
        val newFile = File(directory, "new-note.txt").apply { writeText("new incoming content") }
        val old = repository.prepare(server, key, listOf(Triple(Uri.fromFile(oldFile), oldFile.name, null)), null, false, false, null)
        repository.record(old.id, DurableUploadResult(old.items.single().id, UploadResponse(id = "old-id", slug = old.items.single().slug, filename = oldFile.name)))
        // A completed history row need not run a real worker or contact a server.
        isolated.getSharedPreferences("durable_upload_queue", Context.MODE_PRIVATE).edit()
            .putString("work-${old.id}", UUID.randomUUID().toString()).commit()
        val model = withContext(Dispatchers.Main) { UploadViewModel(OhioApiClient(server), identity, isolated) }
        var created: DurableUploadBatch? = null
        try {
            withContext(Dispatchers.Main) { model.onFilesSelected(isolated, listOf(Uri.fromFile(newFile))) }
            delay(100) // Deliberately let the incoming share precede persisted identity restoration.
            gate.complete(Unit)
            withTimeout(8000) { model.queue.first { it.size == 1 && it.single().name == newFile.name } }
            assertTrue(model.completed.value.isEmpty())
            withContext(Dispatchers.Main) { model.startUpload(isolated) }
            val id = withTimeout(8000) {
                while (true) {
                    val active = repository.active(server, key)
                    if (active != null && active != old.id) return@withTimeout active
                    delay(20)
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
            created = repository.read(id)
            assertNotNull(created)
            assertNotEquals(old.id, created!!.id)
            assertEquals(listOf(newFile.name), created!!.items.map { it.filename })
            assertEquals("new incoming content", File(created!!.items.single().path).readText())
            assertEquals(0, ensureCalls) // A persisted identity can stage while offline.
            repository.forget(server, key, old.id) // Late old cleanup must not erase new ownership.
            assertEquals(created!!.id, repository.active(server, key))
        } finally {
            withContext(Dispatchers.Main) { model.viewModelScope.cancel() }
            created?.let { repository.discard(it) }
            directory.deleteRecursively()
        }
    }
}
