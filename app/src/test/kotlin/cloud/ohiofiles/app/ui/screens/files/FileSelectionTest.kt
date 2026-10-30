package cloud.ohiofiles.app.ui.screens.files

import cloud.ohiofiles.app.data.model.*
import org.junit.Assert.*
import org.junit.Test

class FileSelectionTest {
    private val state = FilesUiState.Success(listOf(
        FileItem("image", "img123", "keep-photo.png", mimeType = "image/png", isOwner = true),
        FileItem("hidden", "doc123", "unrelated.pdf", mimeType = "application/pdf", isOwner = true),
        FileItem("shared", "shr123", "keep-shared.png", mimeType = "image/png", isShared = true, shareAccess = "VIEW"),
        FileItem("saved", "sav123", "saved.txt", isSaved = true)), listOf(FolderItem("keep", "Keep folder"), FolderItem("unseen", "Unrelated folder"), FolderItem("shared-folder", "Shared folder", isShared = true)))
    @Test fun selectingSearchResultsCannotSelectUnseenOwnedFolders() {
        val visible = visibleFileRows(state, 1, "Keep", "All", false)
        assertEquals(setOf("keep"), visible.ids)
        assertEquals(listOf("keep"), visible.snapshot(listOf("keep", "unseen", "image")).second.map { it.id })
    }
    @Test fun typeSharedAndSavedScopesDriveTheSameRenderAndSelectionSet() {
        assertEquals(setOf("image", "shared"), visibleFileRows(state, 0, "", "Images", false).ids)
        assertEquals(setOf("shared", "shared-folder"), visibleFileRows(state, 0, "", "Images", true).ids)
        assertEquals(setOf("saved"), visibleFileRows(state, 2, "", "All", false).ids)
    }
    @Test fun confirmationSnapshotIsImmutableAndHiddenSelectionsAreDiscarded() {
        val selected = state.files.map { it.id } + state.folders.map { it.id }
        val first = visibleFileRows(state, 1, "Keep", "All", false).snapshot(selected)
        val changed = visibleFileRows(state, 0, "shared", "Images", false)
        assertEquals(setOf("shared"), selected.filter { it in changed.ids }.toSet())
        assertEquals(listOf("keep"), first.second.map { it.id })
        assertTrue(first.first.isEmpty())
    }
}
