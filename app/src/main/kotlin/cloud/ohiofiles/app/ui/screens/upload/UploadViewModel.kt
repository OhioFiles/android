package cloud.ohiofiles.app.ui.screens.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FolderItem
import cloud.ohiofiles.app.data.model.FolderListResponse
import cloud.ohiofiles.app.data.model.UploadResponse
import cloud.ohiofiles.app.data.repository.UploadSessionAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cloud.ohiofiles.app.data.upload.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SelectedFileInfo(
    val uri: Uri,
    val name: String,
    val size: Long,
    val formattedSize: String,
    val overrides: PerFileUploadOptions? = null
)
data class PerFileUploadOptions(val filename: String, val slug: String?, val options: UploadItemOptions)

sealed interface UploadUiState {
    object Idle : UploadUiState
    data class Uploading(val progress: Float, val filename: String, val phase: UploadPhase = UploadPhase.TRANSFERRING, val acknowledgedChunks: Int = 0, val totalChunks: Int = 0) : UploadUiState
    data class Success(val response: UploadResponse, val fullUrl: String) : UploadUiState
    data class Duplicate(val filename: String, val existingName: String) : UploadUiState
    data class Error(val message: String) : UploadUiState
}

class UploadViewModel(
    private val api: OhioApiClient,
    private val sessionRepo: UploadSessionAccess,
    private val durableContext: Context? = null
) : ViewModel() {

    private val navigationChannel = Channel<Unit>(Channel.BUFFERED)
    val navigationRequests = navigationChannel.receiveAsFlow()
    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    private val _selectedFile = MutableStateFlow<SelectedFileInfo?>(null)
    val selectedFile: StateFlow<SelectedFileInfo?> = _selectedFile.asStateFlow()

    private val _customSlug = MutableStateFlow("")
    val customSlug: StateFlow<String> = _customSlug.asStateFlow()

    private val _customFilename = MutableStateFlow("")
    val customFilename: StateFlow<String> = _customFilename.asStateFlow()

    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _isPublic = MutableStateFlow(false)
    val isPublic: StateFlow<Boolean> = _isPublic.asStateFlow()

    private val _isOneTime = MutableStateFlow(false)
    val isOneTime: StateFlow<Boolean> = _isOneTime.asStateFlow()

    private val _availableFolders = MutableStateFlow<List<FolderItem>>(emptyList())
    val availableFolders: StateFlow<List<FolderItem>> = _availableFolders.asStateFlow()

    private val _selectedFolder = MutableStateFlow<FolderItem?>(null)
    val selectedFolder: StateFlow<FolderItem?> = _selectedFolder.asStateFlow()

    private val restoreReady = CompletableDeferred<Unit>()
    private val selectionMutex = Mutex()
    private var preparationJob: Job? = null
    private var discardJob: Job? = null
    private var activeBatch: DurableUploadBatch? = null
    private var workObserver: Job? = null
    init {
        loadFolders()
        durableContext?.let { app -> viewModelScope.launch {
            sessionRepo.sessionKeyFlow.distinctUntilChanged().collect { key ->
                try {
                if (activeBatch != null && activeBatch?.sessionKey != key) {
                    _queue.value = emptyList(); _selectedFile.value = null; _completed.value = emptyList(); _uiState.value = UploadUiState.Idle
                }
                workObserver?.cancel(); activeBatch = null
                if (key != null) {
                    val repository = DurableUploadQueue(app)
                    val batch = withContext(Dispatchers.IO) { repository.active(sessionRepo.getServerUrl(), key)?.let(repository::read) }
                    if (batch != null) observeBatch(app, batch)
                }
                } finally { restoreReady.complete(Unit) }
            }
        } } ?: restoreReady.complete(Unit)
    }

    private val _queue = MutableStateFlow<List<SelectedFileInfo>>(emptyList())
    val queue = _queue.asStateFlow()
    private val _completed = MutableStateFlow<List<UploadUiState.Success>>(emptyList())
    val completed = _completed.asStateFlow()

    fun onFilesSelected(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            restoreReady.await()
            selectionMutex.withLock {
                val previous = activeBatch
                val running = previous?.let { batch ->
                    val repository = DurableUploadQueue(context)
                    repository.workId(batch.id)?.let { id -> withContext(Dispatchers.IO) { WorkManager.getInstance(context).getWorkInfoById(id).get() } }?.state?.isFinished == false
                } ?: (preparationJob?.isActive == true)
                if (running) {
                    android.widget.Toast.makeText(context, "Your upload is running. Add files when it finishes.", android.widget.Toast.LENGTH_LONG).show()
                    return@withLock
                }
                if (previous != null) {
                    val results = withContext(Dispatchers.IO) { DurableUploadQueue(context).results(previous.id) }
                    if (previous.items.any { item -> results.none { it.itemId == item.id && it.response != null } }) {
                        navigationChannel.trySend(Unit)
                        android.widget.Toast.makeText(context, "Retry or discard your previous upload queue before adding files.", android.widget.Toast.LENGTH_LONG).show()
                        return@withLock
                    }
                    workObserver?.cancel()
                    activeBatch = null
                    withContext(Dispatchers.IO) { DurableUploadQueue(context).discard(previous) }
                    _queue.value = emptyList()
                }
                _completed.value = emptyList()
                navigationChannel.trySend(Unit)
                for (uri in uris) {
                try {
                    val info = withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        var name = "selected_file"
                        var size = 0L
                        if (uri.scheme == "file") {
                            val file = java.io.File(uri.path ?: error("Missing file path"))
                            check(file.canRead()) { "This file cannot be read" }
                            name = file.name
                            size = file.length()
                        } else {
                            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (nameIndex >= 0) { name = cursor.getString(nameIndex) ?: name }
                                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) { size = cursor.getLong(sizeIndex) }
                                }
                            }
                            resolver.openInputStream(uri)?.use { } ?: error("This file cannot be read")
                        }
                        SelectedFileInfo(uri, name, size, if (size > 0) String.format("%.1f MB", size / 1048576.0) else "Size unavailable")
                    }
                    _queue.update { (it + info).distinctBy { file -> file.uri } }
                    _selectedFile.value = _queue.value.firstOrNull()
                    if (_uiState.value !is UploadUiState.Uploading) _uiState.value = UploadUiState.Idle
                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _uiState.value = UploadUiState.Error(e.message ?: "The selected file cannot be read") }
                }
            }
        }
    }
    fun onFileSelected(context: Context, uri: Uri) = onFilesSelected(context, listOf(uri))

    fun itemOptions(file: SelectedFileInfo): PerFileUploadOptions = file.overrides ?: PerFileUploadOptions(
        if (_queue.value.size == 1) _customFilename.value.ifBlank { file.name } else file.name,
        if (_queue.value.size == 1) _customSlug.value.ifBlank { null } else null,
        UploadItemOptions(_selectedFolder.value?.id, _isPublic.value, _isOneTime.value, _password.value.ifBlank { null }))
    fun editableOptions() = activeBatch == null
    fun setItemOptions(uri: Uri, options: PerFileUploadOptions) {
        if (activeBatch != null) return
        _queue.update { list -> list.map { if (it.uri == uri) it.copy(overrides = options) else it } }
        _selectedFile.value = _queue.value.firstOrNull()
    }
    fun setCustomSlug(slug: String) { _customSlug.value = slug }
    fun setCustomFilename(name: String) { _customFilename.value = name }
    fun setPassword(pw: String) { _password.value = pw }
    fun setIsPublic(pub: Boolean) { _isPublic.value = pub }
    fun setIsOneTime(oneTime: Boolean) { _isOneTime.value = oneTime }
    fun setSelectedFolder(folder: FolderItem?) { _selectedFolder.value = folder }

    fun clearSelection() {
        if (discardJob?.isActive == true) return
        preparationJob?.cancel()
        val batch = activeBatch; val app = durableContext
        if (batch != null && app != null) {
            workObserver?.cancel()
            discardJob = viewModelScope.launch {
                _uiState.value = UploadUiState.Uploading(0f, "Discarding the queued transfer…", UploadPhase.PREPARING)
                try { DurableUploadQueue(app).discard(batch); resetSelection() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { _uiState.value = UploadUiState.Error("Couldn't discard the remote transfer. Your staged files are kept. Reconnect and try again.") }
            }
        } else resetSelection()
    }
    private fun resetSelection() {
        workObserver?.cancel(); activeBatch = null
        _selectedFile.value = null; _queue.value = emptyList(); _completed.value = emptyList(); _uiState.value = UploadUiState.Idle
        _customSlug.value = ""; _customFilename.value = ""; _password.value = ""; _isPublic.value = false; _isOneTime.value = false
    }

    suspend fun listUploadFolders(parent: String?): Result<FolderListResponse> = api.listFolders(sessionRepo.getSessionKey(), parent)
    fun loadFolders() {
        viewModelScope.launch {
            val key = sessionRepo.getSessionKey()
            val res = api.listFolders(key)
            if (res.isSuccess) {
                _availableFolders.value = res.getOrNull()?.folders.orEmpty()
            }
        }
    }

    private fun observeBatch(context: Context, batch: DurableUploadBatch) {
        activeBatch = batch
        val repository = DurableUploadQueue(context)
        val workId = repository.workId(batch.id) ?: repository.enqueue(batch)
        workObserver?.cancel()
        workObserver = viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).collect { work ->
                if (work == null) return@collect
                val results = withContext(Dispatchers.IO) { repository.results(batch.id) }
                val successes = results.mapNotNull { record -> record.response?.let { UploadUiState.Success(it, it.finalUrl ?: api.getPublicFileUrl(it.finalSlug.orEmpty())) } }
                _completed.value = successes
                val pending = batch.items.filter { item -> results.none { it.itemId == item.id && it.response != null } }
                _queue.value = pending.map { SelectedFileInfo(Uri.fromFile(java.io.File(it.path)), it.filename, it.size, String.format("%.1f MB", it.size / 1048576.0)) }
                _selectedFile.value = _queue.value.firstOrNull()
                _isPublic.value = batch.isPublic; _isOneTime.value = batch.isOneTime; _password.value = batch.password.orEmpty()
                when (work.state) {
                    WorkInfo.State.SUCCEEDED -> _uiState.value = successes.lastOrNull() ?: UploadUiState.Error("No upload result was saved")
                    WorkInfo.State.FAILED -> {
                        val duplicate = results.firstOrNull { it.duplicate != null && it.response == null && it.duplicateDecision == null }
                        _uiState.value = if (duplicate != null) UploadUiState.Duplicate(batch.items.firstOrNull { it.id == duplicate.itemId }?.filename ?: "Selected file", duplicate.duplicate!!.finalFilename ?: "Existing file") else UploadUiState.Error(work.outputData.getString("error") ?: results.lastOrNull { it.error != null }?.error ?: "Upload failed. Retry to resume the remaining files.")
                    }
                    WorkInfo.State.CANCELLED -> _uiState.value = UploadUiState.Error("Upload cancelled. Retry to resume the remaining files.")
                    else -> {
                        val phase = uploadPhase(work.state.name, work.progress.getString("phase"), work.runAttemptCount, work.progress.getFloat("progress", 0f))
                        val fraction = if (phase in listOf(UploadPhase.QUEUED, UploadPhase.RETRYING)) 0f else work.progress.getFloat("progress", 0f)
                        _uiState.value = UploadUiState.Uploading(fraction, work.progress.getString("filename") ?: pending.firstOrNull()?.filename ?: "Your files", phase, work.progress.getInt("acknowledgedChunks", 0), work.progress.getInt("totalChunks", 0))
                    }
                }
            }
        }
    }
    fun resolveDuplicate(skip: Boolean) {
        val batch = activeBatch ?: return; val app = durableContext ?: return
        viewModelScope.launch {
            val repository = DurableUploadQueue(app)
            val record = withContext(Dispatchers.IO) { repository.results(batch.id).firstOrNull { it.duplicate != null && it.response == null && it.duplicateDecision == null } } ?: return@launch
            withContext(Dispatchers.IO) {
                repository.record(batch.id, if (skip) DurableUploadResult(record.itemId, response = record.duplicate) else DurableUploadResult(record.itemId, duplicateDecision = "upload"))
                if (skip) batch.items.firstOrNull { it.id == record.itemId }?.let { java.io.File(it.path).delete() }
                repository.enqueue(repository.read(batch.id) ?: batch)
            }
            observeBatch(app, repository.read(batch.id) ?: batch)
        }
    }
    fun cancelUpload() {
        if (discardJob?.isActive == true) { discardJob?.cancel(); _uiState.value = UploadUiState.Error("Discard cancelled. Your queued files are kept."); return }
        if (activeBatch == null) { preparationJob?.cancel(); _uiState.value = UploadUiState.Error("Upload cancelled. Your selected files are still ready to retry.") }
        activeBatch?.let { batch -> durableContext?.let { DurableUploadQueue(it).cancel(batch.id) } }
    }
    fun startUpload(context: Context) {
        if (_uiState.value is UploadUiState.Uploading) return
        val app = context.applicationContext
        val repository = DurableUploadQueue(app)
        activeBatch?.let { existing -> repository.enqueue(existing); observeBatch(app, existing); return }
        val selected = _queue.value
        if (selected.isEmpty()) return
        preparationJob = viewModelScope.launch {
            _uiState.value = UploadUiState.Uploading(0f, "${selected.size} selected files", UploadPhase.PREPARING)
            try {
                val key = sessionRepo.getSessionKey() ?: sessionRepo.ensureSession().getOrThrow().key
                val server = sessionRepo.getServerUrl()
                val single = selected.size == 1
                val sources = selected.map { file -> val options = itemOptions(file); Triple(file.uri, options.filename, options.slug) }
                val batch = repository.prepare(server, key, sources, _selectedFolder.value?.id, _isPublic.value, _isOneTime.value, _password.value.ifBlank { null }, options = selected.map { itemOptions(it).options })
                repository.enqueue(batch)
                observeBatch(app, batch)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _uiState.value = UploadUiState.Error(e.message ?: "Could not prepare uploads") }
        }
    }
}
