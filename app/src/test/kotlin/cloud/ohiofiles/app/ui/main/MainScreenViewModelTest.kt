package cloud.ohiofiles.app.ui.main

import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class MainScreenViewModelTest {
    @Test fun documentationLinksFollowThePublicFolderContract() {
        assertEquals("handbook", cloud.ohiofiles.app.platform.publicFolderLinkToken(listOf("docs", "handbook")))
        assertEquals("handbook", cloud.ohiofiles.app.platform.publicFolderLinkToken(listOf("f", "handbook")))
        assertNull(cloud.ohiofiles.app.platform.publicFolderLinkToken(listOf("file-slug")))
        assertNull(cloud.ohiofiles.app.platform.publicFolderLinkToken(listOf("files", "file-slug")))
    }

    @Test fun sessionJsonPreservesUnicodeQuotesAndNewlines() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"session":{"id":"id","key":"key","nickname":"Kira"}}"""))
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            val nickname = "Kira \"Ω\"\nnew line\t!"
            assertTrue(api.updateNickname("key", nickname).isSuccess)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(nickname, body["nickname"]!!.jsonPrimitive.content)
        }
    }
    @Test fun bookmarkAuthenticationIsInJsonBody() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            assertTrue(api.saveBookmark("slug", "key-with-characters").isSuccess)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/files/slug/save", request.path)
            assertEquals("key-with-characters", Json.parseToJsonElement(request.body.readUtf8()).jsonObject["sessionKey"]!!.jsonPrimitive.content)
        }
    }
    @Test fun rootFilesAndFoldersUseExplicitRootScope() = runTest {
        MockWebServer().use { server ->
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            server.enqueue(MockResponse().setBody("""{"files":[],"total":0}""")); api.listFiles("key")
            assertEquals("root", server.takeRequest().requestUrl!!.queryParameter("folderId"))
            server.enqueue(MockResponse().setBody("""{"folders":[]}""")); api.listFolders("key")
            val url = server.takeRequest().requestUrl!!
            assertEquals("root", url.queryParameter("parentId")); assertEquals("true", url.queryParameter("includePath"))
        }
    }
    @Test fun unauthorizedMutationsAreFailures() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Not the owner"}"""))
            val api = OhioApiClient(server.url("/").toString().trimEnd('/'))
            val result = api.deleteFile("slug", "key")
            assertTrue(result.isFailure); assertEquals("Not the owner", result.exceptionOrNull()?.message)
        }
    }
    @Test fun protectedDownloadsEncodeSessionQueryWithoutInjection() {
        val api = OhioApiClient()
        val url = api.getFileDownloadUrl("abc", "x&public=true").toHttpUrl()
        assertEquals("x&public=true", url.queryParameter("sessionKey")); assertNull(url.queryParameter("public"))
    }
    @Test fun normalizedSocialAndFileModelsSupportBackendResponseAliases() {
        val post = SocialPost("id", "slug", "Title", "Content", reactions = SocialReactions(likes = 9), viewerReaction = "LIKE", viewerReposted = true)
        assertEquals(9, post.totalLikes); assertEquals("LIKE", post.reaction); assertTrue(post.isReposted)
        assertTrue(FileItem("id", "slug", filename = "DOCUMENT.PDF").isPdf)
        assertEquals("nice.txt", FileItem("id", "slug", filename = "stored", originalName = "nice.txt").displayName)
    }
}
