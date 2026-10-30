package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.content.Context
import android.media.MediaPlayer
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val audioClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
private const val maxAudioPreviewBytes = 64L * 1024 * 1024
private class ChatAudioFocus(context: Context, onLoss: () -> Unit) {
    private val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listener = AudioManager.OnAudioFocusChangeListener { change -> if (change <= 0) onLoss() }
    private val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener(listener).build() else null
    @Suppress("DEPRECATION") fun acquire(): Boolean = (if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(request!!) else manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    @Suppress("DEPRECATION") fun release() { if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(request!!) else manager.abandonAudioFocus(listener) }
}
internal fun voiceFilenameDuration(filename: String): Int = Regex("-dur(\\d+)\\.m4a$", RegexOption.IGNORE_CASE).find(filename)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 600)?.times(1000) ?: 0
internal fun audioTime(ms: Int) = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private suspend fun cachedAudio(context: Context, api: OhioApiClient, sessions: SessionRepository, file: FileItem): File = withContext(Dispatchers.IO) {
    require(!file.isOneTime && !file.passwordRequired) { "Open this protected file to listen" }
    require(file.size <= maxAudioPreviewBytes) { "Open this file to play large audio" }
    val key = sessions.getSessionKey() ?: error("Your session is unavailable")
    val identity = sessions.getSessionId() ?: error("Your session is unavailable")
    val hash = MessageDigest.getInstance("SHA-256").digest("${api.getBaseUrl()}|$identity|${file.id}".toByteArray()).joinToString("") { "%02x".format(it) }
    val directory = File(context.cacheDir, "chat-audio").apply { mkdirs() }
    val destination = File(directory, "$hash.audio")
    if (destination.exists() && destination.length() > 0) return@withContext destination
    val temporary = File.createTempFile("audio-", ".part", directory)
    try {
        val call = audioClient.newCall(Request.Builder().url(api.getFileDownloadUrl(file.id, key, file.slug)).build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
            })
        }
        response.use { result ->
            require(result.isSuccessful) { "Audio could not load (${result.code})" }
            val body = result.body ?: error("Audio response was empty")
            require(body.contentLength() <= maxAudioPreviewBytes) { "Open this file to play large audio" }
            body.byteStream().use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(8192); var total = 0L
                while (true) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); val read = input.read(buffer); if (read < 0) break; total += read; require(total <= maxAudioPreviewBytes) { "Open this file to play large audio" }; output.write(buffer, 0, read) }
                require(total > 0) { "Audio response was empty" }
            } }
        }
        check(temporary.renameTo(destination)) { "Audio cache could not be saved" }
        directory.listFiles()?.filter { it != destination && it.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7) }?.forEach(File::delete)
        destination
    } finally { temporary.delete() }
}

@Composable internal fun ChatAudioPlayer(label: String, localPath: String? = null, api: OhioApiClient? = null, sessions: SessionRepository? = null, file: FileItem? = null, onOpen: (() -> Unit)? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var player by remember(localPath, file?.id) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(localPath, file?.id) { mutableStateOf(false) }
    var loading by remember(localPath, file?.id) { mutableStateOf(false) }
    var playing by remember(localPath, file?.id) { mutableStateOf(false) }
    var duration by remember(localPath, file?.id) { mutableIntStateOf(voiceFilenameDuration(file?.displayName ?: localPath.orEmpty())) }
    var position by remember(localPath, file?.id) { mutableIntStateOf(0) }
    var error by remember(localPath, file?.id) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focus = remember(context, localPath, file?.id) { ChatAudioFocus(context) { runCatching { player?.pause() }; playing = false } }
    DisposableEffect(localPath, file?.id, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { runCatching { player?.pause() }; playing = false; focus.release() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); runCatching { player?.release() }; player = null; focus.release() }
    }
    LaunchedEffect(player, playing) { while (playing) { position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0); delay(250) } }
    fun play() {
        if (prepared) {
            if (playing) { player?.pause(); playing = false; focus.release() }
            else if (focus.acquire()) { player?.start(); playing = true } else error = "Another app is using audio. Try again."
            return
        }
        scope.launch {
            loading = true; error = null
            var created: MediaPlayer? = null
            try {
                val source = localPath?.let(::File) ?: cachedAudio(context, requireNotNull(api), requireNotNull(sessions), requireNotNull(file))
                check(source.exists()) { "This recording is no longer available" }
                created = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    setDataSource(source.absolutePath)
                    setOnPreparedListener { ready -> prepared = true; loading = false; duration = ready.duration; if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && focus.acquire()) { ready.start(); playing = true } }
                    setOnCompletionListener { playing = false; position = duration; focus.release() }
                    setOnErrorListener { failed, _, _ -> error = "Audio could not play. Open the file to try another player."; playing = false; loading = false; prepared = false; if (player === failed) player = null; runCatching { failed.release() }; focus.release(); true }
                    prepareAsync()
                }
                player = created
            } catch (e: CancellationException) { created?.release(); throw e }
            catch (e: Exception) { created?.release(); error = e.message; loading = false }
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton({ play() }, enabled = !loading) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause audio" else "Play audio") }
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            else Slider(position.toFloat(), { position = it.toInt() }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = prepared, onValueChangeFinished = { player?.seekTo(position) }, modifier = Modifier.weight(1f))
            Text("${audioTime(position)} / ${audioTime(duration)}", style = MaterialTheme.typography.labelSmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        onOpen?.let { TextButton(it) { Text("Open audio file") } }
    }
}
