package cloud.ohiofiles.app.parity

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.ohiofiles.app.ui.screens.nativefeatures.ChatMediaController
import cloud.ohiofiles.app.ui.screens.nativefeatures.ChatMediaUploader
import cloud.ohiofiles.app.ui.screens.nativefeatures.readyFileIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** No production uploads, microphone access, messages or account mutations. Exercises actual retained media state. */
@RunWith(AndroidJUnit4::class)
class ChatMediaLifecycleTest {
    @Test fun uploadFailureKeepsSourceRetryAttachesMetadataAndOtherConversationCannotSendIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixture = File.createTempFile("chat-media-qa-", ".txt", context.cacheDir).apply { writeText("Disposable media fixture") }
        val attempts = AtomicInteger()
        val controller = ChatMediaController(context, "https://qa.invalid", "QA-not-a-real-session", ChatMediaUploader { _, progress -> progress(.5f); if (attempts.incrementAndGet() == 1) throw IOException("simulated offline"); "qa-file-id" })
        try {
            controller.add("peer:qa-one", listOf(Uri.fromFile(fixture)), 0)
            val failed = withTimeout(8000) { controller.state.first { it.uploads.firstOrNull()?.status == "Failed" } }.uploads.single()
            assertEquals(Uri.fromFile(fixture).toString(), failed.uri); assertNull(failed.fileId); assertTrue(fixture.exists())
            controller.upload(failed.id)
            val ready = withTimeout(8000) { controller.state.first { it.uploads.firstOrNull()?.status == "Ready" } }
            assertEquals(setOf("qa-file-id"), ready.readyFileIds("peer:qa-one")); assertTrue(ready.readyFileIds("peer:qa-other").isEmpty())
            controller.clearSent("peer:qa-other", setOf("qa-file-id")); assertEquals(1, controller.state.value.uploads.size)
            controller.clearSent("peer:qa-one", setOf("qa-file-id")); assertTrue(controller.state.value.uploads.isEmpty())
        } finally { controller.state.value.uploads.forEach { controller.remove(it.id) }; fixture.delete() }
    }
    @Test fun canceledUploadRemainsRetryableWithoutFileId() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fixture = File.createTempFile("chat-cancel-qa-", ".txt", context.cacheDir).apply { writeText("Disposable cancel fixture") }
        val entered = CompletableDeferred<Unit>(); val never = CompletableDeferred<String>()
        val controller = ChatMediaController(context, "https://qa.invalid", "QA-not-a-real-session", ChatMediaUploader { _, _ -> entered.complete(Unit); never.await() })
        try {
            controller.add("peer:qa", listOf(Uri.fromFile(fixture)), 0)
            withTimeout(8000) { entered.await() }
            val id = controller.state.value.uploads.single().id; controller.cancel(id)
            val canceled = withTimeout(8000) { controller.state.first { it.uploads.singleOrNull()?.status == "Canceled" } }.uploads.single()
            assertNull(canceled.fileId); assertTrue(fixture.exists()); assertEquals("peer:qa", canceled.thread)
        } finally { controller.state.value.uploads.forEach { controller.remove(it.id) }; fixture.delete() }
    }
    @Test fun voiceUploadFailurePreservesLocalDraftAndRetryReusesUploadedAsset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.cacheDir, "voice-drafts").apply { mkdirs() }
        val fixture = File.createTempFile("voice-qa-", ".m4a", directory).apply { writeBytes(ByteArray(64) { 7 }) }
        val attempts = AtomicInteger()
        val controller = ChatMediaController(context, "https://qa.invalid", "QA-not-a-real-session", ChatMediaUploader { _, _ -> if (attempts.incrementAndGet() == 1) throw IOException("simulated offline"); "qa-voice-id" })
        try {
            controller.restoreVoice("peer:qa", fixture.absolutePath, 3)
            assertTrue(runCatching { controller.uploadVoice("peer:qa") }.isFailure)
            assertTrue(fixture.exists()); assertEquals(fixture.absolutePath, controller.state.value.voices["peer:qa"]?.path); assertNull(controller.state.value.voices["peer:qa"]?.uploadedId)
            assertEquals("qa-voice-id", controller.uploadVoice("peer:qa"))
            assertEquals("qa-voice-id", controller.uploadVoice("peer:qa")); assertEquals(2, attempts.get())
            controller.discardVoice("peer:qa"); assertFalse(fixture.exists()); assertNull(controller.state.value.voices["peer:qa"])
        } finally { controller.discardVoice("peer:qa"); fixture.delete() }
    }
}
