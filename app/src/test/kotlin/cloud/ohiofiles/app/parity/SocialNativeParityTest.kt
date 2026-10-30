package cloud.ohiofiles.app.parity

import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileListResponse
import cloud.ohiofiles.app.ui.screens.community.communityHasMoreFiles
import cloud.ohiofiles.app.ui.screens.community.communityPublicFileQuery
import cloud.ohiofiles.app.ui.screens.community.fullCommunityPost
import cloud.ohiofiles.app.ui.screens.nativefeatures.blobatarMessage
import cloud.ohiofiles.app.ui.screens.nativefeatures.blobatarMood
import cloud.ohiofiles.app.ui.screens.profile.existingDeviceQuery
import cloud.ohiofiles.app.ui.screens.profile.insightLong
import cloud.ohiofiles.app.ui.screens.profile.revokeOtherDevicesBody
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SocialNativeParityTest {
    @Test fun publicDiscoveryDoesNotScopeToMyAccountAndCanLoadBeyondOneHundred() {
        assertFalse(communityPublicFileQuery(2).containsKey("sessionKey"))
        assertEquals("2", communityPublicFileQuery(2)["page"])
        assertEquals("true", communityPublicFileQuery(2)["public"])
        assertTrue(communityHasMoreFiles(FileListResponse(total = 201, page = 2, limit = 100, totalPages = 3)))
        assertFalse(communityHasMoreFiles(FileListResponse(total = 201, page = 3, limit = 100, totalPages = 3)))
    }
    @Test fun detailUsesCompleteServerPostInsteadOfTruncatedFeedContent() {
        val api = OhioApiClient()
        val full = "Full content. ".repeat(2000) + "final paragraph"
        val payload = buildJsonObject { putJsonObject("post") { put("id", "post-id"); put("slug", "qa-post"); put("title", "Complete post"); put("content", full) } }
        val actual = fullCommunityPost(api, payload)
        assertEquals(full, actual?.content)
        assertTrue(actual!!.content.length > 12000)
    }
    @Test fun knownStickerWireFormatAndUnknownMoodRemainSafe() {
        assertEquals("[sticker:blobatar:happy]", blobatarMessage("happy"))
        assertEquals("thinking", blobatarMood("[sticker:blobatar:thinking]"))
        assertNull(blobatarMood("[sticker:blobatar:unrecognized]"))
        assertNull(blobatarMood("a normal message"))
    }
    @Test fun storageUnknownDoesNotBecomeZeroAndDeviceRevocationKeepsPersistedPhoneId() {
        val storage = buildJsonObject { put("totalBytes", JsonNull); put("fileCount", 0) }
        assertNull(storage.insightLong("totalBytes")); assertEquals(0L, storage.insightLong("fileCount"))
        val query = existingDeviceQuery("QA-only", "existing-device-123")
        assertEquals("existing-device-123", query["deviceId"])
        val payload = revokeOtherDevicesBody(OhioApiClient(), "QA-only", "existing-device-123")
        assertEquals("existing-device-123", payload["deviceId"]?.jsonPrimitive?.content)
    }
}
