package cloud.ohiofiles.app.parity

import cloud.ohiofiles.app.ui.screens.nativefeatures.ChatDraftState
import cloud.ohiofiles.app.ui.screens.nativefeatures.ChatMediaState
import cloud.ohiofiles.app.ui.screens.nativefeatures.ChatUpload
import cloud.ohiofiles.app.ui.screens.nativefeatures.hasUnreadyUploads
import cloud.ohiofiles.app.ui.screens.nativefeatures.readyFileIds
import org.junit.Assert.*
import org.junit.Test

class ChatDraftIsolationTest {
    @Test fun changingConversationAndRestoringSavedDraftsKeepsEachAttachmentSeparate() {
        val saved = mutableMapOf<String, String>()
        val one = ChatDraftState(saved, "peer:one.post", null as String?, { it }, { it })
        val two = ChatDraftState(saved, "group:two.post", null as String?, { it }, { it })
        one.value = "qa-post-one"; two.value = "qa-post-two"
        val recreated = ChatDraftState(saved.toMutableMap(), "peer:one.post", null as String?, { it }, { it })
        assertEquals("qa-post-one", recreated.value); assertEquals("qa-post-two", two.value)
        one.value = null; assertNull(one.value); assertEquals("qa-post-two", two.value)
    }
    @Test fun onlyVerifiedReadyFilesFromSelectedThreadCanEnterSendPayload() {
        val state = ChatMediaState(uploads = listOf(ChatUpload("one", "peer:one", "uri1", "File 1", "Ready", fileId = "one-id"), ChatUpload("two", "peer:two", "uri2", "File 2", "Ready", fileId = "two-id"), ChatUpload("three", "peer:one", "uri3", "File 3", "Failed", error = "Offline")))
        assertEquals(setOf("one-id"), state.readyFileIds("peer:one")); assertTrue(state.hasUnreadyUploads("peer:one")); assertFalse(state.hasUnreadyUploads("peer:two"))
    }
}
