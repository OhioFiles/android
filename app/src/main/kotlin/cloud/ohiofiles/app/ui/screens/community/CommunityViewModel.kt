package cloud.ohiofiles.app.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

sealed interface CommunityUiState {
    data object Loading : CommunityUiState
    data class Success(val posts: List<SocialPost>, val publicFiles: List<FileItem>, val selectedTag: String? = null, val hasMore: Boolean = false, val publicFolders: List<JsonObject> = emptyList(), val hasMoreFiles: Boolean = false, val discoveryError: String? = null) : CommunityUiState
    data class Error(val message: String) : CommunityUiState
}

class CommunityViewModel(val api: OhioApiClient, val sessionRepo: SessionRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<CommunityUiState>(CommunityUiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _mutating = MutableStateFlow(false)
    val mutating = _mutating.asStateFlow()
    private val _attachments = MutableStateFlow<List<SocialAttachment>>(emptyList())
    val attachments = _attachments.asStateFlow()
    private var selectedTag: String? = null
    private var query = ""
    private var cursor: String? = null
    private var feedJob: Job? = null
    private var loadingMore = false
    private var discoveryLoaded = false
    private var publicFiles = emptyList<FileItem>()
    private var publicFolders = emptyList<JsonObject>()
    private var publicPage = 1
    private var hasMoreFiles = false
    private var discoveryError: String? = null
    private var loadingFiles = false
    init { loadFeed() }
    fun dismissMessage() { _message.value = null }
    fun search(value: String) { if (query != value) { query = value.take(80); loadFeed() } }
    fun filterByTag(tag: String?) { selectedTag = tag; loadFeed() }
    fun loadFeed() {
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _uiState.value = CommunityUiState.Loading
            val key = sessionRepo.getSessionKey()
            val result = api.request("social/posts", query = mapOf("sessionKey" to key, "tag" to selectedTag, "query" to query, "limit" to "24"))
            if (!discoveryLoaded) {
                discoveryError = null
                api.request("files", query = communityPublicFileQuery(1)).onSuccess {
                    val page = api.json.decodeFromJsonElement<FileListResponse>(it)
                    publicFiles = page.files.filter { file -> file.isPublic }; publicPage = 1
                    hasMoreFiles = communityHasMoreFiles(page)
                }.onFailure { discoveryError = it.message ?: "Could not load public files" }
                api.request("public-folders").onSuccess { publicFolders = it.jsonObject["folders"]?.jsonArray?.map { folder -> folder.jsonObject }.orEmpty() }
                    .onFailure { discoveryError = it.message ?: "Could not load public folders" }
                discoveryLoaded = discoveryError == null
            }
            result.onSuccess {
                val page = api.json.decodeFromJsonElement<SocialPostsResponse>(it)
                cursor = page.nextCursor
                _uiState.value = CommunityUiState.Success(page.posts, publicFiles, selectedTag, cursor != null, publicFolders, hasMoreFiles, discoveryError)
            }.onFailure { _uiState.value = CommunityUiState.Error(it.message ?: "Could not load community") }
        }
    }
    fun loadMore() {
        val next = cursor ?: return
        val startedQuery = query; val startedTag = selectedTag
        if (loadingMore) return
        viewModelScope.launch {
            loadingMore = true
            try {
                api.request("social/posts", query = mapOf("sessionKey" to sessionRepo.getSessionKey(), "tag" to selectedTag, "query" to query, "cursor" to next, "limit" to "24")).onSuccess {
                    val page = api.json.decodeFromJsonElement<SocialPostsResponse>(it)
                    val current = _uiState.value as? CommunityUiState.Success ?: return@onSuccess
                    if (startedQuery != query || startedTag != selectedTag) return@onSuccess
                    cursor = page.nextCursor
                    _uiState.value = current.copy(posts = (current.posts + page.posts).distinctBy { post -> post.id }, hasMore = cursor != null)
                }.onFailure { _message.value = it.message }
            } finally { loadingMore = false }
        }
    }
    fun loadMorePublicFiles() {
        if (loadingFiles || !hasMoreFiles) return
        viewModelScope.launch {
            loadingFiles = true
            try {
                api.request("files", query = communityPublicFileQuery(publicPage + 1)).onSuccess {
                    val page = api.json.decodeFromJsonElement<FileListResponse>(it)
                    publicPage += 1; publicFiles = (publicFiles + page.files.filter { file -> file.isPublic }).distinctBy { file -> file.id }
                    hasMoreFiles = communityHasMoreFiles(page)
                    val current = _uiState.value as? CommunityUiState.Success
                    if (current != null) _uiState.value = current.copy(publicFiles = publicFiles, hasMoreFiles = hasMoreFiles)
                }.onFailure { _message.value = it.message }
            } finally { loadingFiles = false }
        }
    }
    fun retryDiscovery() { discoveryLoaded = false; loadFeed() }
    private fun mutate(block: suspend (String) -> Result<*>) {
        if (_mutating.value) return
        viewModelScope.launch {
            _mutating.value = true
            try {
                val key = sessionRepo.getSessionKey() ?: error("Your session is unavailable. Open You to reconnect.")
                block(key).onSuccess { loadFeed() }.onFailure { _message.value = it.message ?: "Action failed" }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _message.value = e.message }
            finally { _mutating.value = false }
        }
    }
    fun reactToPost(post: SocialPost, reaction: String?) = mutate { api.reactToPost(post.id, it, reaction) }
    fun repost(post: SocialPost) = mutate { api.repostPost(post.id, it, !post.isReposted) }
    fun saveFile(file: FileItem) = mutate { if (file.isSaved) api.removeBookmark(file.slug, it) else api.saveBookmark(file.slug, it) }
    fun deletePost(post: SocialPost) = mutate { api.request("social/posts/${post.id}", "DELETE", api.body("sessionKey" to it)) }
    fun loadAttachments() { viewModelScope.launch {
        api.request("social/attachments", query = mapOf("sessionKey" to sessionRepo.getSessionKey())).onSuccess {
            _attachments.value = it.jsonObject["files"]?.jsonArray?.map { element -> api.json.decodeFromJsonElement<SocialAttachment>(element) }.orEmpty().filter { file -> file.isPublic && !file.passwordRequired }
        }.onFailure { _message.value = it.message }
    } }
    suspend fun loadFullPost(slug: String): Result<SocialPost> = api.request("social/posts/$slug", query = mapOf("sessionKey" to sessionRepo.getSessionKey())).mapCatching { fullCommunityPost(api, it) ?: error("The complete post is unavailable") }
    suspend fun publish(original: SocialPost?, title: String, content: String, tags: List<String>): Boolean {
        if (_mutating.value) return false
        _message.value = null
        _mutating.value = true
        return try {
            val key = sessionRepo.getSessionKey() ?: error("Your session is unavailable")
            val result = api.request(if (original == null) "social/posts" else "social/posts/${original.id}", if (original == null) "POST" else "PUT", api.body("sessionKey" to key, "title" to title.trim(), "content" to content, "tags" to JsonArray(tags.map(::JsonPrimitive)), "format" to "MARKDOWN"))
            result.onSuccess { loadFeed() }.onFailure { _message.value = it.message }
            result.isSuccess
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { _message.value = e.message; false }
        finally { _mutating.value = false }
    }
}
