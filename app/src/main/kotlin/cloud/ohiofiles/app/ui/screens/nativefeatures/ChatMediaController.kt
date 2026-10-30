package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.ohiofiles.app.data.api.OhioApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class ChatUpload(val id: String, val thread: String, val uri: String, val name: String, val status: String = "Queued", val progress: Float = 0f, val fileId: String? = null, val error: String? = null)
internal data class VoiceDraft(val path: String, val seconds: Int = 0, val recording: Boolean = false, val progress: Float = 0f, val uploadedId: String? = null, val error: String? = null)
internal data class ChatMediaState(val uploads: List<ChatUpload> = emptyList(), val voices: Map<String, VoiceDraft> = emptyMap(), val error: String? = null)

internal fun interface ChatMediaUploader { suspend fun upload(uri: Uri, progress: (Float) -> Unit): String }
internal fun ChatMediaState.readyFileIds(thread: String): Set<String> = uploads.filter { it.thread == thread && it.status == "Ready" }.mapNotNull { it.fileId }.toSet()
internal fun ChatMediaState.hasUnreadyUploads(thread: String): Boolean = uploads.any { it.thread == thread && it.fileId == null }

/** Retained across rotation; all credentials and upload endpoints stay bound to the originating account. */
internal class ChatMediaController(context: Context, baseUrl: String, private val accountKey: String?, private val testUploader: ChatMediaUploader? = null) : ViewModel() {
    private val context = context.applicationContext
    private val api = OhioApiClient(baseUrl)
    private val _state = MutableStateFlow(ChatMediaState())
    val state = _state.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val attemptTokens = mutableMapOf<String, String>()
    private val permits = Semaphore(2)
    private var recorder: MediaRecorder? = null
    private var recordingThread: String? = null
    private var startedAt = 0L
    private fun updateUpload(id: String, transform: (ChatUpload) -> ChatUpload) { _state.update { state -> state.copy(uploads = state.uploads.map { if (it.id == id) transform(it) else it }) } }
    private fun updateVoice(thread: String, transform: (VoiceDraft) -> VoiceDraft) { _state.update { state -> val value = state.voices[thread] ?: return@update state; state.copy(voices = state.voices + (thread to transform(value))) } }
    private suspend fun uploadAsset(uri: Uri, progress: (Float) -> Unit): String = testUploader?.upload(uri, progress)
        ?: api.uploadFile(context, uri, accountKey ?: error("Your session is unavailable"), onProgress = progress).getOrThrow().finalId
        ?: error("Upload did not return file metadata")
    fun recordingSeconds(thread: String): Int = if (recordingThread == thread) ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt() else _state.value.voices[thread]?.seconds ?: 0
    fun add(thread: String, uris: List<Uri>, currentAttachedCount: Int) {
        val remaining = (10 - currentAttachedCount - _state.value.uploads.count { it.thread == thread }).coerceAtLeast(0)
        if (uris.size > remaining) _state.update { it.copy(error = "Attach up to 10 files and folders per message") }
        uris.take(remaining).forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val item = ChatUpload(UUID.randomUUID().toString(), thread, uri.toString(), "Selected file")
            _state.update { it.copy(uploads = it.uploads + item) }
            upload(item.id)
        }
    }
    fun dismissError() { _state.update { it.copy(error = null) } }
    fun upload(id: String) {
        if (jobs[id]?.isActive == true) return
        val item = _state.value.uploads.firstOrNull { it.id == id } ?: return
        if (item.fileId != null) return
        val token = UUID.randomUUID().toString(); attemptTokens[id] = token
        jobs[id] = viewModelScope.launch {
            fun updateAttempt(transform: (ChatUpload) -> ChatUpload) { if (attemptTokens[id] == token) updateUpload(id, transform) }
            try {
                permits.withPermit {
                    updateAttempt { it.copy(status = "Uploading", error = null) }
                    val name = withContext(Dispatchers.IO) { runCatching { context.contentResolver.query(Uri.parse(item.uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } }.getOrNull() ?: "Selected file" }
                    updateAttempt { it.copy(name = name) }
                    val fileId = uploadAsset(Uri.parse(item.uri)) { progress -> val previous = _state.value.uploads.firstOrNull { it.id == id }?.progress ?: 0f; if (progress - previous > .015f || progress >= 1f) updateAttempt { it.copy(progress = progress) } }
                    updateAttempt { it.copy(status = "Ready", progress = 1f, fileId = fileId) }
                }
            } catch (e: CancellationException) { updateAttempt { it.copy(status = "Canceled", error = "Upload canceled. Retry to attach this file.") }; throw e }
            catch (e: Exception) { updateAttempt { it.copy(status = "Failed", error = e.message ?: "Upload failed") } }
        }
    }
    fun cancel(id: String) { jobs[id]?.cancel(); updateUpload(id) { it.copy(status = "Canceled", error = "Upload canceled. Retry to attach this file.") } }
    fun remove(id: String) { attemptTokens.remove(id); jobs.remove(id)?.cancel(); _state.update { it.copy(uploads = it.uploads.filterNot { upload -> upload.id == id }) } }
    fun clearSent(thread: String, sentIds: Set<String>) { _state.value.uploads.filter { it.thread == thread && it.fileId in sentIds }.forEach { remove(it.id) } }
    @Suppress("DEPRECATION") fun startVoice(thread: String) {
        if (recorder != null) return
        if (_state.value.voices[thread] != null) { _state.update { it.copy(error = "Send or discard your existing recording first") }; return }
        val directory = File(context.cacheDir, "voice-drafts").apply { mkdirs() }
        val path = File(directory, "recording-${System.currentTimeMillis()}.m4a")
        var created: MediaRecorder? = null
        try {
            created = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
            created.apply { setAudioSource(MediaRecorder.AudioSource.MIC); setOutputFormat(MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(96000); setAudioSamplingRate(44100); setOutputFile(path.absolutePath); setMaxDuration(10 * 60 * 1000); setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopVoice() }; prepare(); start() }
            recorder = created; recordingThread = thread; startedAt = android.os.SystemClock.elapsedRealtime()
            _state.update { it.copy(voices = it.voices + (thread to VoiceDraft(path.absolutePath, recording = true))) }
        } catch (e: Exception) { created?.release(); path.delete(); _state.update { it.copy(error = e.message ?: "Microphone could not start") } }
    }
    fun stopVoice() {
        val active = recorder ?: return; val thread = recordingThread ?: return
        recorder = null; recordingThread = null
        try {
            active.stop()
            val seconds = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt().coerceAtLeast(1)
            val previous = _state.value.voices[thread] ?: return
            val source = File(previous.path)
            val named = File(source.parentFile, "voice-${System.currentTimeMillis()}-dur${seconds}.m4a")
            check(source.renameTo(named)) { "Recording could not be saved" }
            updateVoice(thread) { it.copy(path = named.absolutePath, seconds = seconds, recording = false) }
        } catch (e: Exception) {
            _state.value.voices[thread]?.let { File(it.path).delete() }
            _state.update { it.copy(voices = it.voices - thread, error = "Recording was too short or could not be saved. Try again.") }
        } finally { active.release() }
    }
    fun discardVoice(thread: String) { if (recordingThread == thread) stopVoice(); _state.value.voices[thread]?.let { File(it.path).delete() }; _state.update { it.copy(voices = it.voices - thread) } }
    internal fun restoreVoice(thread: String, path: String, seconds: Int) {
        val file = File(path)
        require(file.exists() && file.length() > 0) { "Recording is no longer available" }
        require(file.canonicalPath.startsWith(File(context.cacheDir, "voice-drafts").canonicalPath + File.separator)) { "Recording is outside app storage" }
        _state.update { it.copy(voices = it.voices + (thread to VoiceDraft(file.absolutePath, seconds.coerceAtLeast(1)))) }
    }
    suspend fun uploadVoice(thread: String): String? {
        val voice = _state.value.voices[thread] ?: return null
        require(!voice.recording) { "Stop recording before sending" }
        voice.uploadedId?.let { return it }
        return try {
            val id = uploadAsset(Uri.fromFile(File(voice.path))) { progress -> updateVoice(thread) { it.copy(progress = progress, error = null) } }
            updateVoice(thread) { it.copy(uploadedId = id, progress = 1f) }; id
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { updateVoice(thread) { it.copy(error = e.message ?: "Recording could not upload") }; throw e }
    }
    override fun onCleared() { stopVoice(); jobs.values.forEach { it.cancel() } }
}
