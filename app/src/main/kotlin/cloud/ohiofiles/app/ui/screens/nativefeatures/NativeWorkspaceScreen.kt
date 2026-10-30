package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cloud.ohiofiles.app.data.api.ApiException
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import cloud.ohiofiles.app.ui.document.*

@Composable
fun NativeWorkspaceScreen(api: OhioApiClient, sessionRepo: SessionRepository, onOpenFile: (FileItem) -> Unit,
    initialDocument: FileItem? = null, onDocumentHandled: () -> Unit = {}, browserContent: @Composable () -> Unit = {}) {
    var documents by rememberSaveable { mutableStateOf(false) }
    var notebook by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialDocument?.id) { if (initialDocument != null) { documents = true; notebook = false } }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SegmentedButton(!documents && !notebook, { documents = false; notebook = false }, SegmentedButtonDefaults.itemShape(0, 3)) { Text("Files") }
            SegmentedButton(documents && !notebook, { documents = true; notebook = false }, SegmentedButtonDefaults.itemShape(1, 3)) { Text("Documents") }
            SegmentedButton(notebook, { notebook = true; documents = false }, SegmentedButtonDefaults.itemShape(2, 3)) { Text("Notebook") }
        }
        Box(Modifier.weight(1f)) { if (notebook) NativeNotebookPane(api, sessionRepo, onOpenFile) else if (documents) WorkspaceDocuments(api, sessionRepo, onOpenFile, initialDocument, onDocumentHandled) else browserContent() }
    }
}

@Composable private fun WorkspaceDocuments(api: OhioApiClient, sessions: SessionRepository, onOpenFile: (FileItem) -> Unit, initialDocument: FileItem?, onDocumentHandled: () -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf<String?>(null) }; var store by remember { mutableStateOf<WorkspaceDraftStore?>(null) }
    var files by remember { mutableStateOf(emptyList<FileItem>()) }; var folders by remember { mutableStateOf(emptyList<JsonObject>()) }
    var tabs by remember { mutableStateOf(emptyList<FileItem>()) }; var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var folderId by rememberSaveable { mutableStateOf<String?>(null) }; var folderName by rememberSaveable { mutableStateOf("All documents") }
    var query by rememberSaveable { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }; var create by remember { mutableStateOf(false) }
    var docsToken by remember { mutableStateOf<String?>(null) }
    var tabPrefKey by remember { mutableStateOf("") }
    var tabsRestored by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("workspace_open_tabs", Context.MODE_PRIVATE) }
    suspend fun load() {
        busy = true
        try {
            suspend fun restoreLocal(sessionId: String) {
                if (store != null) return
                store = WorkspaceDraftStore(context, api.getBaseUrl(), sessionId)
                tabPrefKey = "${api.getBaseUrl()}|$sessionId"
                val saved = withContext(Dispatchers.IO) { prefs.getString(tabPrefKey, null) }
                if (saved != null) tabs = runCatching { api.json.decodeFromString<List<FileItem>>(saved) }.getOrDefault(emptyList())
                tabsRestored = true
            }
            // Restore local tabs/drafts before contacting the server so offline cold starts work.
            key = sessions.getSessionKey()
            sessions.getSessionId()?.let { restoreLocal(it) }
            val session = sessions.ensureSession().getOrThrow()
            key = sessions.getSessionKey() ?: error("Session unavailable")
            restoreLocal(session.id)
            val all = mutableListOf<FileItem>(); var page = 1
            do {
                val result = api.request("files", query = mapOf("sessionKey" to key, "folderId" to folderId, "page" to page.toString(), "limit" to "100")).getOrThrow().jsonObject
                all += api.json.decodeFromJsonElement<List<FileItem>>(result["files"] ?: JsonArray(emptyList()))
                val more = result.flag("hasMore"); page++
            } while (more && page <= 100)
            files = all.distinctBy { it.id }.filter { it.isTextOrCode && !it.isOneTime }
            folders = api.request("folders", query = mapOf("sessionKey" to key, "parentId" to (folderId ?: "root"), "includeStats" to "true")).getOrThrow().jsonObject.objects("folders")
            error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Documents could not load" } finally { busy = false }
    }
    LaunchedEffect(reload, folderId) { load() }
    LaunchedEffect(tabs, tabPrefKey, tabsRestored) { if (tabsRestored && tabPrefKey.isNotEmpty()) withContext(Dispatchers.IO) { prefs.edit().putString(tabPrefKey, api.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(FileItem.serializer()), tabs)).commit() } }
    fun open(file: FileItem) { if (file !in tabs) tabs = (tabs.filterNot { it.id == file.id } + file).takeLast(12); activeId = file.id }
    LaunchedEffect(initialDocument?.id, store) { if (initialDocument != null && store != null) { open(initialDocument); onDocumentHandled() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "document-controls") {
            Column {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Documents", style = MaterialTheme.typography.headlineSmall); Text("Write, read, and keep things moving.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FilledTonalIconButton(onClick = { create = true }, enabled = key != null) { Icon(Icons.Outlined.Add, "Create document or docs folder") }
        }
        if (tabs.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tabs, key = { it.id }) { file -> InputChip(selected = false, onClick = { activeId = file.id }, label = { Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) }, leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(18.dp)) }, trailingIcon = { IconButton(onClick = { tabs = tabs.filterNot { it.id == file.id } }, Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Close ${file.displayName}", Modifier.size(16.dp)) } }) }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), placeholder = { Text("Find a document") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp))
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (folderId != null) TextButton(onClick = { folderId = null; folderName = "All documents" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text("All") }
            Text(folderName, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f)); IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Refresh documents") }
        }
        error?.let { FeatureError(it) { reload++ } }; if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
            if (query.isBlank()) items(folders, key = { "folder-${it.text("id")}" }) { folder ->
                OutlinedCard(onClick = { folderId = folder.text("id"); folderName = folder.text("name") }, shape = RoundedCornerShape(20.dp)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(if (folder.text("folderType") == "DOCS") Icons.Outlined.MenuBook else Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.tertiary); Text(folder.text("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); if (folder.text("folderType") == "DOCS") IconButton(onClick = { docsToken = folder.text("slug").ifBlank { folder.text("id") } }) { Icon(Icons.Outlined.MenuBook, "Read ${folder.text("name")} documentation") } else Icon(Icons.Outlined.ChevronRight, null) } }
            }
            val filtered = files.filter { it.displayName.contains(query, true) }
            if (filtered.isEmpty() && !busy && error == null) item { FeatureEmpty(if (query.isBlank()) "Start with a thought" else "No matching documents", if (query.isBlank()) "Create a note, Markdown document, or source file. Drafts stay safe on this device." else "Try another name.") }
            items(filtered, key = { it.id }) { file ->
                Card(onClick = { open(file) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) { Icon(Icons.Outlined.Description, null, Modifier.padding(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                        Column(Modifier.weight(1f)) { Text(file.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(listOfNotNull(file.folderPath ?: file.folderName, file.formattedSize).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { onOpenFile(file) }) { Icon(Icons.Outlined.OpenInNew, "Open ${file.displayName} in viewer") }
                    }
                }
            }
        }
    if (create && key != null) CreateWorkspaceDialog(api, key!!, folderId, onDismiss = { create = false }, onCreated = { file -> create = false; reload++; file?.let(::open) })
    val active = tabs.firstOrNull { it.id == activeId }
    if (active != null && key != null && store != null) WorkspaceEditor(api, key!!, active, tabs, store!!,
        onSelect = { activeId = it }, onClose = { activeId = null }, onSaved = { reload++ }, onOpenFile = onOpenFile)
    docsToken?.let { token -> Dialog(onDismissRequest = { docsToken = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) { NativeDocsScreen(token, api, sessions, { file -> docsToken = null; open(file) }, { docsToken = null }) }
    } }
}

@Composable private fun CreateWorkspaceDialog(api: OhioApiClient, sessionKey: String, parentId: String?, onDismiss: () -> Unit, onCreated: (FileItem?) -> Unit) {
    val scope = rememberCoroutineScope(); var kind by rememberSaveable { mutableStateOf("Markdown") }; var name by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Something new") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Markdown", "Text", "Source", "Docs folder").forEach { value -> FilterChip(kind == value, { kind = value }, label = { Text(value) }) } }
            OutlinedTextField(name, { name = it.take(180) }, label = { Text(if (kind == "Docs folder") "Folder name" else "File name") }, placeholder = { Text(when (kind) { "Markdown" -> "My idea.md"; "Source" -> "hello.kt"; "Text" -> "Notes.txt"; else -> "Project handbook" }) }, singleLine = true)
            Text(if (kind == "Docs folder") "A shared home for your Markdown documentation." else "Private by default. Open it, write, and save when you're ready.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { scope.launch {
        busy = true
        try {
            if (kind == "Docs folder") { api.request("folders", "POST", api.body("sessionKey" to sessionKey, "name" to name.trim(), "parentId" to parentId, "folderType" to "DOCS", "isPublic" to false, "defaultAccess" to "NONE")).getOrThrow(); onCreated(null) }
            else {
                val filename = name.trim().let { if ('.' in it) it else it + when (kind) { "Markdown" -> ".md"; "Source" -> ".kt"; else -> ".txt" } }
                val initial = if (kind == "Markdown") "# ${filename.substringBeforeLast('.')}\n\n" else ""
                val file = api.uploadText(sessionKey, filename, initial, parentId).getOrThrow(); onCreated(file)
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not create item" } finally { busy = false }
    } }, enabled = !busy && name.isNotBlank() && '/' !in name && '\\' !in name) { Text(if (busy) "Creating…" else "Create") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}

@Composable private fun WorkspaceEditor(api: OhioApiClient, sessionKey: String, file: FileItem, tabs: List<FileItem>, drafts: WorkspaceDraftStore, onSelect: (String) -> Unit, onClose: () -> Unit, onSaved: () -> Unit, onOpenFile: (FileItem) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val lifecycle = LocalLifecycleOwner.current.lifecycle
    var content by remember(file.id) { mutableStateOf("") }; var original by remember(file.id) { mutableStateOf("") }; var checksum by remember(file.id) { mutableStateOf("") }
    var access by remember(file.id) { mutableStateOf("VIEW") }; var ready by remember(file.id) { mutableStateOf(false) }; var busy by remember(file.id) { mutableStateOf(false) }
    var remoteLoaded by remember(file.id) { mutableStateOf(false) }
    var error by remember(file.id) { mutableStateOf<String?>(null) }; var draftError by remember(file.id) { mutableStateOf<String?>(null) }; var conflict by remember(file.id) { mutableStateOf(false) }
    var preview by rememberSaveable(file.id) { mutableStateOf(false) }; var closeConfirm by remember { mutableStateOf(false) }; var reload by remember { mutableIntStateOf(0) }
    var editorValue by remember(file.id) { mutableStateOf(TextFieldValue("")) }; var find by rememberSaveable(file.id) { mutableStateOf(false) }
    val dirty = ready && (content != original || !remoteLoaded)
    suspend fun persist() { if (ready) { if (dirty) drafts.save(file.id, content, checksum) else drafts.remove(file.id) } }
    LaunchedEffect(file.id, reload) {
        busy = true; ready = false; remoteLoaded = false; error = null
        try {
            val saved = drafts.load(file.id)
            if (saved != null) { content = saved.content; checksum = saved.checksum; access = "EDIT"; ready = true }
            val data = api.request("collaboration/files/${file.id}/text", query = mapOf("sessionKey" to sessionKey)).getOrThrow().jsonObject
            original = data.text("content"); content = original; checksum = data.text("checksumSha256"); access = data.text("access", "VIEW")
            if (saved != null && saved.content != original) { content = saved.content; conflict = saved.checksum.isNotBlank() && saved.checksum != checksum; checksum = saved.checksum }
            remoteLoaded = true; ready = true
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Document could not open" } finally { busy = false }
        editorValue = TextFieldValue(content)
    }
    LaunchedEffect(file.id, content, ready, checksum, original, remoteLoaded) {
        if (ready) { delay(400); try { if (dirty) drafts.save(file.id, content, checksum) else drafts.remove(file.id); draftError = null } catch (e: CancellationException) { throw e } catch (e: Exception) { draftError = e.message ?: "Draft could not save" } }
    }
    val latestContent by rememberUpdatedState(content); val latestChecksum by rememberUpdatedState(checksum); val latestDirty by rememberUpdatedState(dirty)
    DisposableEffect(lifecycle, file.id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE && ready) scope.launch(Dispatchers.IO) { try { if (latestDirty) drafts.save(file.id, latestContent, latestChecksum) else drafts.remove(file.id) } catch (_: Exception) {} } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    fun requestClose() {
        if (dirty) closeConfirm = true
        else scope.launch { try { persist(); onClose() } catch (e: CancellationException) { throw e } catch (e: Exception) { draftError = e.message } }
    }
    Dialog(onDismissRequest = ::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, decorFitsSystemWindows = false)) {
        BackHandler { requestClose() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::requestClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close editor") }
                    Column(Modifier.weight(1f)) { Text(file.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(if (access == "VIEW") "Read only" else if (dirty) "Unsaved · draft on this device" else "Saved to OhioFiles", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = { find = !find; preview = false }) { Icon(Icons.Outlined.FindReplace, "Find and replace") }
                    FilledTonalButton(onClick = { val savingText = content; val savingChecksum = checksum; scope.launch {
                        busy = true; error = null
                        try {
                            val payload = buildJsonObject { put("sessionKey", sessionKey); put("content", savingText); if (savingChecksum.isNotBlank()) put("expectedChecksum", savingChecksum) }
                            val data = api.request("collaboration/files/${file.id}/text", "PUT", payload).getOrThrow().jsonObject
                            checksum = data.text("checksumSha256"); original = savingText; conflict = false
                            if (content == savingText) drafts.remove(file.id) else drafts.save(file.id, content, checksum)
                            onSaved()
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { if (e is ApiException && e.status == 409) conflict = true; error = e.message ?: "Save failed" } finally { busy = false }
                    } }, enabled = ready && remoteLoaded && dirty && access != "VIEW" && !busy && !conflict) { Icon(Icons.Outlined.CloudUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save") }
                }
                if (tabs.size > 1) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(tabs, key = { it.id }) { tab -> FilterChip(tab.id == file.id, { scope.launch { try { persist(); onSelect(tab.id) } catch (e: Exception) { draftError = e.message } } }, label = { Text(tab.displayName, maxLines = 1, modifier = Modifier.widthIn(max = 140.dp), overflow = TextOverflow.Ellipsis) }) } }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    SegmentedButton(!preview, { preview = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Source") }
                    SegmentedButton(preview, { preview = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Preview") }
                }
                if (find && ready) DocumentFindReplace(editorValue, { editorValue = it; content = it.text }, access == "VIEW" || busy) { find = false }
                error?.takeUnless { conflict }?.let { FeatureError(it) { scope.launch { try { persist(); reload++ } catch (e: CancellationException) { throw e } catch (e: Exception) { draftError = e.message } } } }; draftError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                if (conflict) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp)) { Text("A newer version is online", style = MaterialTheme.typography.titleSmall); Text("Your draft is safe. Copy it before loading the latest version.", style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer), onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(file.displayName, content)); Toast.makeText(context, "Draft copied", Toast.LENGTH_SHORT).show() }) { Text("Copy draft") }
                            TextButton(colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer), onClick = { closeConfirm = true }) { Text("Resolve") } }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (ready) {
                    if (preview) NativeMarkdownReader(content, api, sessionKey, onOpenFile, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), assetFolderId = file.folderId)
                    else {
                        DocumentFormattingToolbar(editorValue, { editorValue = it; content = it.text }, access == "VIEW" || busy)
                        NativeSourceEditor(editorValue, { editorValue = it; content = it.text }, file.displayName, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), access == "VIEW" || busy)
                    }
                    Text("${content.length} characters · ${content.lineSequence().count()} lines", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
                } else if (!busy && error == null) FeatureEmpty("Opening document", "Getting the latest version.")
            }
        }
    }
    if (closeConfirm) AlertDialog(onDismissRequest = { closeConfirm = false }, title = { Text(if (conflict) "Resolve this draft" else "Keep your draft?") }, text = { Text(if (conflict) "Keep this draft to review it later, or discard it and load the latest online version." else "Keep it safely on this device, or discard your unsaved changes.") },
        confirmButton = { TextButton(onClick = { scope.launch { try { persist(); closeConfirm = false; onClose() } catch (e: Exception) { draftError = e.message; closeConfirm = false } } }) { Text("Keep draft") } },
        dismissButton = { TextButton(onClick = { scope.launch { try { drafts.remove(file.id); closeConfirm = false; if (conflict) { conflict = false; reload++ } else onClose() } catch (e: Exception) { draftError = e.message } } }) { Text(if (conflict) "Load latest" else "Discard changes") } })
}

@Composable internal fun NativeMarkdownPreview(source: String) {
    NativeMarkdownReader(source)
}
