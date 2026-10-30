package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.content.Context
import android.os.Build
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.crypto.ChatCrypto
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.model.FileListResponse
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal class ChatRepository(private val context: Context, private val api: OhioApiClient, private val sessions: SessionRepository) {
    private lateinit var identity: ChatCrypto.Identity
    private lateinit var key: String
    var conversationDetails: JsonObject? = null
        private set
    val sessionId get() = identity.sessionId
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val session = sessions.ensureSession().getOrThrow(); key = sessions.getSessionKey() ?: error("Session is unavailable")
        val deviceId = ChatCrypto.deviceId(context)
        identity = ChatCrypto.identity(context, api.getBaseUrl(), session.id, deviceId)
        api.request("session-devices/register", "POST", api.body("sessionKey" to key, "deviceId" to deviceId,
            "name" to "${Build.MANUFACTURER} ${Build.MODEL}".take(80), "platform" to "Android", "deviceType" to "mobile", "browser" to "OhioFiles Android")).getOrThrow()
        api.request("chat/encryption/keys", "POST", api.body("sessionKey" to key, "deviceId" to deviceId, "publicKey" to identity.publicKey)).getOrThrow()
    }
    suspend fun get(path: String, extra: Map<String, String?> = emptyMap()): JsonObject = api.request(path,
        query = mapOf("sessionKey" to key, "deviceId" to identity.deviceId) + extra).getOrThrow().jsonObject
    suspend fun post(path: String, vararg fields: Pair<String, Any?>, method: String = "POST"): JsonObject = api.request(path, method,
        api.body("sessionKey" to key, *fields)).getOrThrow().jsonObject
    suspend fun getDeleteGroup(id: String) = api.request("chat/groups/$id", "DELETE", query = mapOf("sessionKey" to key)).getOrThrow()
    suspend fun messages(id: String, group: Boolean): List<JsonObject> = withContext(Dispatchers.IO) {
        val data = get(if (group) "chat/groups/$id/messages" else "chat/messages/$id")
        conversationDetails = data.obj(if (group) "group" else "person")
        data.objects("messages").map { message ->
            val encryption = message.obj("encryption") ?: return@map message
            val plaintext = runCatching { ChatCrypto.decrypt(encryption, identity, message.obj("sender")?.text("id").orEmpty(), message.text("conversationId")) }
            JsonObject(message + ("displayBody" to JsonPrimitive(plaintext.getOrElse { "Encrypted message unavailable on this device" })) + ("decryptionFailed" to JsonPrimitive(plaintext.isFailure)))
        }
    }
    suspend fun resolveRecipient(nickname: String): JsonObject {
        if (nickname.lowercase() in listOf("saved", "me")) return api.body("id" to sessionId, "nickname" to "Saved messages")
        return get("chat/people", mapOf("query" to nickname.take(32))).objects("people").firstOrNull { it.text("nickname").equals(nickname, true) }
            ?: error("Recipient not found. Choose a person before sending.")
    }
    suspend fun resolvePost(slug: String): JsonObject = get("social/posts/$slug").obj("post") ?: error("This post is unavailable")
    suspend fun resolveFile(reference: String): FileItem {
        get("chat/attachments").objects("files").firstOrNull { it.text("id") == reference || it.text("slug") == reference }?.let {
            return api.json.decodeFromJsonElement<FileItem>(it)
        }
        val bySlug = api.request("files", query = mapOf("slug" to reference, "sessionKey" to key, "limit" to "100")).getOrThrow()
        api.json.decodeFromJsonElement<FileListResponse>(bySlug).files.firstOrNull { it.id == reference || it.slug == reference }?.let { return it }
        var page = 1
        while (true) {
            val result = api.request("files", query = mapOf("public" to "true", "page" to page.toString(), "limit" to "100")).getOrThrow()
            val list = api.json.decodeFromJsonElement<FileListResponse>(result)
            list.files.firstOrNull { it.id == reference || it.slug == reference }?.let { return it }
            if (!list.hasMore && list.page >= list.totalPages && list.page.toLong() * list.limit >= list.total) break
            page += 1
        }
        error("This file is unavailable or cannot be shared with this session")
    }
    suspend fun send(id: String, group: Boolean, text: String, files: Set<String>, folders: Set<String>, edit: Boolean, socialPostId: String? = null) = withContext(Dispatchers.IO) {
        val encryption = if (text.isNotBlank()) {
            val keys = get(if (group) "chat/groups/$id/encryption/keys" else "chat/encryption/keys/$id")
            val targets = keys.objects("devices").map { ChatCrypto.Target(it.text("sessionId"), it.text("deviceId"), it.text("publicKey")) }
            require(targets.any { it.sessionId == identity.sessionId && it.deviceId == identity.deviceId }) { "This device's chat key is no longer active" }
            if (!group) require(targets.any { it.sessionId == id }) { "The recipient must open OhioFiles before receiving encrypted messages" }
            ChatCrypto.encrypt(text, identity.sessionId, id, targets)
        } else null
        val payload = buildJsonObject {
            put("sessionKey", key); put("deviceId", identity.deviceId); put(if (group) "groupId" else "recipientId", id)
            encryption?.let { put("encryption", it) }; if (files.isNotEmpty()) put("fileIds", jsonStrings(files)); if (folders.isNotEmpty()) put("folderIds", jsonStrings(folders))
            socialPostId?.let { put("socialPostId", it) }
            put("access", if (edit) "EDIT" else "VIEW")
        }
        api.request("chat/messages", "POST", payload).getOrThrow()
    }
}
