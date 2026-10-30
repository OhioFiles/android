package cloud.ohiofiles.app.data.document

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.*
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

data class ArchiveEntryInfo(val path: String, val size: Long, val directory: Boolean)
object ArchiveContents {
    const val MAX_ENTRIES = 10_000
    const val MAX_SCAN_BYTES = 256L * 1024 * 1024
    const val MAX_EXTRACT_BYTES = 64L * 1024 * 1024
    fun safePath(path: String): String {
        val normalized = path.replace('\\', '/'); require(!normalized.startsWith('/') && !Regex("^[a-zA-Z]:").containsMatchIn(normalized) && normalized.split('/').none { it == ".." || it.any(Char::isISOControl) }) { "Archive contains an unsafe path" }
        require(normalized.length <= 4096) { "Archive path is too long" }; return normalized.trimEnd('/')
    }
    private fun kind(name: String) = when { name.endsWith(".zip", true) -> "zip"; name.endsWith(".tgz", true) || name.endsWith(".tar.gz", true) -> "tgz"; name.endsWith(".tar", true) -> "tar"; name.endsWith(".gz", true) || name.endsWith(".gzip", true) -> "gz"; else -> error("Supported archives: ZIP, TAR, GZIP, and TGZ") }
    private fun bounded(input: InputStream, limit: Long = MAX_SCAN_BYTES) = object : FilterInputStream(input) {
        var count = 0L
        fun add(size: Long) { count += size; require(count <= limit) { "Archive exceeds the ${limit / (1024 * 1024)} MiB decompression limit" }; if (Thread.currentThread().isInterrupted) throw InterruptedIOException() }
        override fun read(): Int { val result = super.read(); if (result >= 0) add(1); return result }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { val result = `in`.read(bytes, offset, length); if (result > 0) add(result.toLong()); return result }
        override fun skip(length: Long): Long { val buffer = ByteArray(8192); var total = 0L; while (total < length) { val amount = read(buffer, 0, minOf(length - total, buffer.size.toLong()).toInt()); if (amount < 0) break; total += amount }; return total }
    }
    fun list(file: File, name: String): List<ArchiveEntryInfo> {
        return when (kind(name)) {
            "zip" -> ZipFile(file).use { zip -> val entries = zip.entries(); buildList { while (entries.hasMoreElements()) { require(size < MAX_ENTRIES) { "Archive contains too many entries" }; val entry = entries.nextElement(); val path = safePath(entry.name); if (path.isNotBlank()) add(ArchiveEntryInfo(path, entry.size, entry.isDirectory)) } } }
            "tar", "tgz" -> tar(file, kind(name) == "tgz").use { input -> buildList { while (true) { val entry = input.nextTarEntry ?: break; require(size < MAX_ENTRIES) { "Archive contains too many entries" }; require(!entry.isSymbolicLink && !entry.isLink) { "Archive links are not supported for extraction" }; val path = safePath(entry.name); if (path.isNotBlank()) add(ArchiveEntryInfo(path, entry.size, entry.isDirectory)) } } }
            else -> GZIPInputStream(file.inputStream()).use { compressed -> bounded(compressed).use { input -> val buffer = ByteArray(8192); var size = 0L; while (true) { val count = input.read(buffer); if (count < 0) break; size += count }; listOf(ArchiveEntryInfo(safePath(name.substringBeforeLast('.')), size, false)) } }
        }
    }
    private fun tar(file: File, compressed: Boolean): TarArchiveInputStream {
        val input = BufferedInputStream(file.inputStream()); return TarArchiveInputStream(bounded(if (compressed) GZIPInputStream(input) else input))
    }
    fun extract(file: File, name: String, path: String, output: OutputStream, limit: Long = MAX_EXTRACT_BYTES) {
        safePath(path)
        fun copy(input: InputStream) { val bytes = ByteArray(16384); var total = 0L; while (true) { if (Thread.currentThread().isInterrupted) throw InterruptedIOException(); val read = input.read(bytes); if (read < 0) break; total += read; require(total <= limit) { "This entry exceeds the ${limit / (1024 * 1024)} MiB extraction limit" }; output.write(bytes, 0, read) } }
        when (kind(name)) {
            "zip" -> ZipFile(file).use { zip -> val entry = zip.getEntry(path) ?: error("Archive entry not found"); require(!entry.isDirectory && entry.size <= limit) { "Entry is a directory or exceeds the extraction limit" }; zip.getInputStream(entry).use(::copy) }
            "tar", "tgz" -> tar(file, kind(name) == "tgz").use { input -> var count = 0; while (true) { val entry = input.nextTarEntry ?: error("Archive entry not found"); require(++count <= MAX_ENTRIES); if (safePath(entry.name) == path) { require(!entry.isLink && !entry.isSymbolicLink && !entry.isDirectory && entry.size <= limit) { "Unsafe or oversized archive entry" }; copy(input); break } } }
            else -> { require(path == safePath(name.substringBeforeLast('.'))) { "Archive entry not found" }; GZIPInputStream(file.inputStream()).use(::copy) }
        }
    }
    fun preview(file: File, name: String, path: String, limit: Int = 2 * 1024 * 1024): ByteArray = ByteArrayOutputStream().also { extract(file, name, path, it, limit.toLong()) }.toByteArray()
}
