package cloud.ohiofiles.app.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import cloud.ohiofiles.app.data.api.ApiException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ohiofiles_prefs")

interface UploadSessionAccess {
    val sessionKeyFlow: Flow<String?>
    suspend fun getSessionKey(): String?
    suspend fun getServerUrl(): String
    suspend fun ensureSession(): Result<PublicSession>
}

class SessionRepository(
    private val context: Context,
    private val api: OhioApiClient
) : UploadSessionAccess {
    private object Keys {
        val SESSION_METADATA = stringPreferencesKey("session_metadata")
        val METADATA_SERVER = stringPreferencesKey("session_metadata_server")
        val SESSION_ID = stringPreferencesKey("session_id")
        val SESSION_KEY = stringPreferencesKey("session_key")
        val NICKNAME = stringPreferencesKey("nickname")
        val PROFILE_SLUG = stringPreferencesKey("profile_slug")
        val SERVER_URL = stringPreferencesKey("server_url")
    }

    override val sessionKeyFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.SESSION_KEY]
    }

    val nicknameFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[Keys.NICKNAME]
    }

    val serverUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.SERVER_URL] ?: "https://api.ohiofiles.cloud"
    }

    override suspend fun getSessionKey(): String? {
        val prefs = context.dataStore.data.first()
        val address = prefs[Keys.SERVER_URL] ?: "https://api.ohiofiles.cloud"
        if (runCatching { api.setBaseUrl(address) }.isFailure) return null
        return prefs[Keys.SESSION_KEY]
    }
    suspend fun getSessionId(): String? = context.dataStore.data.first()[Keys.SESSION_ID]

    override suspend fun getServerUrl(): String = context.dataStore.data.first()[Keys.SERVER_URL] ?: "https://api.ohiofiles.cloud"

    suspend fun setServerUrl(url: String) {
        val trimmed = url.trimEnd('/')
        require(trimmed.toHttpUrl().isHttps) { "Use an HTTPS server address" }
        sessionMutex.withLock {
            if (getServerUrl() == trimmed) return@withLock
            val previousUrl = getServerUrl()
            context.dataStore.edit { prefs ->
                prefs[Keys.SESSION_KEY]?.let { prefs[stringPreferencesKey("identity.$previousUrl")] = it }
                prefs[Keys.SESSION_ID]?.let { prefs[stringPreferencesKey("identity_id.$previousUrl")] = it }
                val restoredId = prefs[stringPreferencesKey("identity_id.$trimmed")]
                val restored = prefs[stringPreferencesKey("identity.$trimmed")]
                if (restored == null) prefs.remove(Keys.SESSION_KEY) else prefs[Keys.SESSION_KEY] = restored
                prefs[Keys.SERVER_URL] = trimmed
                if (restoredId == null) prefs.remove(Keys.SESSION_ID) else prefs[Keys.SESSION_ID] = restoredId
                prefs.remove(Keys.NICKNAME)
                prefs.remove(Keys.PROFILE_SLUG)
            }
            api.setBaseUrl(trimmed)
            cachedSession = null
        }
    }

    private val sessionMutex = Mutex()
    private var cachedSession: PublicSession? = null

    override suspend fun ensureSession(): Result<PublicSession> = sessionMutex.withLock {
        cachedSession?.let { return@withLock Result.success(it) }
        val currentUrl = getServerUrl()
        try { api.setBaseUrl(currentUrl) } catch (e: IllegalArgumentException) { return@withLock Result.failure(e) }

        val existingKey = getSessionKey()
        if (!existingKey.isNullOrBlank()) {
            val res = api.getSession(existingKey)
            if (res.isSuccess) {
                val session = res.getOrThrow()
                saveSessionLocally(session)
                cachedSession = session
                return@withLock Result.success(session)
            }
            // A network outage must never silently replace an existing identity.
            val error = res.exceptionOrNull()
            if (error !is ApiException || error.status !in listOf(404, 410)) return@withLock res
        }

        // Create new session
        val createRes = api.createSession("secure")
        if (createRes.isSuccess) {
            val session = createRes.getOrThrow()
            saveSessionLocally(session)
            cachedSession = session
            return@withLock Result.success(session)
        }
        createRes
    }

    suspend fun cachedIdentity(): PublicSession? {
        val prefs = context.dataStore.data.first()
        val key = prefs[Keys.SESSION_KEY] ?: return null
        cachedSession?.takeIf { it.key == key }?.let { return it }
        if (prefs[Keys.METADATA_SERVER] != (prefs[Keys.SERVER_URL] ?: "https://api.ohiofiles.cloud")) return null
        return prefs[Keys.SESSION_METADATA]?.let { value -> runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<PublicSession>(value) }.getOrNull() }?.takeIf { it.key == key }
    }

    suspend fun refreshSession(): Result<PublicSession> = sessionMutex.withLock {
        val key = getSessionKey() ?: return@withLock Result.failure(Exception("Import a session to continue"))
        val result = api.getSession(key)
        result.getOrNull()?.let { saveSessionLocally(it) }
        result
    }

    suspend fun importSessionKey(key: String): Result<PublicSession> = sessionMutex.withLock {
        val trimmedKey = key.trim()
        val res = api.getSession(trimmedKey)
        if (res.isSuccess) {
            val session = res.getOrThrow()
            saveSessionLocally(session)
            return@withLock Result.success(session)
        }
        res
    }

    suspend fun updateNickname(nickname: String): Result<PublicSession> = sessionMutex.withLock {
        val key = getSessionKey() ?: return@withLock Result.failure(Exception("No session key"))
        val res = api.updateNickname(key, nickname.trim())
        if (res.isSuccess) {
            val session = res.getOrThrow()
            saveSessionLocally(session)
        }
        res
    }

    suspend fun revokeCurrentSession(): Result<Boolean> = sessionMutex.withLock {
        val key = getSessionKey() ?: return@withLock Result.success(true)
        val res = api.revokeSession(key)
        if (res.isFailure) return@withLock res
        cachedSession = null
        val url = getServerUrl()
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("identity.$url"))
            prefs.remove(stringPreferencesKey("identity_id.$url"))
            prefs.remove(Keys.SESSION_KEY)
            prefs.remove(Keys.SESSION_METADATA)
            prefs.remove(Keys.METADATA_SERVER)
            prefs.remove(Keys.SESSION_ID)
            prefs.remove(Keys.NICKNAME)
            prefs.remove(Keys.PROFILE_SLUG)
        }
        res
    }

    private suspend fun saveSessionLocally(session: PublicSession) {
        cachedSession = session
        context.dataStore.edit { prefs ->
            prefs[Keys.SESSION_METADATA] = Json.encodeToString(PublicSession.serializer(), session)
            prefs[Keys.METADATA_SERVER] = prefs[Keys.SERVER_URL] ?: "https://api.ohiofiles.cloud"
            prefs[Keys.SESSION_ID] = session.id
            prefs[Keys.SESSION_KEY] = session.key
            if (session.nickname == null) prefs.remove(Keys.NICKNAME) else prefs[Keys.NICKNAME] = session.nickname
            if (session.profileSlug == null) prefs.remove(Keys.PROFILE_SLUG) else prefs[Keys.PROFILE_SLUG] = session.profileSlug
        }
    }
}

class AppearanceRepository(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_PALETTE = stringPreferencesKey("accent_palette")
        val CORNER_STYLE = stringPreferencesKey("corner_style")
        val DENSITY_STYLE = stringPreferencesKey("density_style")
        val FONT_OPTION = stringPreferencesKey("font_option")
        val AUTO_SHOW_QR = booleanPreferencesKey("auto_show_qr")
        val DETAILED_CHUNKS = booleanPreferencesKey("detailed_chunks")
        val VIEW_MODE_GRID = booleanPreferencesKey("view_mode_grid")
    }

    val appearanceFlow: Flow<AppearanceConfig> = context.dataStore.data.map { prefs ->
        val themeMode = runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: "") }.getOrDefault(ThemeMode.SYSTEM)
        val accent = runCatching { AccentPalette.valueOf(prefs[Keys.ACCENT_PALETTE] ?: "") }.getOrDefault(AccentPalette.EMERALD)
        val corners = runCatching { CornerStyle.valueOf(prefs[Keys.CORNER_STYLE] ?: "") }.getOrDefault(CornerStyle.ROUND)
        val density = runCatching { DensityStyle.valueOf(prefs[Keys.DENSITY_STYLE] ?: "") }.getOrDefault(DensityStyle.COMFORTABLE)
        val font = runCatching { FontOption.valueOf(prefs[Keys.FONT_OPTION] ?: "") }.getOrDefault(FontOption.SYSTEM)
        val autoQr = prefs[Keys.AUTO_SHOW_QR] ?: true
        val grid = prefs[Keys.VIEW_MODE_GRID] ?: true

        AppearanceConfig(
            themeMode = themeMode,
            accentPalette = accent,
            cornerStyle = corners,
            densityStyle = density,
            fontOption = font,
            autoShowQrOnUpload = autoQr,
            detailedChunkProgress = prefs[Keys.DETAILED_CHUNKS] ?: false,
            viewModeGrid = grid
        )
    }

    suspend fun updateThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun updateAccentPalette(palette: AccentPalette) {
        context.dataStore.edit { it[Keys.ACCENT_PALETTE] = palette.name }
    }

    suspend fun updateCornerStyle(corner: CornerStyle) {
        context.dataStore.edit { it[Keys.CORNER_STYLE] = corner.name }
    }

    suspend fun updateDensityStyle(density: DensityStyle) {
        context.dataStore.edit { it[Keys.DENSITY_STYLE] = density.name }
    }

    suspend fun updateFontOption(font: FontOption) {
        context.dataStore.edit { it[Keys.FONT_OPTION] = font.name }
    }

    suspend fun updateAutoShowQr(autoShow: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_SHOW_QR] = autoShow }
    }

    suspend fun updateDetailedChunkProgress(value: Boolean) { context.dataStore.edit { it[Keys.DETAILED_CHUNKS] = value } }
    suspend fun updateViewModeGrid(isGrid: Boolean) {
        context.dataStore.edit { it[Keys.VIEW_MODE_GRID] = isGrid }
    }
}
