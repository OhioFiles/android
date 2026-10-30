package cloud.ohiofiles.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.crypto.ChatCrypto
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.ui.screens.nativefeatures.ChatRepository
import cloud.ohiofiles.app.ui.screens.profile.importArchive
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real mutations are guarded by a separate Android UID and never run against the installed user's account. */
@RunWith(AndroidJUnit4::class)
class PrivateAccountParityTest {
    @Test fun privateQaAvatarBackupImportRestoreDevicesAndEncryptedGroup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue("Requires the isolated QA companion package", context.packageName == "cloud.ohiofiles.app.qa")
        val api = OhioApiClient()
        val sessions = SessionRepository(context, api)
        api.setBaseUrl(sessions.getServerUrl())
        val registry = context.getSharedPreferences("qa_identity_registry", Context.MODE_PRIVATE)
        suspend fun newOwnedQaIdentity() = api.createSession().getOrThrow().also { created ->
            registry.edit().putStringSet("keys", registry.getStringSet("keys", emptySet()).orEmpty() + created.key).commit()
        }
        val primary = newOwnedQaIdentity()
        sessions.importSessionKey(primary.key).getOrThrow()
        val key = primary.key
        val secondary = newOwnedQaIdentity()
        val cleanupFiles = mutableSetOf<String>(); val cleanupFolders = mutableSetOf<String>(); val cleanupBackups = mutableSetOf<String>()
        var groupId: String? = null
        val local = File(context.cacheDir, "private-parity-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val png = File(local, "avatar.asset")
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            val avatar = api.uploadFile(context, Uri.fromFile(png), key, customFilename = "private-qa-avatar.png", mimeTypeOverride = "image/png").getOrThrow()
            cleanupFiles += avatar.finalSlug!!
            api.request("profiles/me/avatar", "PUT", api.body("sessionKey" to key, "fileId" to avatar.finalId)).getOrThrow()
            api.request("profiles/me", "PUT", api.body("sessionKey" to key, "readme" to "# Private QA\nNative account verification fixture.")).getOrThrow()
            api.request("public-sessions/$key/customization", "POST", api.body("customization" to api.body("mood" to "thinking", "statusText" to "Private QA fixture"))).getOrThrow()
            val profile = api.request("profiles/me", query = mapOf("sessionKey" to key)).getOrThrow().jsonObject["profile"]!!.jsonObject
            assertTrue(profile["avatarUrl"]?.jsonPrimitive?.content.orEmpty().isNotBlank())
            assertTrue(profile["readme"]!!.jsonPrimitive.content.contains("Private QA"))
            assertEquals("thinking", profile["mood"]?.jsonPrimitive?.content)

            val folder = api.createFolder(key, "Private QA folder").getOrThrow(); cleanupFolders += folder.id
            val note = api.uploadText(key, "private-qa-note.md", "# Private backup fixture", folder.id).getOrThrow(); cleanupFiles += note.slug
            val backupRoot = api.request("session-backups", "POST", api.body("sessionKey" to key, "name" to "Private native QA")).getOrThrow().jsonObject
            val backup = (backupRoot["backup"] as? JsonObject) ?: backupRoot
            val backupId = backup["id"]!!.jsonPrimitive.content; cleanupBackups += backupId
            val archive = File(local, "backup.asset")
            withContext(Dispatchers.IO) {
                OkHttpClient().newCall(Request.Builder().url(api.url("session-backups/$backupId/download", mapOf("sessionKey" to key))).build()).execute().use { response ->
                    check(response.isSuccessful) { "Private backup download failed" }
                    response.body!!.byteStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
                }
            }
            assertTrue(archive.length() > 0)
            api.request("session-backups/$backupId/restore", "POST", api.body("sessionKey" to key)).getOrThrow()
            importArchive(context, Uri.fromFile(archive), api, secondary.key).getOrThrow()
            val imported = api.request("files", query = mapOf("sessionKey" to secondary.key, "limit" to "100")).getOrThrow().jsonObject["files"]!!.jsonArray
            assertTrue(imported.any { it.jsonObject["filename"]?.jsonPrimitive?.content == "private-qa-note.md" })

            val currentDevice = ChatCrypto.deviceId(context)
            val otherDevice = "qa-other-${UUID.randomUUID()}"
            for (device in listOf(currentDevice, otherDevice)) api.request("session-devices/register", "POST", api.body("sessionKey" to key, "deviceId" to device, "name" to "Private QA device", "platform" to "Android", "deviceType" to "mobile", "browser" to "OhioFiles QA")).getOrThrow()
            api.request("session-devices/revoke-others", "POST", api.body("sessionKey" to key, "deviceId" to currentDevice)).getOrThrow()
            val devices = api.request("session-devices", query = mapOf("sessionKey" to key, "deviceId" to currentDevice)).getOrThrow().jsonObject["devices"]!!.jsonArray
            assertTrue(devices.any { it.jsonObject["deviceId"]?.jsonPrimitive?.content == currentDevice })
            assertFalse(devices.any { it.jsonObject["deviceId"]?.jsonPrimitive?.content == otherDevice })

            val repository = ChatRepository(context, api, sessions); repository.initialize()
            val secondaryDevice = "qa-secondary-${UUID.randomUUID()}"
            val secondIdentity = ChatCrypto.identity(context, api.getBaseUrl(), secondary.id, secondaryDevice)
            api.request("session-devices/register", "POST", api.body("sessionKey" to secondary.key, "deviceId" to secondaryDevice, "name" to "Private QA recipient", "platform" to "Android", "deviceType" to "mobile", "browser" to "OhioFiles QA")).getOrThrow()
            api.request("chat/encryption/keys", "POST", api.body("sessionKey" to secondary.key, "deviceId" to secondaryDevice, "publicKey" to secondIdentity.publicKey)).getOrThrow()
            val groupRoot = api.request("chat/groups", "POST", api.body("sessionKey" to key, "name" to "Private QA group", "memberIds" to JsonArray(listOf(JsonPrimitive(secondary.id))))).getOrThrow().jsonObject
            groupId = groupRoot["group"]!!.jsonObject["id"]!!.jsonPrimitive.content
            repository.send(groupId!!, true, "Private native encrypted QA", emptySet(), emptySet(), false)
            val messages = repository.messages(groupId!!, true)
            assertTrue(messages.any { it["displayBody"]?.jsonPrimitive?.content == "Private native encrypted QA" })
            assertTrue(messages.all { it["body"] == null || it["body"] is JsonNull || it["body"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() })
        } finally {
            withContext(NonCancellable) {
                groupId?.let { api.request("chat/groups/$it", "DELETE", query = mapOf("sessionKey" to key)) }
                api.request("profiles/me/avatar", "DELETE", query = mapOf("sessionKey" to key))
                api.request("profiles/me", "PUT", api.body("sessionKey" to key, "readme" to ""))
                api.request("public-sessions/$key/customization", "POST", api.body("customization" to api.body("mood" to "", "statusText" to "")))
                for (id in cleanupBackups) api.request("session-backups/$id", "DELETE", query = mapOf("sessionKey" to key))
                // A restore may mint new IDs; remove all content in this guarded private QA account only.
                api.request("files", query = mapOf("sessionKey" to key, "limit" to "100")).getOrNull()?.jsonObject?.get("files")?.jsonArray?.forEach { file -> api.deleteFile(file.jsonObject["slug"]!!.jsonPrimitive.content, key) }
                api.listFolders(key).getOrNull()?.folders?.forEach { api.deleteFolder(it.id, key) }
                api.revokeSession(secondary.key)
                api.revokeSession(primary.key)
                // Leave the isolated companion ready for additional native UI checks with another owned, empty identity.
                sessions.importSessionKey(newOwnedQaIdentity().key)
                local.deleteRecursively()
            }
        }
    }
}
