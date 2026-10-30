package cloud.ohiofiles.app.data.upload

import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.model.UploadResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UploadSlugTest {
    private fun item(auto: Boolean = true, attempted: Boolean = false) = DurableUploadItem("item", "/private/staged", "note.txt", 1024, "expected-checksum", "Ab12Cd", isAutoSlug = auto, uploadAttempted = attempted)
    private fun existing(owner: Boolean = true, checksum: String = "expected-checksum", slug: String = "Ab12Cd") = ExistingUpload(owner, checksum, UploadResponse(id = "existing-id", slug = slug))

    @Test fun generatedSlugsMatchTheWebAlphabetAndSkipReservedRoutes() {
        repeat(2000) { val slug = generateUploadSlug(); assertEquals(6, slug.length); assertTrue(slug.all { it in UPLOAD_SLUG_ALPHABET }) }
        val sequence = "PUBLICAb12Cd".map(UPLOAD_SLUG_ALPHABET::indexOf).iterator()
        assertEquals("Ab12Cd", generateUploadSlug { sequence.next() })
    }
    @Test fun legacySerializationPreservesEveryStoredCandidate() {
        for (slug in listOf("e73c50e0c3724b7db69e1a3a3ee8f223", "my-custom-link")) {
            val legacy = """{"id":"item","path":"/private/staged","filename":"note.txt","size":1024,"checksum":"expected-checksum","slug":"$slug"}"""
            val restored = Json.decodeFromString<DurableUploadItem>(legacy)
            assertEquals(slug, restored.slug); assertFalse(restored.isAutoSlug); assertTrue(restored.uploadAttempted)
        }
    }
    @Test fun freshOwnOrForeignSameBytesCannotBeAdoptedAndRotationPersistsBeforePost() = runTest {
        for (owner in listOf(false, true)) {
            var saved = item(); var sent = 0
            val response = uploadWithStableSlug(saved, lookup = { if (it == "Ab12Cd") existing(owner) else null },
                persist = { next, previous -> assertEquals(saved, previous); saved = next; next }, generate = { "Zy98Xw" },
                upload = { candidate -> assertEquals(saved, candidate); assertTrue(candidate.uploadAttempted); assertEquals("Zy98Xw", candidate.slug); sent++; UploadResponse(id = "new-id", slug = candidate.slug) })
            assertEquals("new-id", response.finalId); assertEquals(1, sent); assertEquals(1, saved.slugCollisionCount)
            assertEquals(item().copy(slug = "Zy98Xw", slugCollisionCount = 1, uploadAttempted = true), saved)
        }
    }
    @Test fun customCollisionNeverChangesTheRequestedLink() = runTest {
        var writes = 0; var posts = 0
        val error = runCatching { uploadWithStableSlug(item(auto = false), lookup = { existing(false) },
            upload = { posts++; error("Must not post") }, persist = { next, _ -> writes++; next }) }.exceptionOrNull()
        assertTrue(error is IllegalStateException); assertEquals(0, writes); assertEquals(0, posts)
    }
    @Test fun confirmedPost409RotatesButLostAcknowledgementAdoptsOnlyOwnedMatchingFile() = runTest {
        for (owner in listOf(false, true)) {
            var saved = item(); var queries = 0; val posted = mutableListOf<String>()
            val response = uploadWithStableSlug(saved, lookup = { slug -> queries++; if (queries == 2) existing(owner, slug = slug) else null },
                persist = { next, _ -> saved = next; next }, generate = { "Zy98Xw" }, upload = { candidate ->
                    posted += candidate.slug
                    if (posted.size == 1) throw ApiException(409, "Slug already in use. Please choose another.")
                    UploadResponse(id = "new-id", slug = candidate.slug)
                })
            if (owner) { assertEquals("existing-id", response.finalId); assertEquals(listOf("Ab12Cd"), posted); assertEquals(0, saved.slugCollisionCount) }
            else { assertEquals("new-id", response.finalId); assertEquals(listOf("Ab12Cd", "Zy98Xw"), posted); assertEquals(1, saved.slugCollisionCount) }
        }
    }
    @Test fun uncertainRequestsAndCancellationKeepThePersistedCandidateForProcessRestart() = runTest {
        for (failure in listOf(IOException("Response lost"), ApiException(503, "Unavailable"), ApiException(409, "Unrelated conflict"), CancellationException("Stopped"))) {
            var saved = item()
            val error = runCatching { uploadWithStableSlug(saved, lookup = { null }, upload = { throw failure }, persist = { next, _ -> saved = next; next }, generate = { error("Never rotate on uncertainty") }) }.exceptionOrNull()
            assertSame(failure, error)
            val restored = Json.decodeFromString<DurableUploadItem>(Json.encodeToString(DurableUploadItem.serializer(), saved))
            assertEquals("Ab12Cd", restored.slug); assertTrue(restored.uploadAttempted); assertEquals(0, restored.slugCollisionCount)
            val recovered = uploadWithStableSlug(restored, lookup = { existing() }, upload = { error("Must reconcile without another POST") }, persist = { _, _ -> error("Must not rotate") })
            assertEquals("existing-id", recovered.finalId)
        }
    }
    @Test fun ownedWrongChecksumAndRepeatedCollisionsAreBounded() = runTest {
        var saved = item(attempted = true); var generated = 0
        val error = runCatching { uploadWithStableSlug(saved, lookup = { existing(checksum = "other-checksum") },
            upload = { error("Must not post") }, persist = { next, _ -> saved = next; next }, generate = { generated++; "N${generated.toString().padStart(5, '0')}" }) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("10 attempts")); assertEquals(9, generated); assertEquals(9, saved.slugCollisionCount)
    }
}
