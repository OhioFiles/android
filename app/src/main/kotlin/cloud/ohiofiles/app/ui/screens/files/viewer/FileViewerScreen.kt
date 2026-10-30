package cloud.ohiofiles.app.ui.screens.files.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import cloud.ohiofiles.app.ui.screens.nativefeatures.NativeMarkdownPreview
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.platform.NativeActions
import cloud.ohiofiles.app.ui.preview.*
import cloud.ohiofiles.app.ui.document.NativeMarkdownReader
import cloud.ohiofiles.app.ui.document.SourceHighlighting
import coil.compose.AsyncImage
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

private val previewClient = OkHttpClient()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(file: FileItem, downloadUrl: String, onBack: () -> Unit, modifier: Modifier = Modifier, api: OhioApiClient? = null, sessionKey: String? = null, onOpenFile: ((FileItem) -> Unit)? = null) {
    val context = LocalContext.current
    val suffix = file.displayName.substringAfterLast('.').lowercase()
    Scaffold(topBar = { TopAppBar(title = { Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } }, actions = {
        IconButton({ NativeActions.share(context, "https://ohiofiles.cloud/${file.slug}") }) { Icon(Icons.Outlined.Share, "Share file link") }
        if (!file.isOneTime) IconButton({ NativeActions.download(context, downloadUrl, file.displayName, file.mimeType) }) { Icon(Icons.Outlined.Download, "Save to Downloads") }
    }) }, modifier = modifier) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            key(file.id, downloadUrl) {
            when {
                file.isImage -> ZoomablePreview(Modifier.fillMaxSize().background(Color.Black)) { modifier -> AsyncImage(downloadUrl, file.displayName, modifier = modifier, contentScale = ContentScale.Fit) }
                file.isVideo -> VideoPreview(downloadUrl)
                file.isAudio -> AudioPreview(downloadUrl, file)
                file.isPdf -> NativePdfPreview(downloadUrl)
                suffix in setOf("zip", "tar", "gz", "tgz", "gzip") -> NativeArchivePreview(downloadUrl, file.displayName)
                suffix in setOf("csv", "tsv") || file.mimeType == "text/csv" -> DelimitedTablePreview(downloadUrl, if (suffix == "tsv") '\t' else ',')
                suffix in setOf("diff", "patch") -> UnifiedDiffPreview(downloadUrl)
                suffix in setOf("glb", "gltf", "obj", "stl") || file.mimeType in setOf("model/gltf-binary", "model/gltf+json", "model/stl", "model/obj") -> NativeModelPreview(downloadUrl, file, api, sessionKey)
                file.isTextOrCode -> TextCodeViewer(downloadUrl, markdown = suffix in setOf("md", "markdown") || file.mimeType == "text/markdown", filename = file.displayName, api = api, sessionKey = sessionKey, onOpenFile = onOpenFile, assetFolderId = file.folderId)
                else -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(file.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 20.dp))
                    Text("${file.formattedSize} · ${file.mimeType ?: "File"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!file.isOneTime) Button({ NativeActions.download(context, downloadUrl, file.displayName, file.mimeType) }, Modifier.padding(top = 24.dp)) { Text("Save to Downloads") }
                }
            }
            }
        }
    }
}
@Composable
private fun VideoPreview(url: String) {
    var error by remember { mutableStateOf(false) }
    var video by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(factory = { context -> VideoView(context).apply {
        video = this
        setMediaController(MediaController(context).also { it.setAnchorView(this) })
        setVideoURI(Uri.parse(url)); setOnPreparedListener { start() }; setOnErrorListener { _, _, _ -> error = true; true }
    } }, modifier = Modifier.fillMaxSize())
    DisposableEffect(url) { onDispose { video?.stopPlayback() } }
    if (error) Text("This video could not be played. Try saving it to Downloads.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
}
@Composable
private fun AudioPreview(url: String, file: FileItem) {
    var prepared by remember { mutableStateOf(false) }; var playing by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableFloatStateOf(0f) }; var duration by remember { mutableFloatStateOf(1f) }
    val player = remember(url) { MediaPlayer().apply { setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build()) } }
    DisposableEffect(url) {
        player.setOnPreparedListener { prepared = true; duration = it.duration.toFloat().coerceAtLeast(1f) }
        player.setOnErrorListener { _, _, _ -> error = "Audio could not be played"; playing = false; true }
        player.setOnCompletionListener { playing = false; position = 0f }
        runCatching { player.setDataSource(url); player.prepareAsync() }.onFailure { error = it.message }
        onDispose { player.release() }
    }
    LaunchedEffect(playing) { while (playing) { position = runCatching { player.currentPosition.toFloat() }.getOrDefault(0f); delay(250) } }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer) { Icon(Icons.Outlined.GraphicEq, null, Modifier.padding(36.dp).size(72.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer) }
        Text(file.displayName, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp, bottom = 8.dp))
        Text(file.formattedSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Slider(position.coerceIn(0f, duration), { position = it; if (prepared) player.seekTo(it.toInt()) }, enabled = prepared, valueRange = 0f..duration)
        FilledIconButton({ runCatching { if (playing) player.pause() else player.start(); playing = !playing }.onFailure { error = it.message } }, enabled = prepared, modifier = Modifier.size(72.dp)) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Pause" else "Play", Modifier.size(36.dp)) }
    }
}
@Composable
fun TextCodeViewer(downloadUrl: String, markdown: Boolean = false, filename: String = "text", api: OhioApiClient? = null, sessionKey: String? = null, onOpenFile: ((FileItem) -> Unit)? = null, assetFolderId: String? = null) {
    var preview by rememberSaveable(downloadUrl) { mutableStateOf(markdown) }
    var text by remember(downloadUrl) { mutableStateOf<String?>(null) }
    LaunchedEffect(downloadUrl) {
        try { text = PreviewIO.text(downloadUrl) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { text = e.message ?: "Preview unavailable. Save the original file to open it." }
    }
    if (text == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else Column(Modifier.fillMaxSize()) {
        if (markdown) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(preview, { preview = true }, { Text("Read") })
            FilterChip(!preview, { preview = false }, { Text("Source") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (markdown && preview) Box(Modifier.fillMaxSize().padding(horizontal = 20.dp)) { NativeMarkdownReader(text!!, api, sessionKey, onOpenFile, assetFolderId = assetFolderId) }
            else SelectionContainer { androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                val lines = text!!.lines()
                items(lines.size) { index -> Text(SourceHighlighting.highlight(lines[index], SourceHighlighting.language(filename), MaterialTheme.colorScheme.surface.red < .4f), Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
            } }
        }
    }
}
