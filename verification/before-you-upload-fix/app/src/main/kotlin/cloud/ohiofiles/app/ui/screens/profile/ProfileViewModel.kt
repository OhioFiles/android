package cloud.ohiofiles.app.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.PublicSession
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ProfileUiState {
    object Loading : ProfileUiState
    data class Success(
        val session: PublicSession?,
        val sessionKey: String,
        val nickname: String,
        val serverUrl: String
    ) : ProfileUiState
    data class Error(val message: String) : ProfileUiState
}

class ProfileViewModel(
    val api: OhioApiClient,
    val sessionRepo: SessionRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Loading)
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _uiState.value = ProfileUiState.Loading
            val key = sessionRepo.getSessionKey()
            val serverUrl = sessionRepo.getServerUrl()

            if (key.isNullOrBlank()) {
                val res = sessionRepo.ensureSession()
                if (res.isSuccess) {
                    val session = res.getOrThrow()
                    _uiState.value = ProfileUiState.Success(
                        session = session,
                        sessionKey = session.key,
                        nickname = session.nickname ?: "OhioFiles User",
                        serverUrl = serverUrl
                    )
                } else {
                    val err = res.exceptionOrNull()?.message ?: "Unknown error"
                    android.util.Log.e("ProfileViewModel", "ensureSession failed: $err")
                    _uiState.value = ProfileUiState.Error("Could not initialize session: $err")
                }
            } else {
                val res = api.getSession(key)
                val session = res.getOrNull()
                _uiState.value = ProfileUiState.Success(
                    session = session,
                    sessionKey = key,
                    nickname = session?.nickname ?: "OhioFiles User",
                    serverUrl = serverUrl
                )
            }
        }
    }

    private val _notice = MutableStateFlow<String?>(null)
    val notice = _notice.asStateFlow()

    fun updateNickname(nickname: String) {
        viewModelScope.launch {
            val res = sessionRepo.updateNickname(nickname)
            if (res.isSuccess) { loadProfile(); _notice.value = "Nickname updated" }
            else _notice.value = res.exceptionOrNull()?.message
        }
    }

    fun importSessionKey(key: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = sessionRepo.importSessionKey(key)
            if (res.isSuccess) {
                loadProfile()
                onResult(true, null)
            } else {
                onResult(false, res.exceptionOrNull()?.message ?: "Invalid session key")
            }
        }
    }

    fun renewSession() {
        viewModelScope.launch {
            val key = sessionRepo.getSessionKey() ?: return@launch
            val result = api.renewSession(key)
            _notice.value = if (result.isSuccess) "Session renewed" else result.exceptionOrNull()?.message
            if (result.isSuccess) loadProfile()
        }
    }

    fun revokeSession(onComplete: () -> Unit) {
        viewModelScope.launch {
            val result = sessionRepo.revokeCurrentSession()
            if (result.isFailure) { _notice.value = result.exceptionOrNull()?.message; return@launch }
            sessionRepo.ensureSession()
            loadProfile()
            onComplete()
        }
    }
}
