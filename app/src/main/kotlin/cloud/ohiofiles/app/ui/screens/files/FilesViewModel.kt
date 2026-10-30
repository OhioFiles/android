package cloud.ohiofiles.app.ui.screens.files

import android.content.Context
import android.net.Uri
import kotlinx.serialization.json.*
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
    private var allFolders = emptyList<FolderItem>()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var deleted = emptyList<Pair<String, Boolean>>()
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
                allFolders = folders.folders
                _uiState.value = FilesUiState.Success(sorted(), sortedFolders(), folders.currentFolder, folders.path.orEmpty(), query, grid, folderId = folderId)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { _uiState.value = FilesUiState.Error(e.message ?: "Could not load your files") }
            }
        }
    }
    private fun sorted(): List<FileItem> {
        val files = allFiles.filter { it.displayName.contains(query, true) }
        return when (order) { FileSortOrder.DATE_DESC -> files.sortedByDescending { it.uploadedAt }; FileSortOrder.NAME_ASC -> files.sortedBy { it.displayName.lowercase() }; FileSortOrder.SIZE_DESC -> files.sortedByDescending { it.size } }
    }
    private fun sortedFolders() = when (order) {
        FileSortOrder.DATE_DESC -> allFolders.sortedByDescending { it.createdAt }
        FileSortOrder.NAME_ASC -> allFolders.sortedBy { it.name.lowercase() }
        FileSortOrder.SIZE_DESC -> allFolders.sortedByDescending { it.fileCount + it.childCount }
    }
    suspend fun saveFolderProperties(folder: FolderItem?, properties: JsonObject): Result<Unit> {
        val key = sessionRepo.getSessionKey() ?: return Result.failure(IllegalStateException("Your session is unavailable"))
        val slug = properties["slug"]?.jsonPrimitive?.contentOrNull
        if (slug != null) {
            val check = api.request("folders/check-slug", query = mapOf("slug" to slug, "folderId" to folder?.id))
            if (check.isFailure) return Result.failure(check.exceptionOrNull()!!)
            if (check.getOrThrow().jsonObject["available"]?.jsonPrimitive?.booleanOrNull != true) return Result.failure(IllegalArgumentException(check.getOrThrow().jsonObject["reason"]?.jsonPrimitive?.contentOrNull ?: "This folder link is unavailable"))
        }
        val body = if (folder == null) JsonObject(properties + ("parentId" to (folderId?.let(::JsonPrimitive) ?: JsonNull))) else properties
        return api.configureFolder(folder?.id, key, body).map { loadData(); Unit }
    }
    suspend fun revealPassword(id: String, folder: Boolean): Result<String> {
        val key = sessionRepo.getSessionKey() ?: return Result.failure(IllegalStateException("Your session is unavailable"))
        return api.request("${if (folder) "folders" else "files"}/$id/password", query = mapOf("sessionKey" to key)).mapCatching { it.jsonObject["password"]?.jsonPrimitive?.contentOrNull ?: error("No password is available") }
    }
    fun setVisibility(file: FileItem, public: Boolean) = action { api.updateFile(file.id, it, isPublic = public) }
    suspend fun exportFiles(context: Context, destination: Uri, ids: List<String>): Result<Long> {
        val key = sessionRepo.getSessionKey() ?: return Result.failure(IllegalStateException("Your session is unavailable"))
        return api.exportSelectedFiles(context, destination, ids, key)
    }
    suspend fun bulk(files: List<FileItem>, folders: List<FolderItem>, operation: String, destination: String? = null): String {
        val key = sessionRepo.getSessionKey() ?: return "Your session is unavailable"
        var completed = 0; val failures = mutableListOf<String>(); val undone = mutableListOf<Pair<String, Boolean>>()
        try {
        if (operation == "move" && files.isNotEmpty()) {
            val result = api.moveFiles(files.map { it.id }, destination, key)
            if (result.isSuccess) { val moved = result.getOrThrow().jsonObject["movedCount"]?.jsonPrimitive?.intOrNull ?: 0; completed += moved; if (moved < files.size) failures += "${files.size - moved} files were not moved" } else failures += result.exceptionOrNull()?.message ?: "Files couldn't be moved"
        } else for (file in files) {
            currentCoroutineContext().ensureActive()
            val result = if (operation == "delete") api.deleteFile(file.slug, key) else api.updateFile(file.id, key, isPublic = operation == "public")
            if (result.isSuccess) { completed++; if (operation == "delete") { undone += file.id to false; deleted = undone.toList() } } else failures += "${file.displayName}: ${result.exceptionOrNull()?.message ?: "Action failed"}"
        }
        for (folder in folders) {
            currentCoroutineContext().ensureActive()
            val result = when (operation) {
                "move" -> api.updateFolder(folder.id, key, parentId = destination, move = true)
                "delete" -> api.deleteFolder(folder.id, key)
                else -> api.configureFolder(folder.id, key, api.body("isPublic" to (operation == "public")))
            }
            if (result.isSuccess) { completed++; if (operation == "delete") { undone += folder.id to true; deleted = undone.toList() } } else failures += "${folder.name}: ${result.exceptionOrNull()?.message ?: "Action failed"}"
        }
        } finally {
            if (undone.isNotEmpty()) _undoMessage.value = "${undone.size} items deleted"
            loadData()
        }
        return "$completed of ${files.size + folders.size} updated" + if (failures.isEmpty()) "" else ". ${failures.joinToString("; ")}"
    }
    fun onSearch(value: String) { query = value; searchJob?.cancel(); searchJob = viewModelScope.launch { delay(250); publish() } }
    private fun publish() { (_uiState.value as? FilesUiState.Success)?.let { _uiState.value = it.copy(files = sorted(), folders = sortedFolders(), searchQuery = query, isGrid = grid) } }
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
    fun deleteFile(file: FileItem) = action { key -> api.deleteFile(file.slug, key).onSuccess { deleted = listOf(file.id to false); _undoMessage.value = "File deleted" } }
    fun deleteFolder(folder: FolderItem) = action { key -> api.deleteFolder(folder.id, key).onSuccess { deleted = listOf(folder.id to true); _undoMessage.value = "Folder deleted" } }
    fun undoDelete() { viewModelScope.launch {
        val key = sessionRepo.getSessionKey() ?: return@launch
        val snapshot = deleted.toList()
        try {
            for ((id, folder) in snapshot) {
                val result = if (folder) api.undoDeleteFolder(id, key) else api.undoDeleteFile(id, key)
                if (result.isSuccess) deleted = deleted.filterNot { it.first == id && it.second == folder }
            }
            _undoMessage.value = if (deleted.isEmpty()) "Items restored" else "${deleted.size} items couldn't be restored. Try Undo again."
        } finally { loadData() }
    } }
    fun clearUndoMessage() { _undoMessage.value = null }
    fun toggleBookmark(file: FileItem) = action { if (file.isSaved) api.removeBookmark(file.slug, it) else api.saveBookmark(file.slug, it) }
    suspend fun destinationFolders(parent: String?): Result<FolderListResponse> = api.listFolders(sessionRepo.getSessionKey(), parent)
    fun getDownloadUrl(file: FileItem) = api.getFileDownloadUrl(file.id, sessionKey, file.slug)
    fun getDownloadUrl(id: String) = api.getFileDownloadUrl(id, sessionKey)
    fun getPublicUrl(slug: String) = api.getPublicFileUrl(slug)
    fun getFolderDownloadUrl(folder: FolderItem) = api.url("folders/${folder.id}/download-zip", mapOf("sessionKey" to sessionKey))
    fun getFolderUrl(folder: FolderItem) = api.getPublicFolderUrl(folder)
}
