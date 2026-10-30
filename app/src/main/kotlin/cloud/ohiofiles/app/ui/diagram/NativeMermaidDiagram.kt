package cloud.ohiofiles.app.ui.diagram

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cloud.ohiofiles.app.ui.preview.ZoomablePreview
import kotlinx.coroutines.*

@Composable fun NativeMermaidDiagram(source: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current; val activity = remember(context) { context.activity() }; val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.surface.red + MaterialTheme.colorScheme.surface.green + MaterialTheme.colorScheme.surface.blue < 1.5f
    var code by rememberSaveable(source) { mutableStateOf(false) }; var fullscreen by remember { mutableStateOf(false) }; var exportError by remember { mutableStateOf<String?>(null) }
    val result by produceState<Result<RenderedDiagram>?>(null, source, dark, activity) {
        value = null
        value = try { Result.success(OfflineDiagramRenderer.render(activity ?: error("Open this diagram in the Android app"), source, dark)) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val rendered = result?.getOrNull()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/svg+xml")) { uri -> val svg = rendered?.svg; if (uri != null && svg != null) scope.launch {
        try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(svg.toByteArray(Charsets.UTF_8)) } ?: error("Export destination could not open") }; Toast.makeText(context, "Diagram exported", Toast.LENGTH_SHORT).show() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { exportError = e.message ?: "Diagram could not export" }
    } }
    OutlinedCard(modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountTree, null, tint = MaterialTheme.colorScheme.primary)
                Text("Diagram", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Mermaid diagram source", source)) }) { Icon(Icons.Outlined.ContentCopy, "Copy diagram source") }
                IconButton(onClick = { export.launch("ohiofiles-diagram.svg") }, enabled = rendered != null) { Icon(Icons.Outlined.Download, "Export diagram as SVG") }
                IconButton(onClick = { fullscreen = true }, enabled = rendered != null) { Icon(Icons.Outlined.Fullscreen, "Open diagram full screen") }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!code, { code = false }, { Text("Diagram") }); FilterChip(code, { code = true }, { Text("Source") })
            }
            result?.exceptionOrNull()?.let { Text(it.message ?: "Diagram could not render. Its source is available.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            exportError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (code || result?.isFailure == true) DiagramSource(source)
            else if (rendered != null) DiagramImage(rendered, Modifier.fillMaxWidth().height(340.dp))
            else Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("Rendering diagram on this device…", style = MaterialTheme.typography.labelMedium) } }
        }
    }
    if (fullscreen && rendered != null) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("Diagram", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleLarge); IconButton(onClick = { fullscreen = false }) { Icon(Icons.Outlined.Close, "Close full screen diagram") } }
            DiagramImage(rendered, Modifier.weight(1f).fillMaxWidth())
        } }
    }
}

@Composable private fun DiagramImage(diagram: RenderedDiagram, modifier: Modifier) {
    ZoomablePreview(modifier) { imageModifier -> Image(diagram.bitmap.asImageBitmap(), "Mermaid diagram. Select Source to read its full text definition.", imageModifier.padding(bottom = 54.dp), contentScale = ContentScale.Fit) }
}
@Composable private fun DiagramSource(source: String) {
    val lines = remember(source) { source.lines() }
    SelectionContainer { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), contentPadding = PaddingValues(16.dp)) { items(lines.size) { index -> Text(lines[index], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } }
}
private fun Context.activity(): Activity? {
    var current: Context = this; val visited = mutableSetOf<Context>()
    while (visited.add(current)) { if (current is Activity) return current; current = (current as? ContextWrapper)?.baseContext ?: return null }
    return null
}
