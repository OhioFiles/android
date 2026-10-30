package cloud.ohiofiles.app.ui.document

import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.document.*
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.data.model.FileItem
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

@Composable fun NativeNotebookPane(api: OhioApiClient, sessions: SessionRepository, onOpenFile: (FileItem) -> Unit = {}) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val store = remember { NotebookStore(context) }; val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notebook by remember { mutableStateOf(LocalNotebook()) }; var loaded by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var active by rememberSaveable { mutableStateOf<String?>(null) }; var rename by remember { mutableStateOf<NotebookPage?>(null) }; var delete by remember { mutableStateOf<NotebookPage?>(null) }
    var renameNotebook by remember { mutableStateOf(false) }; var publish by remember { mutableStateOf(false) }; var exporting by remember { mutableStateOf<NotebookPage?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun persist(next: LocalNotebook = notebook) { store.save(next) }
    fun update(next: LocalNotebook) { notebook = next.copy(updatedAt = System.currentTimeMillis()) }
    fun updatePage(page: NotebookPage) { update(notebook.copy(pages = notebook.pages.map { if (it.id == page.id) page.copy(updatedAt = System.currentTimeMillis()) else it })) }
    LaunchedEffect(Unit) { try { notebook = store.load(); loaded = true } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Notebook could not load" } }
    LaunchedEffect(notebook, loaded) { if (loaded) { delay(350); try { persist(); error = null } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Notebook could not save" } } }
    val latest by rememberUpdatedState(notebook)
    DisposableEffect(lifecycle) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE && loaded) scope.launch(Dispatchers.IO) { try { store.save(latest) } catch (_: Exception) {} } }; lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) } }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> scope.launch {
        busy = true
        try {
            var incoming = emptyList<NotebookPage>()
            for (uri in uris) {
                val pages = withContext(Dispatchers.IO) {
                    var name = uri.lastPathSegment ?: "import.md"
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) name = cursor.getString(0) }
                    context.contentResolver.openInputStream(uri)?.use { input -> runInterruptible { NotebookInterchange.import(input, name).pages } } ?: error("Selected document could not open")
                }
                incoming = incoming + pages
            }
            require(notebook.pages.size + incoming.size <= NotebookInterchange.MAX_PAGES) { "A notebook supports up to 200 pages" }
            update(notebook.copy(pages = (notebook.pages + incoming).mapIndexed { index, page -> page.copy(order = index) })); persist()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Import failed" } finally { busy = false }
    } }
    val exportPage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> val page = exporting; exporting = null; if (uri != null && page != null) scope.launch {
        try { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(page.content.toByteArray(Charsets.UTF_8)) } ?: error("Export destination could not open") }; Toast.makeText(context, "Page exported", Toast.LENGTH_SHORT).show() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    } }
    val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) scope.launch {
        try { val snapshot = notebook; withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { NotebookInterchange.exportZip(snapshot, it) } ?: error("Export destination could not open") }; Toast.makeText(context, "Notebook exported", Toast.LENGTH_SHORT).show() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    } }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) scope.launch {
        try { val snapshot = notebook; withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(NotebookInterchange.json.encodeToString(LocalNotebook.serializer(), snapshot).toByteArray()) } ?: error("Export destination could not open") } }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(notebook.notebookTitle, style = MaterialTheme.typography.headlineSmall); Text("Saved on this device · available offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; IconButton(onClick = { renameNotebook = true }) { Icon(Icons.Outlined.Edit, "Rename notebook") } } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { val page = NotebookPage(title = "Untitled page", filename = "note-${notebook.pages.size + 1}.md", order = notebook.pages.size); update(notebook.copy(pages = notebook.pages + page)); active = page.id }, enabled = loaded && !busy) { Icon(Icons.Outlined.Add, null); Text("New page") }
            OutlinedButton(onClick = { import.launch(arrayOf("text/*", "application/zip", "application/json", "application/octet-stream")) }, enabled = loaded && !busy) { Text("Import") }
            OutlinedButton(onClick = { exportZip.launch(NotebookInterchange.safeFilename(notebook.notebookTitle) + ".zip") }, enabled = notebook.pages.isNotEmpty()) { Text("Export ZIP") }
            OutlinedButton(onClick = { exportJson.launch(NotebookInterchange.safeFilename(notebook.notebookTitle) + ".json") }, enabled = notebook.pages.isNotEmpty()) { Text("Export JSON") }
            OutlinedButton(onClick = { publish = true }, enabled = notebook.pages.isNotEmpty()) { Text("Publish pages") }
        } }
        if (!loaded || busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (loaded && notebook.pages.isEmpty()) item { Text("Start a page or import Markdown, text, a web notebook ZIP, or a notebook JSON backup.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 24.dp)) }
        itemsIndexed(notebook.pages.sortedBy { it.order }, key = { _, page -> page.id }) { index, page ->
            Card(onClick = { active = page.id }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (page.editingDocument != null) Icons.Outlined.CloudDone else Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(page.title, style = MaterialTheme.typography.titleMedium); Text(page.filename, style = MaterialTheme.typography.bodySmall) }; IconButton(onClick = { rename = page }) { Icon(Icons.Outlined.Edit, "Rename ${page.title}") } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { val ordered = notebook.pages.sortedBy { it.order }.toMutableList(); java.util.Collections.swap(ordered, index, index - 1); update(notebook.copy(pages = ordered.mapIndexed { order, item -> item.copy(order = order) })) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move page up") }
                        IconButton(onClick = { val ordered = notebook.pages.sortedBy { it.order }.toMutableList(); java.util.Collections.swap(ordered, index, index + 1); update(notebook.copy(pages = ordered.mapIndexed { order, item -> item.copy(order = order) })) }, enabled = index < notebook.pages.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "Move page down") }
                        IconButton(onClick = { exporting = page; exportPage.launch(page.filename) }) { Icon(Icons.Outlined.Download, "Export ${page.title}") }
                        IconButton(onClick = { delete = page }) { Icon(Icons.Outlined.DeleteOutline, "Delete ${page.title}") }
                    }
                }
            }
        }
    }
    notebook.pages.firstOrNull { it.id == active }?.let { page -> LocalNotebookEditor(page, api, sessions, onOpenFile, onChange = ::updatePage, onClose = { scope.launch { try { persist(); active = null } catch (e: Exception) { error = e.message } } }) }
    rename?.let { page -> RenameNotebookDialog("Rename page", page.title, page.filename, { rename = null }) { title, filename -> updatePage(page.copy(title = title, filename = NotebookInterchange.safeFilename(filename))); rename = null } }
    if (renameNotebook) RenameNotebookDialog("Rename notebook", notebook.notebookTitle, null, { renameNotebook = false }) { title, _ -> update(notebook.copy(notebookTitle = title)); renameNotebook = false }
    delete?.let { page -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete this local page?") }, text = { Text("${page.title} will be removed from this device. A published file stays in OhioFiles.") }, confirmButton = { TextButton(onClick = { update(notebook.copy(pages = notebook.pages.filterNot { it.id == page.id })); delete = null }) { Text("Delete page") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
    if (publish) PublishNotebookDialog(notebook, api, sessions, onDismiss = { publish = false }, onPublished = { pages -> update(notebook.copy(pages = pages)); scope.launch { persist() } })
}

@Composable private fun RenameNotebookDialog(title: String, initial: String, initialFilename: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by remember { mutableStateOf(initial) }; var filename by remember { mutableStateOf(initialFilename.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { OutlinedTextField(name, { name = it.take(100) }, label = { Text("Title") }); if (initialFilename != null) OutlinedTextField(filename, { filename = it.take(180) }, label = { Text("File name") }) } }, confirmButton = { TextButton(onClick = { onSave(name.trim(), filename.trim()) }, enabled = name.isNotBlank() && (initialFilename == null || filename.isNotBlank())) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun LocalNotebookEditor(page: NotebookPage, api: OhioApiClient, sessions: SessionRepository, onOpenFile: (FileItem) -> Unit, onChange: (NotebookPage) -> Unit, onClose: () -> Unit) {
    var value by remember(page.id) { mutableStateOf(TextFieldValue(page.content)) }; var preview by rememberSaveable(page.id) { mutableStateOf(false) }; var find by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }; var saving by remember { mutableStateOf(false) }; var reloadConfirm by remember { mutableStateOf(false) }; val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val sessionKey by sessions.sessionKeyFlow.collectAsStateWithLifecycle(null)
    val readOnly = page.editingDocument?.access == "VIEW"
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { onClose() }
        Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close page") }; Column(Modifier.weight(1f)) { Text(page.title, style = MaterialTheme.typography.titleMedium); Text("Draft saved on this device", style = MaterialTheme.typography.labelSmall) }; IconButton(onClick = { find = !find; preview = false }) { Icon(Icons.Outlined.FindReplace, "Find and replace") }
                if (page.editingDocument != null) TextButton(onClick = { val savingText = value.text; val link = page.editingDocument; scope.launch {
                    saving = true
                    try { val key = sessions.getSessionKey() ?: error("Connect an OhioFiles session first"); require(link.checksumSha256.isNotBlank()) { "Load the online version before saving this linked page" }; val data = api.request("collaboration/files/${link.id}/text", "PUT", api.body("sessionKey" to key, "content" to savingText, "expectedChecksum" to link.checksumSha256)).getOrThrow().jsonObject; onChange(page.copy(content = value.text, savedContent = savingText, editingDocument = link.copy(checksumSha256 = data["checksumSha256"]!!.jsonPrimitive.content))) }
                    catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { saving = false }
                } }, enabled = !saving && page.editingDocument.access != "VIEW") { Text("Save online") }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!preview, { preview = false }, { Text("Source") }); FilterChip(preview, { preview = true }, { Text("Preview") }) }
            if (find) DocumentFindReplace(value, { value = it; onChange(page.copy(content = it.text)) }, readOnly) { find = false }
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (page.editingDocument != null) Row(Modifier.padding(horizontal = 12.dp)) {
                TextButton(onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(page.title, value.text)) }) { Text("Copy draft") }
                TextButton(onClick = { reloadConfirm = true }, enabled = !saving) { Text("Load online version") }
                if (page.recoveryContent != null) TextButton(onClick = { val restored = page.recoveryContent; value = TextFieldValue(restored); onChange(page.copy(content = restored, recoveryContent = page.content)) }) { Text("Restore previous draft") }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (preview) NativeMarkdownReader(value.text, api = api, sessionKey = sessionKey, onOpenFile = onOpenFile, modifier = Modifier.weight(1f).padding(horizontal = 20.dp))
            else { DocumentFormattingToolbar(value, { value = it; onChange(page.copy(content = it.text)) }, readOnly); NativeSourceEditor(value, { value = it; onChange(page.copy(content = it.text)) }, page.filename, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), readOnly) }
        } }
    }
    if (reloadConfirm && page.editingDocument != null) AlertDialog(onDismissRequest = { reloadConfirm = false }, title = { Text("Load the online version?") }, text = { Text("The current local text is kept as a recoverable draft on this page.") }, confirmButton = { TextButton(onClick = { scope.launch {
        saving = true
        try { val data = api.request("collaboration/files/${page.editingDocument.id}/text", query = mapOf("sessionKey" to sessions.getSessionKey())).getOrThrow().jsonObject; val latest = data["content"]!!.jsonPrimitive.content; val previous = value.text; value = TextFieldValue(latest); onChange(page.copy(content = latest, savedContent = latest, recoveryContent = previous, editingDocument = page.editingDocument.copy(checksumSha256 = data["checksumSha256"]!!.jsonPrimitive.content, access = data["access"]!!.jsonPrimitive.content))); reloadConfirm = false; error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message; reloadConfirm = false } finally { saving = false }
    } }) { Text("Load latest") } }, dismissButton = { TextButton(onClick = { reloadConfirm = false }) { Text("Cancel") } })
}

@Composable private fun PublishNotebookDialog(notebook: LocalNotebook, api: OhioApiClient, sessions: SessionRepository, onDismiss: () -> Unit, onPublished: (List<NotebookPage>) -> Unit) {
    val scope = rememberCoroutineScope(); var selected by remember { mutableStateOf(notebook.pages.filter { it.editingDocument == null }.map { it.id }.toSet()) }
    var folders by remember { mutableStateOf(emptyList<JsonObject>()) }; var folder by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }; var progress by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { try { sessions.ensureSession().getOrThrow(); folders = (api.request("folders", query = mapOf("sessionKey" to sessions.getSessionKey())).getOrThrow().jsonObject["folders"] as? JsonArray)?.map { it.jsonObject }.orEmpty() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Publish notebook pages") }, text = { Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
        Text("Selected local pages become private files in OhioFiles. Published pages keep their online link.", style = MaterialTheme.typography.bodySmall)
        notebook.pages.forEach { page -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(page.id in selected, { checked -> selected = if (checked) selected + page.id else selected - page.id }, enabled = !busy && page.editingDocument == null); Text(page.title + if (page.editingDocument != null) " · already linked" else "") } }
        Text("Destination folder", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { FilterChip(folder == null, { folder = null }, { Text("Root") }); folders.forEach { item -> val id = item["id"]!!.jsonPrimitive.content; FilterChip(folder == id, { folder = id }, { Text(item["name"]!!.jsonPrimitive.content) }) } }
        if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = { scope.launch {
        busy = true; error = null; val updated = notebook.pages.toMutableList()
        try {
            val key = sessions.getSessionKey() ?: error("Session unavailable")
            val pages = notebook.pages.filter { it.id in selected && it.editingDocument == null }
            pages.forEachIndexed { index, page ->
                progress = "Publishing ${index + 1} of ${pages.size} · ${page.filename}"
                val file = api.uploadText(key, page.filename, page.content, folder).getOrThrow()
                val location = updated.indexOfFirst { it.id == page.id }
                updated[location] = page.copy(savedContent = page.content, editingDocument = NotebookDocument(file.id, file.slug, file.displayName))
                onPublished(updated.toList())
                val metadata = api.request("collaboration/files/${file.id}/text", query = mapOf("sessionKey" to key)).getOrThrow().jsonObject
                updated[location] = page.copy(savedContent = page.content, editingDocument = NotebookDocument(file.id, file.slug, file.displayName, metadata["access"]?.jsonPrimitive?.content ?: "OWNER", metadata["checksumSha256"]?.jsonPrimitive?.content.orEmpty()))
                onPublished(updated.toList())
            }; onDismiss()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Publish failed. Successfully published pages remain linked." } finally { busy = false }
    } }, enabled = !busy && selected.isNotEmpty()) { Text(if (busy) "Publishing…" else "Publish selected") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
