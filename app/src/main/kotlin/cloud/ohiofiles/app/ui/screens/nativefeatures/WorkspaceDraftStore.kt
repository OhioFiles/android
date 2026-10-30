package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.content.Context
import cloud.ohiofiles.app.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

internal data class WorkspaceDraft(val content: String, val checksum: String, val updatedAt: Long)
internal class WorkspaceDraftStore(context: Context, server: String, session: String) {
    private val root = File(context.noBackupFilesDir, "workspace-drafts/" + digest("$server|$session"))
    private val mutex = Mutex()
    private fun file(id: String) = File(root, digest(id) + ".json")
    suspend fun load(id: String): WorkspaceDraft? = withContext(Dispatchers.IO) { mutex.withLock {
        val target = file(id)
        if (!target.exists()) null else Json.parseToJsonElement(target.readText()).jsonObject.let { WorkspaceDraft(it.text("content"), it.text("checksum"), it.text("updatedAt").toLongOrNull() ?: 0L) }
    } }
    suspend fun save(id: String, content: String, checksum: String) = withContext(Dispatchers.IO) { mutex.withLock {
        check(root.exists() || root.mkdirs()) { "Draft folder could not be created" }
        val target = file(id); val pending = File(root, target.name + ".pending")
        pending.writeText(buildJsonObject { put("content", content); put("checksum", checksum); put("updatedAt", System.currentTimeMillis().toString()) }.toString())
        check(pending.renameTo(target)) { "Draft could not be saved" }
    } }
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { mutex.withLock { file(id).let { check(!it.exists() || it.delete()) { "Draft could not be removed" } } } }
    companion object { private fun digest(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) } }
}
