package cloud.ohiofiles.app.ui.screens.files

import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.model.FolderItem

internal data class FileRows(val files: List<FileItem>, val folders: List<FolderItem>) {
    val ids: Set<String> get() = (files.map { it.id } + folders.map { it.id }).toSet()
    fun snapshot(selected: Collection<String>) = files.filter { it.id in selected }.toList() to folders.filter { it.id in selected }.toList()
}
internal fun visibleFileRows(current: FilesUiState.Success?, section: Int, search: String, type: String, sharedOnly: Boolean): FileRows {
    val folders = current?.folders.orEmpty().filter { (section == 1 || current?.folderId != null || sharedOnly) && it.name.contains(search, true) && (!sharedOnly || it.isShared) }
    val files = current?.files.orEmpty().filter { section != 1 && it.displayName.contains(search, true) && (section != 2 || it.isSaved) && (!sharedOnly || it.isShared) && when (type) {
        "Images" -> it.isImage; "Video" -> it.isVideo; "Audio" -> it.isAudio; "Documents" -> it.isPdf || it.isTextOrCode; else -> true
    } }
    return FileRows(files, folders)
}
