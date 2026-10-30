package cloud.ohiofiles.app

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.data.upload.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PrivateBatchUploadParityTest {
    @Test fun realWorkerKeepsTwoFilesOptionsAndReportsFolderVisibilityTruth() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageName == "cloud.ohiofiles.app.qa")
        val api = OhioApiClient(); val sessions = SessionRepository(context, api)
        val key = sessions.getSessionKey() ?: error("Run the guarded account fixture first")
        check(key in context.getSharedPreferences("qa_identity_registry", Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty())
        api.setBaseUrl(sessions.getServerUrl())
        val root = File(context.cacheDir, "private-batch-${UUID.randomUUID()}").apply { mkdirs() }
        val firstFolder = api.createFolder(key, "Private batch A").getOrThrow()
        val secondFolder = api.createFolder(key, "Private batch B").getOrThrow()
        val repository = DurableUploadQueue(context)
        var batch: DurableUploadBatch? = null
        try {
            val first = File(root, "first.asset").apply { writeText("Generated private batch first ${UUID.randomUUID()}") }
            val second = File(root, "second.asset").apply { writeText("Generated private batch second ${UUID.randomUUID()}") }
            val custom = "qa-${UUID.randomUUID().toString().take(12)}"
            batch = repository.prepare(api.getBaseUrl(), key, listOf(Triple(Uri.fromFile(first), "first-private.txt", null), Triple(Uri.fromFile(second), "second-private.txt", custom)),
                null, false, false, null, options = listOf(UploadItemOptions(firstFolder.id, false, false, null), UploadItemOptions(secondFolder.id, true, true, "Private-qa-password")))
            val worker = TestListenableWorkerBuilder<UploadWorker>(context).setInputData(Data.Builder().putString("batchId", batch.id).build()).build()
            worker.doWork()
            val records = repository.results(batch.id)
            assertEquals(2, records.count { it.response != null })
            for ((index, item) in batch.items.withIndex()) {
                val data = api.resolveFile(item.slug, key).getOrThrow()
                assertEquals(item.filename, data.displayName); assertEquals(item.checksum, data.checksumSha256)
                assertEquals(if (index == 0) firstFolder.id else secondFolder.id, data.folderId)
                assertFalse(data.isPublic) // The server's private-folder visibility overrides the per-file public request.
                assertEquals(index == 1, data.isOneTime); assertEquals(index == 1, data.passwordRequired)
                assertEquals(if (index == 0) 6 else custom.length, data.slug.length)
                assertFalse(File(item.path).exists())
            }
            // Restarted work must skip both acknowledged results and create no duplicates.
            TestListenableWorkerBuilder<UploadWorker>(context).setInputData(Data.Builder().putString("batchId", batch.id).build()).build().doWork()
            assertEquals(2, repository.results(batch.id).count { it.response != null })
        } finally {
            withContext(NonCancellable) {
                batch?.items?.forEach { api.deleteFile(it.slug, key) }
                api.deleteFolder(firstFolder.id, key); api.deleteFolder(secondFolder.id, key)
                batch?.let { repository.discard(it) }; root.deleteRecursively()
            }
        }
    }
}
