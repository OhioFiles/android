package cloud.ohiofiles.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.PublicSession
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal data class AccountInsights(val files: Long?, val folders: Long?, val bytes: Long?, val backups: Long?)
internal fun accountInsights(value: JsonElement): AccountInsights? {
    val storage = (value as? JsonObject)?.get("storage") as? JsonObject ?: return null
    fun number(key: String) = (storage[key] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    return AccountInsights(number("fileCount"), number("folderCount"), number("totalBytes"), number("backupCount"))
}
sealed interface ProfileUiState {
    data object Loading : ProfileUiState
    data class Success(
        val session: PublicSession?, val sessionKey: String, val nickname: String, val serverUrl: String,
        val profileSlug: String? = null, val avatarUrl: String? = null, val mood: String? = null, val status: String? = null,
        val fileCount: Long? = null, val folderCount: Long? = null, val totalBytes: Long? = null, val backupCount: Long? = null,
        val refreshing: Boolean = false, val verified: Boolean = false, val message: String? = null
    ) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}
class ProfileViewModel(val api: OhioApiClient, val sessionRepo: SessionRepository) : ViewModel() {
    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState = _uiState.asStateFlow()
    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private var loadJob: Job? = null
    init { loadProfile() }
    fun clearNotice() { _notice.value = null }
    fun loadProfile() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val key = sessionRepo.getSessionKey() ?: sessionRepo.ensureSession().getOrThrow().key
                val server = sessionRepo.getServerUrl()
                val old = (_uiState.value as? ProfileUiState.Success)?.takeIf { it.sessionKey == key && it.serverUrl == server }
                val cached = old?.session ?: sessionRepo.cachedIdentity()
                val name = cached?.nickname ?: sessionRepo.nicknameFlow.first() ?: "Your profile"
                _uiState.value = old?.copy(refreshing = true, message = null) ?: ProfileUiState.Success(cached, key, name, server,
                    profileSlug = cached?.profileSlug, avatarUrl = cached?.avatarUrl, mood = cached?.mood, status = cached?.statusText, refreshing = true)
                supervisorScope {
                    val scopedApi = OhioApiClient(server)
                    val sessionTask = async { sessionRepo.refreshSession() }
                    val profileTask = async { scopedApi.request("profiles/me", query = mapOf("sessionKey" to key)) }
                    val insightsTask = async { scopedApi.request("session-insights", query = mapOf("sessionKey" to key)) }
                    val sessionResult = sessionTask.await()
                    val profileResult = profileTask.await()
                    val insightsResult = insightsTask.await()
                    if (sessionRepo.getSessionKey() != key || sessionRepo.getServerUrl() != server) return@supervisorScope
                    val session = sessionResult.getOrNull() ?: cached
                    val root = profileResult.getOrNull() as? JsonObject
                    val profile = root?.get("profile") as? JsonObject ?: root
                    fun text(key: String): String? = (profile?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                    val insights = insightsResult.getOrNull()?.let(::accountInsights)
                    val errors = listOf(sessionResult.exceptionOrNull(), profileResult.exceptionOrNull(), insightsResult.exceptionOrNull()).filterNotNull()
                    val auth = errors.any { it is ApiException && it.status in listOf(401, 403, 410) }
                    _uiState.value = ProfileUiState.Success(session, key, text("nickname") ?: session?.nickname ?: name, server,
                        profileSlug = text("slug") ?: text("profileSlug") ?: session?.profileSlug ?: old?.profileSlug,
                        avatarUrl = (text("avatarUrl") ?: session?.avatarUrl ?: old?.avatarUrl)?.let { if (it.startsWith('/')) server + it else it },
                        mood = text("mood") ?: session?.mood, status = text("statusText") ?: session?.statusText,
                        fileCount = insights?.files ?: old?.fileCount, folderCount = insights?.folders ?: old?.folderCount,
                        totalBytes = insights?.bytes ?: old?.totalBytes, backupCount = insights?.backups ?: old?.backupCount,
                        verified = sessionResult.isSuccess, message = when { auth -> "Session access needs attention. Open Session & recovery to check it."; errors.isNotEmpty() -> "Couldn't refresh everything. Saved details are shown where available."; else -> null })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _uiState.value = ProfileUiState.Error("Couldn't load your profile. Check your connection and try again, or import a session.") }
        }
    }
    fun updateNickname(nickname: String, onResult: (Boolean, String?) -> Unit = { _, _ -> }) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { val result = sessionRepo.updateNickname(nickname); if (result.isSuccess) { loadProfile(); onResult(true, null) } else onResult(false, result.exceptionOrNull()?.message ?: "Nickname couldn't be changed") }
            finally { _busy.value = false }
        }
    }
    fun importSessionKey(key: String, onResult: (Boolean, String?) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { val result = sessionRepo.importSessionKey(key); if (result.isSuccess) { loadProfile(); onResult(true, null) } else onResult(false, result.exceptionOrNull()?.message ?: "Check the session key and try again") }
            finally { _busy.value = false }
        }
    }
    fun renewSession() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try { val key = sessionRepo.getSessionKey() ?: return@launch; val result = api.renewSession(key); _notice.value = if (result.isSuccess) "Session renewed" else result.exceptionOrNull()?.message ?: "Couldn't renew the session"; if (result.isSuccess) loadProfile() }
            finally { _busy.value = false }
        }
    }
    fun revokeSession(onResult: (Boolean, String?) -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = sessionRepo.revokeCurrentSession()
                if (result.isFailure) { onResult(false, result.exceptionOrNull()?.message ?: "Couldn't revoke the session"); return@launch }
                sessionRepo.ensureSession(); loadProfile(); onResult(true, null)
            } finally { _busy.value = false }
        }
    }
}
