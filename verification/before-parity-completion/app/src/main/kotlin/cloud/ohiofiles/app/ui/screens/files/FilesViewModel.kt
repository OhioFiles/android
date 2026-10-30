package cloud.ohiofiles.app.ui.screens.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed interface FilesUiState {
    data object Loading : FilesUiState
    data class Success(val files: List<FileItem>, val folders: List<FolderItem>, val currentFolder: FolderItem? = null,
        val breadcrumbs: List<FolderPathItem> = emptyList(), val searchQuery: String = "", val isGrid: Boolean = true,
        val refreshing: Boolean = false, val folderId: String? = null) : FilesUiState
    data class Error(val message: String) : FilesUiState
}
enum class FileSortOrder { DATE_DESC, NAME_ASC, SIZE_DESC }
class FilesViewModel(private val api: OhioApiClient, private val sessionRepo: SessionRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<FilesUiState>(FilesUiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _undoMessage = MutableStateFlow<String?>(null)
    val undoMessage = _undoMessage.asStateFlow()
    private var folderId: String? = null
    private var query = ""
    private var grid = true
    private var order = FileSortOrder.DATE_DESC
    private var allFiles = emptyList<FileItem>()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var deleted: Pair<String, Boolean>? = null
    private var sessionKey: String? = null
    init { loadData() }
    fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val previous = _uiState.value as? FilesUiState.Success
            if (previous != null && previous.folderId == folderId) _uiState.value = previous.copy(refreshing = true)
            else _uiState.value = FilesUiState.Loading
            val key = sessionRepo.ensureSession().getOrNull()?.key ?: sessionRepo.getSessionKey()
            sessionKey = key
            if (key.isNullOrBlank()) { _uiState.value = FilesUiState.Error("Connect to the server to start your session."); return@launch }
            supervisorScope {
            val fileTask = async {
                val first = api.listFiles(key, folderId).getOrThrow()
                buildList { addAll(first.files); for (page in 2..first.totalPages) { ensureActive(); addAll(api.listFiles(key, folderId, page = page).getOrThrow().files) } }
            }
            val folderTask = async { api.listFolders(key, folderId).getOrThrow() }
            try {
                allFiles = fileTask.await()
                val folders = folderTask.await()
                _uiState.value = FilesUiState.Success(sorted(), folders.folders, folders.currentFolder, folders.path.orEmpty(), query, grid, folderId = folderId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _uiState.value = FilesUiState.Error(e.message ?: "Could not load your files") }
            }
        }
    }
    private fun sorted(): List<FileItem> {
        val files = allFiles.filter { it.displayName.contains(query, true) }
        return when (order) { FileSortOrder.DATE_DESC -> files.sortedByDescending { it.uploadedAt }; FileSortOrder.NAME_ASC -> files.sortedBy { it.displayName.lowercase() }; FileSortOrder.SIZE_DESC -> files.sortedByDescending { it.size } }
    }
    fun onSearch(value: String) { query = value; searchJob?.cancel(); searchJob = viewModelScope.launch { delay(250); publish() } }
    private fun publish() { (_uiState.value as? FilesUiState.Success)?.let { _uiState.value = it.copy(files = sorted(), searchQuery = query, isGrid = grid) } }
    fun toggleViewMode() { grid = !grid; publish() }
    fun setSortOrder(value: FileSortOrder) { order = value; publish() }
    fun openFolder(folder: FolderItem) { folderId = folder.id; query = ""; loadData() }
    fun navigateToBreadcrumb(id: String?) { folderId = id; query = ""; loadData() }
    fun navigateUp(): Boolean { if (folderId == null) return false; val state = _uiState.value as? FilesUiState.Success; folderId = state?.breadcrumbs?.filter { it.id != folderId }?.lastOrNull()?.id; loadData(); return true }
    private fun action(block: suspend (String) -> Result<*>) { viewModelScope.launch { val key = sessionRepo.getSessionKey() ?: return@launch; val result = block(key); if (result.isSuccess) loadData() else _undoMessage.value = result.exceptionOrNull()?.message ?: "Action failed" } }
    fun createFolder(name: String, isPublic: Boolean = false, password: String? = null) = action { api.createFolder(it, name.trim(), folderId, isPublic, password) }
    fun renameFile(file: FileItem, name: String) = action { api.updateFile(file.id, it, name = name) }
    fun renameFolder(folder: FolderItem, name: String) = action { api.updateFolder(folder.id, it, name = name) }
    fun moveFile(file: FileItem, destination: String?) = action { api.updateFile(file.id, it, folderId = destination, move = true) }
    fun moveFolder(folder: FolderItem, destination: String?) = action { api.updateFolder(folder.id, it, parentId = destination, move = true) }
    fun deleteFile(file: FileItem) = action { key -> api.deleteFile(file.slug, key).onSuccess { deleted = file.id to false; _undoMessage.value = "File deleted" } }
    fun deleteFolder(folder: FolderItem) = action { key -> api.deleteFolder(folder.id, key).onSuccess { deleted = folder.id to true; _undoMessage.value = "Folder deleted" } }
    fun undoDelete() = action { key -> deleted?.let { (id, folder) -> if (folder) api.undoDeleteFolder(id, key) else api.undoDeleteFile(id, key) } ?: Result.success(true) }
    fun clearUndoMessage() { _undoMessage.value = null }
    fun toggleBookmark(file: FileItem) = action { if (file.isSaved) api.removeBookmark(file.slug, it) else api.saveBookmark(file.slug, it) }
    suspend fun destinationFolders(parent: String?): Result<FolderListResponse> = api.listFolders(sessionRepo.getSessionKey(), parent)
    fun getDownloadUrl(file: FileItem) = api.getFileDownloadUrl(file.id, sessionKey, file.slug)
    fun getDownloadUrl(id: String) = api.getFileDownloadUrl(id, sessionKey)
    fun getPublicUrl(slug: String) = api.getPublicFileUrl(slug)
    fun getFolderDownloadUrl(folder: FolderItem) = api.url("folders/${folder.id}/download-zip", mapOf("sessionKey" to sessionKey))
    fun getFolderUrl(folder: FolderItem) = api.getPublicFolderUrl(folder)
}
