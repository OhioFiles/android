package cloud.ohiofiles.app.data.document

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.*
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable data class NotebookDocument(val id: String, val slug: String, val filename: String, val access: String = "OWNER", val checksumSha256: String = "")
@Serializable data class NotebookPage(val id: String = UUID.randomUUID().toString(), val title: String = "Untitled page", val filename: String = "untitled.md",
    val content: String = "", val savedContent: String = "", val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = System.currentTimeMillis(),
    val parentId: String? = null, val folder: String? = null, val order: Int = 0, val editingDocument: NotebookDocument? = null, val recoveryContent: String? = null)
@Serializable data class LocalNotebook(val version: Int = 1, val notebookTitle: String = "My notebook", val pages: List<NotebookPage> = emptyList(),
    val activePageId: String = "", val openTabIds: List<String> = emptyList(), val updatedAt: Long = System.currentTimeMillis())

/** Plain-text interchange matches the web notebook ZIP; JSON preserves page hierarchy and order. */
object NotebookInterchange {
    const val MAX_PAGE_BYTES = 2 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 20 * 1024 * 1024
    const val MAX_PAGES = 200
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun safeFilename(value: String): String = value.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "-").trim().take(180).ifBlank { "untitled.md" }
    fun title(content: String, name: String): String = Regex("(?m)^#\\s+(.+)$").find(content)?.groupValues?.get(1)?.replace(Regex("[*_`~=]"), "")?.take(80) ?: name.substringBeforeLast('.')
    fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val out = ByteArrayOutputStream(); val bytes = ByteArray(8192)
        while (true) { if (Thread.currentThread().isInterrupted) throw InterruptedIOException(); val count = input.read(bytes); if (count < 0) break; require(out.size() + count <= maximum) { "Import exceeds the size limit" }; out.write(bytes, 0, count) }
        return out.toByteArray()
    }
    fun import(input: InputStream, name: String): LocalNotebook {
        val suffix = name.substringAfterLast('.', "").lowercase()
        val notebook = when (suffix) {
            "json" -> {
                val text = readBounded(input, MAX_TOTAL_BYTES).toString(Charsets.UTF_8)
                val structured = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
                if (structured?.containsKey("pages") == true) json.decodeFromString<LocalNotebook>(text)
                else { require(text.toByteArray().size <= MAX_PAGE_BYTES); val page = NotebookPage(title = title(text, name), filename = safeFilename(name), content = text); LocalNotebook(pages = listOf(page), activePageId = page.id, openTabIds = listOf(page.id)) }
            }
            "zip" -> {
                val pages = mutableListOf<NotebookPage>(); var total = 0; var entries = 0
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break; require(++entries <= 1000) { "Archive contains too many entries" }
                        val path = entry.name.replace('\\', '/'); require(!path.startsWith('/') && path.split('/').none { it == ".." }) { "Unsafe archive path" }
                        val accepted = !entry.isDirectory && !path.startsWith("__MACOSX/") && !path.substringAfterLast('/').startsWith('.') && (path.lowercase().endsWith(".md") || path.lowercase().endsWith(".txt") || path.lowercase().endsWith(".markdown"))
                        require(entry.size <= (if (accepted) MAX_PAGE_BYTES else MAX_TOTAL_BYTES)) { "Archive entry exceeds the import limit" }
                        val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { if (Thread.currentThread().isInterrupted) throw InterruptedIOException(); val read = zip.read(buffer); if (read < 0) break; total += read; require(total <= MAX_TOTAL_BYTES) { "Notebook archive exceeds 20 MiB of decompressed content" }; if (accepted) { require(bytes.size() + read <= MAX_PAGE_BYTES) { "Page exceeds 2 MiB" }; bytes.write(buffer, 0, read) } }
                        if (accepted) {
                            require(pages.size < MAX_PAGES) { "Import supports up to $MAX_PAGES pages" }
                            val data = bytes.toByteArray()
                            val text = data.toString(Charsets.UTF_8); val filename = safeFilename(path)
                            pages += NotebookPage(title = title(text, filename), filename = filename, content = text, folder = path.substringBeforeLast('/', "").ifBlank { null }, order = pages.size)
                        }
                    }
                }
                require(pages.isNotEmpty()) { "Archive has no Markdown or text pages" }
                LocalNotebook(notebookTitle = name.substringBeforeLast('.'), pages = pages, activePageId = pages.first().id, openTabIds = listOf(pages.first().id))
            }
            else -> { val text = readBounded(input, MAX_PAGE_BYTES).toString(Charsets.UTF_8); val file = safeFilename(name); val page = NotebookPage(title = title(text, file), filename = file, content = text); LocalNotebook(pages = listOf(page), activePageId = page.id, openTabIds = listOf(page.id)) }
        }
        require(notebook.version == 1 && notebook.pages.size <= MAX_PAGES) { "Unsupported notebook format" }
        require(notebook.pages.map { it.id }.distinct().size == notebook.pages.size) { "Notebook contains duplicate page identifiers" }
        require(notebook.pages.sumOf { it.content.toByteArray().size.toLong() } <= MAX_TOTAL_BYTES && notebook.pages.all { it.content.toByteArray().size <= MAX_PAGE_BYTES }) { "Notebook exceeds the size limit" }
        val identifiers = notebook.pages.associate { it.id to UUID.randomUUID().toString() }
        return notebook.copy(pages = notebook.pages.mapIndexed { index, page -> page.copy(id = identifiers.getValue(page.id), parentId = identifiers[page.parentId], filename = safeFilename(page.filename), order = index,
            // Imported JSON is a local recovery copy; the active server must explicitly publish it.
            editingDocument = null) }).let { remapped ->
            remapped.copy(activePageId = remapped.pages.firstOrNull()?.id.orEmpty(), openTabIds = remapped.pages.take(1).map { it.id })
        }
    }
    fun exportZip(notebook: LocalNotebook, output: OutputStream) {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val used = mutableSetOf<String>()
            notebook.pages.sortedBy { it.order }.forEachIndexed { index, page ->
                val base = safeFilename(page.filename); var name = base; var suffix = 2
                while (!used.add(name.lowercase())) { val stem = base.substringBeforeLast('.', base); val extension = base.substringAfterLast('.', ""); name = "$stem-${suffix++}" + if (extension.isBlank()) "" else ".$extension" }
                zip.putNextEntry(ZipEntry(name)); zip.write(page.content.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
        }
    }
}

class NotebookStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "local-notebook-v1.json")
    private val mutex = Mutex()
    suspend fun load(): LocalNotebook = withContext(Dispatchers.IO) { mutex.withLock {
        if (!file.exists()) LocalNotebook() else NotebookInterchange.json.decodeFromString<LocalNotebook>(file.readText())
    } }
    suspend fun save(notebook: LocalNotebook) = withContext(Dispatchers.IO) { mutex.withLock {
        val pending = File(file.parentFile, file.name + ".pending")
        pending.writeText(NotebookInterchange.json.encodeToString(LocalNotebook.serializer(), notebook.copy(updatedAt = System.currentTimeMillis())))
        check(pending.renameTo(file)) { "Notebook could not save on this device" }
    } }
}
