package cloud.ohiofiles.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.ui.screens.nativefeatures.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivateVoiceParityTest {
    @Test fun generatedSilentVoiceAndEncryptedStickerSendOnlyToOwnedQaSavedMessages() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageName == "cloud.ohiofiles.app.qa")
        val api = OhioApiClient(); val sessions = SessionRepository(context, api)
        api.setBaseUrl(sessions.getServerUrl())
        val key = sessions.getSessionKey() ?: error("Run the isolated account fixture first")
        check(key in context.getSharedPreferences("qa_identity_registry", Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty()) { "Only a newly created private QA identity may send test messages" }
        val fixture = File(context.cacheDir, "voice-drafts/voice-Auditor-QA-dur3.m4a")
        check(fixture.isFile && fixture.length() > 0) { "Stage the generated silent AAC fixture in the QA companion cache first" }
        val session = sessions.ensureSession().getOrThrow()
        val repository = ChatRepository(context, api, sessions); repository.initialize()
        val media = withContext(Dispatchers.Main) { ChatMediaController(context, api.getBaseUrl(), key) }
        withContext(Dispatchers.Main) { media.restoreVoice(session.id, fixture.path, 3) }
        val fileId = media.uploadVoice(session.id) ?: error("Voice upload did not return an ID")
        assertEquals(fileId, media.uploadVoice(session.id)) // Retrying Send reuses the acknowledged upload.
        repository.send(session.id, false, "", setOf(fileId), emptySet(), false)
        var messages = repository.messages(session.id, false)
        val voice = messages.lastOrNull { value ->
            (value["files"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content == fileId } || (value["file"] as? JsonObject)?.get("id")?.jsonPrimitive?.content == fileId
        } ?: error("The native voice attachment wasn't persisted in QA Saved messages")
        assertTrue(voice["encryption"] == null || voice["encryption"] is JsonNull)
        val sticker = blobatarMessage("happy")
        repository.send(session.id, false, sticker, emptySet(), emptySet(), false)
        messages = repository.messages(session.id, false)
        val stickerMessage = messages.lastOrNull { it["displayBody"]?.jsonPrimitive?.content == sticker } ?: error("Encrypted native sticker didn't decrypt after sending")
        val messageId = stickerMessage["id"]!!.jsonPrimitive.content
        assertTrue(stickerMessage["body"] == null || stickerMessage["body"] is JsonNull || stickerMessage["body"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        repository.post("chat/messages/$messageId/reactions", "emoji" to "👍")
        var reactions = repository.messages(session.id, false).first { it["id"]!!.jsonPrimitive.content == messageId }["reactions"]!!.jsonArray
        assertTrue(reactions.any { it.jsonObject["emoji"]?.jsonPrimitive?.content == "👍" && (it.jsonObject["count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 })
        repository.post("chat/messages/$messageId/reactions", "emoji" to "👍")
        reactions = repository.messages(session.id, false).first { it["id"]!!.jsonPrimitive.content == messageId }["reactions"]!!.jsonArray
        assertFalse(reactions.any { it.jsonObject["emoji"]?.jsonPrimitive?.content == "👍" && (it.jsonObject["count"]?.jsonPrimitive?.intOrNull ?: 0) > 0 })
        context.getSharedPreferences("qa_voice_fixture", Context.MODE_PRIVATE).edit().putString("voice_file", fileId).putString("sticker_message", messageId).commit()
        // These private QA messages remain for native playback verification, then the QA registry is revoked.
    }
}
