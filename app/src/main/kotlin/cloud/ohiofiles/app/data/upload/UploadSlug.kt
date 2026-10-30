package cloud.ohiofiles.app.data.upload

import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.model.UploadResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.security.SecureRandom

internal const val UPLOAD_SLUG_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
private val slugRandom = SecureRandom()
// Only these backend reserved routes have the generated slug's six-character length.
private val reservedShortSlugs = setOf("public", "logout", "signin", "static", "assets", "images")
internal fun generateUploadSlug(nextIndex: (Int) -> Int = slugRandom::nextInt): String {
    repeat(10) {
        val candidate = buildString { repeat(6) { append(UPLOAD_SLUG_ALPHABET[nextIndex(UPLOAD_SLUG_ALPHABET.length)]) } }
        if (candidate.lowercase(java.util.Locale.ROOT) !in reservedShortSlugs) return candidate
    }
    error("Could not create an available short link. Retry your upload.")
}

internal data class ExistingUpload(val isOwner: Boolean, val checksum: String?, val response: UploadResponse)

/** Rotate only confirmed auto-slug collisions; uncertain requests keep their durable candidate. */
internal suspend fun uploadWithStableSlug(
    initial: DurableUploadItem,
    lookup: suspend (String) -> ExistingUpload?,
    upload: suspend (DurableUploadItem) -> UploadResponse,
    persist: suspend (DurableUploadItem, DurableUploadItem) -> DurableUploadItem,
    generate: () -> String = { generateUploadSlug() }
): UploadResponse {
    var item = initial
    fun ExistingUpload.ownedMatch() = item.uploadAttempted && isOwner && checksum == item.checksum
    suspend fun rotate() {
        check(item.isAutoSlug) { "This link is already used by another file. Choose a different custom link." }
        check(item.slugCollisionCount < 9) { "Couldn't find an available short link after 10 attempts. Retry your upload." }
        val replacement = item.copy(slug = generate(), slugCollisionCount = item.slugCollisionCount + 1, uploadAttempted = false)
        // Must commit before the next request so a killed process always retries the same candidate.
        item = persist(replacement, item)
    }
    while (true) {
        currentCoroutineContext().ensureActive()
        val existing = lookup(item.slug)
        if (existing != null) {
            if (existing.ownedMatch()) return existing.response
            rotate()
            continue
        }
        if (!item.uploadAttempted) item = persist(item.copy(uploadAttempted = true), item)
        try { return upload(item) }
        catch (e: CancellationException) { throw e }
        catch (e: ApiException) {
            if (e.status != 409 || !e.message.orEmpty().contains("Slug already in use", ignoreCase = true)) throw e
            // A completed upload with a lost acknowledgement can race the first lookup.
            val acknowledged = lookup(item.slug)
            if (acknowledged?.ownedMatch() == true) return acknowledged.response
            rotate()
        }
    }
}

internal fun validateUploadFilename(name: String) {
    require(name.isNotBlank() && name.length <= 255 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) { "Use a filename of 1–255 characters without slashes or control characters" }
}
private val reservedCustomSlugs = setOf("admin", "api", "folders", "workspace", "public", "settings", "text", "chats", "docs", "sites", "p", "u", "f", "dashboard", "login", "logout", "register", "signin", "signout", "app", "_next", "static", "assets", "images", "icons", "tree")
internal fun validateCustomUploadSlug(slug: String?) {
    if (slug == null) return
    require(slug.length in 3..32 && slug.all { it in UPLOAD_SLUG_ALPHABET || it == '-' || it == '_' } && slug.lowercase(java.util.Locale.ROOT) !in reservedCustomSlugs) { "Use 3–32 letters, numbers, dashes or underscores and avoid reserved links" }
}
internal fun generateUploadPassword(): String = buildString { repeat(12) { append(UPLOAD_SLUG_ALPHABET[slugRandom.nextInt(UPLOAD_SLUG_ALPHABET.length)]) } }

internal fun ownedUploadDuplicate(item: DurableUploadItem, files: List<cloud.ohiofiles.app.data.model.FileItem>) = files.firstOrNull {
    it.isOwner && it.checksumSha256 == item.checksum && it.size == item.size && !(it.isOneTime && it.oneTimeConsumed)
}
internal class DuplicateUploadChoice : Exception("The same content is already in your files. Choose whether to skip it or upload again.")
