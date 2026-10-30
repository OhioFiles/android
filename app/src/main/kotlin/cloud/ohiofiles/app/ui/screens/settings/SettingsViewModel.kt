package cloud.ohiofiles.app.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import cloud.ohiofiles.app.data.repository.AppearanceRepository
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val appearanceRepo: AppearanceRepository,
    private val sessionRepo: SessionRepository,
    private val api: OhioApiClient
) : ViewModel() {

    val appearanceConfig: StateFlow<AppearanceConfig> = appearanceRepo.appearanceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppearanceConfig())

    val serverUrl: StateFlow<String> = sessionRepo.serverUrlFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "https://api.ohiofiles.cloud")

    private val _connectionStatus = MutableStateFlow<String?>(null)
    val connectionStatus: StateFlow<String?> = _connectionStatus.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            appearanceRepo.updateThemeMode(mode)
        }
    }

    fun setAccentPalette(palette: AccentPalette) {
        viewModelScope.launch {
            appearanceRepo.updateAccentPalette(palette)
        }
    }

    fun setCornerStyle(style: CornerStyle) {
        viewModelScope.launch {
            appearanceRepo.updateCornerStyle(style)
        }
    }

    fun setDensityStyle(density: DensityStyle) {
        viewModelScope.launch {
            appearanceRepo.updateDensityStyle(density)
        }
    }

    fun setAutoShowQr(autoShow: Boolean) {
        viewModelScope.launch {
            appearanceRepo.updateAutoShowQr(autoShow)
        }
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch {
            runCatching { sessionRepo.setServerUrl(url) }.onSuccess { testConnection() }.onFailure { _connectionStatus.value = it.message }
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _connectionStatus.value = "Testing connection..."
            try {
                val ok = api.request("health")
                if (ok.isSuccess) {
                    _connectionStatus.value = "Connected successfully!"
                } else {
                    _connectionStatus.value = "Connection error: ${ok.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _connectionStatus.value = "Error: ${e.message}"
            }
        }
    }
    fun setDetailedChunkProgress(value: Boolean) { viewModelScope.launch { appearanceRepo.updateDetailedChunkProgress(value) } }

}
