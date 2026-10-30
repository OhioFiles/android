package cloud.ohiofiles.app.ui.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.document.*
import cloud.ohiofiles.app.ui.document.NativeMarkdownReader
import cloud.ohiofiles.app.ui.document.SourceHighlighting
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NativeArchivePreview(url: String, name: String) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val alive = remember(url) { AtomicBoolean(true) }
    var local by remember(url) { mutableStateOf<File?>(null) }; var entries by remember(url) { mutableStateOf(emptyList<ArchiveEntryInfo>()) }; var directory by remember(url) { mutableStateOf("") }; var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var selected by remember { mutableStateOf<ArchiveEntryInfo?>(null) }; var extracting by remember { mutableStateOf<ArchiveEntryInfo?>(null) }
    val extract = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> val entry = extracting; extracting = null; val archive = local
        if (uri != null && entry != null && archive != null) scope.launch { busy = true
            try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { output -> runInterruptible { ArchiveContents.extract(archive, name, entry.path, output) } } ?: error("Destination could not open") }; Toast.makeText(context, "Entry extracted", Toast.LENGTH_SHORT).show() }
            catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
        }
    }
    LaunchedEffect(url) { busy = true
        try { val file = PreviewIO.temporary(context, url); if (!alive.get()) { file.delete(); return@LaunchedEffect }; local = file; entries = withContext(Dispatchers.IO) { runInterruptible { ArchiveContents.list(file, name) } } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    }
    DisposableEffect(url) { onDispose { alive.set(false); local?.delete() } }
    val visible = remember(entries, directory, query) {
        if (query.isNotBlank()) entries.filter { it.path.contains(query, true) }
        else {
            val children = linkedMapOf<String, ArchiveEntryInfo>()
            entries.forEach { entry -> if (entry.path.startsWith(directory)) {
                val rest = entry.path.removePrefix(directory)
                if (rest.isNotEmpty()) {
                    val child = rest.substringBefore('/'); val path = directory + child
                    if ('/' in rest) children[path] = ArchiveEntryInfo(path, 0, true) else children[path] = entry
                }
            } }; children.values.sortedWith(compareByDescending<ArchiveEntryInfo> { it.directory }.thenBy { it.path })
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("Search archive") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true)
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) { if (directory.isNotEmpty()) TextButton(onClick = { directory = directory.trimEnd('/').substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" } }) { Text("Up") }; Text(directory.ifBlank { "Archive root" }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); Text("${entries.size} entries", style = MaterialTheme.typography.labelSmall) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()); error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(visible, key = { it.path }) { entry ->
            OutlinedCard(onClick = { if (entry.directory) { directory = entry.path + "/"; query = "" } else selected = entry }) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(if (entry.directory) Icons.Outlined.Folder else Icons.Outlined.Description, null); Column(Modifier.weight(1f)) { Text(if (query.isBlank()) entry.path.substringAfterLast('/') else entry.path, style = MaterialTheme.typography.titleSmall); if (!entry.directory) Text("${entry.size.coerceAtLeast(0)} bytes", style = MaterialTheme.typography.bodySmall) }; if (!entry.directory) IconButton(onClick = { extracting = entry; extract.launch(entry.path.substringAfterLast('/')) }) { Icon(Icons.Outlined.FileDownload, "Extract ${entry.path.substringAfterLast('/')}") } }
            }
        } }
    }
    if (selected != null && local != null) ModalBottomSheet(onDismissRequest = { selected = null }) {
        val entry = selected!!; val file = local!!; var bytes by remember(entry.path) { mutableStateOf<ByteArray?>(null) }; var previewError by remember(entry.path) { mutableStateOf<String?>(null) }
        LaunchedEffect(entry.path) { try { bytes = withContext(Dispatchers.IO) { runInterruptible { ArchiveContents.preview(file, name, entry.path) } } } catch (e: CancellationException) { throw e } catch (e: Exception) { previewError = e.message } }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(entry.path.substringAfterLast('/'), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); TextButton(onClick = { extracting = entry; extract.launch(entry.path.substringAfterLast('/')) }) { Text("Extract") } }
        previewError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        val data = bytes
        if (data == null && previewError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (data != null) {
            val suffix = entry.path.substringAfterLast('.').lowercase()
            if (suffix in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp")) {
                val bitmap by produceState<Bitmap?>(null, data) { value = withContext(Dispatchers.Default) { val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(data, 0, data.size, bounds); val options = BitmapFactory.Options().apply { var sample = 1; while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2; inSampleSize = sample }; BitmapFactory.decodeByteArray(data, 0, data.size, options) } }
                Box(Modifier.fillMaxWidth().height(440.dp)) { bitmap?.let { image -> ZoomablePreview(Modifier.fillMaxSize()) { modifier -> Image(image.asImageBitmap(), entry.path, modifier, contentScale = ContentScale.Fit) } } }
            } else if (data.take(8192).none { it == 0.toByte() }) {
                val source = remember(data) { data.toString(Charsets.UTF_8) }
                Box(Modifier.fillMaxWidth().height(440.dp).padding(horizontal = 16.dp)) { if (suffix in listOf("md", "markdown")) NativeMarkdownReader(source) else LazyColumn { items(source.lines()) { line -> Text(SourceHighlighting.highlight(line, suffix, true), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } }
            } else Text("Binary entry · extract it to open with another Android app.", Modifier.padding(24.dp))
        }
    }
}
