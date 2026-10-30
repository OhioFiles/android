package cloud.ohiofiles.app.ui.screens.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.ohiofiles.app.data.model.*
import cloud.ohiofiles.app.platform.NativeActions
import cloud.ohiofiles.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel, onOpenFile: (FileItem) -> Unit, onNavigateToUpload: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.undoMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var section by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("All") }
    var creating by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(false) }
    var renameFile by remember { mutableStateOf<FileItem?>(null) }
    var renameFolder by remember { mutableStateOf<FolderItem?>(null) }
    var moveFile by remember { mutableStateOf<FileItem?>(null) }
    var moveFolder by remember { mutableStateOf<FolderItem?>(null) }
    var deleteFile by remember { mutableStateOf<FileItem?>(null) }
    var deleteFolder by remember { mutableStateOf<FolderItem?>(null) }
    val snack = remember { SnackbarHostState() }
    val success = state as? FilesUiState.Success
    var restoredFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { restoredFolderId?.let(viewModel::navigateToBreadcrumb) }
    LaunchedEffect(success?.folderId) { if (success != null) restoredFolderId = success.folderId }
    BackHandler(enabled = success?.folderId != null) { viewModel.navigateUp() }
    LaunchedEffect(message) { message?.let { if (snack.showSnackbar(it, if (it.endsWith("deleted")) "Undo" else null) == SnackbarResult.ActionPerformed) viewModel.undoDelete(); viewModel.clearUndoMessage() } }
    val hasItems = success?.let { if (section == 1) it.folders.isNotEmpty() else it.files.isNotEmpty() } == true
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snack) }, floatingActionButton = {
        if (hasItems) ExtendedFloatingActionButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (section == 1) creating = true else onNavigateToUpload() },
            icon = { Icon(if (section == 1) Icons.Outlined.CreateNewFolder else Icons.Outlined.Add, null) }, text = { Text(if (section == 1) "New folder" else "Upload") },
            shape = RoundedCornerShape(24.dp), containerColor = MaterialTheme.colorScheme.primary)
    }, modifier = modifier) { padding ->
        LazyVerticalGrid(columns = if (success?.isGrid != false) GridCells.Adaptive(160.dp) else GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item(key = "browse-controls", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Files", "Folders", "Saved").forEachIndexed { index, label ->
                        SegmentedButton(selected = section == index, onClick = { section = index; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(search, { search = it; viewModel.onSearch(it) }, placeholder = { Text("Search this folder") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth(), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (success?.folderId != null) {
                        TextButton(onClick = { viewModel.navigateToBreadcrumb(null) }) { Icon(Icons.Outlined.Home, null, Modifier.size(18.dp)); Text(" My files") }
                        Text("/ ${success.currentFolder?.name ?: "Folder"}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    } else Text(if (section == 1) "FOLDERS" else if (section == 2) "SAVED FILES" else "YOUR FILES · ${success?.files?.size ?: 0}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Box { IconButton({ sort = true }) { Icon(Icons.Outlined.Sort, "Sort files") }; DropdownMenu(sort, { sort = false }) {
                        listOf("Newest first", "Name A–Z", "Largest first").forEachIndexed { i, label -> DropdownMenuItem({ Text(label) }, { viewModel.setSortOrder(FileSortOrder.entries[i]); sort = false }) }
                    } }
                    IconButton({ viewModel.toggleViewMode() }) { Icon(if (success?.isGrid != false) Icons.Outlined.ViewList else Icons.Outlined.GridView, "Switch grid or list") }
                    IconButton({ viewModel.loadData() }) { Icon(Icons.Outlined.Refresh, "Refresh files") }
                }
                if (success?.breadcrumbs?.isNotEmpty() == true) Row(Modifier.horizontalScroll(rememberScrollState())) {
                    success.breadcrumbs.forEach { crumb -> TextButton({ viewModel.navigateToBreadcrumb(crumb.id) }) { Text(crumb.name ?: "Folder") } }
                }
                if (section != 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("All", "Images", "Video", "Audio", "Documents").forEach { label -> FilterChip(type == label, { type = label }, { Text(label) }) }
                }
            }
            }
            when (val current = state) {
                FilesUiState.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                is FilesUiState.Error -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyCloud("Let's reconnect", current.message, "Try again", viewModel::loadData) }
                is FilesUiState.Success -> {
                    if (current.refreshing) item(span = { GridItemSpan(maxLineSpan) }) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    val folders = current.folders.filter { it.name.contains(search, true) }
                    val files = current.files.filter { (section != 2 || it.isSaved) && when (type) { "Images" -> it.isImage; "Video" -> it.isVideo; "Audio" -> it.isAudio; "Documents" -> it.isPdf || it.isTextOrCode; else -> true } }
                    if ((section == 1 && folders.isEmpty()) || (section != 1 && files.isEmpty() && (current.folderId == null || folders.isEmpty()))) {
                        item(span = { GridItemSpan(maxLineSpan) }) { EmptyCloud(if (search.isNotBlank()) "Nothing found" else if (section == 1) "Make room for ideas" else if (section == 2) "Keep your favorites close" else "A home for your files",
                            if (search.isNotBlank()) "Try another name in this folder." else if (section == 1) "Create a folder to bring a little order to your cloud." else if (section == 2) "Save a file from its menu to find it here." else "Choose something from your phone. Share it in a moment.",
                            if (section == 1) "Create folder" else "Choose files", { if (section == 1) creating = true else onNavigateToUpload() }) }
                    } else {
                        if (section == 1 || current.folderId != null) items(folders, key = { "folder-${it.id}" }) { folder ->
                            FolderCard(folder, { viewModel.openFolder(it); section = 0; search = "" }, { deleteFolder = it }, onRename = { renameFolder = it }, onMove = { moveFolder = it }, onShare = { NativeActions.share(context, viewModel.getFolderUrl(it)) }, onQr = { qr = viewModel.getFolderUrl(it) }, compact = !current.isGrid, onDownload = { NativeActions.download(context, viewModel.getFolderDownloadUrl(it), "${it.name}.zip", "application/zip") })
                        }
                        if (section != 1) items(files, key = { "file-${it.id}" }) { file ->
                            FileCard(file, viewModel.getDownloadUrl(file), onOpenFile, { if (it.passwordRequired || it.isOneTime) onOpenFile(it) else NativeActions.download(context, viewModel.getDownloadUrl(it), it.displayName, it.mimeType) },
                                { NativeActions.share(context, viewModel.getPublicUrl(it.slug)) }, { qr = viewModel.getPublicUrl(it.slug) }, viewModel::toggleBookmark, { deleteFile = it }, compact = !current.isGrid, onRename = { renameFile = it }, onMove = { moveFile = it })
                        }
                    }
                }
            }
        }
    }
    if (creating) FolderEditor(onDismiss = { creating = false }) { name, public, password -> viewModel.createFolder(name, public, password); creating = false }
    if (qr != null) QrCodeDialog(title = "Share with a scan", content = qr!!, onDismiss = { qr = null })
    if (renameFile != null || renameFolder != null) NameDialog("Rename", renameFile?.displayName ?: renameFolder!!.name, { renameFile = null; renameFolder = null }) { name -> renameFile?.let { viewModel.renameFile(it, name) }; renameFolder?.let { viewModel.renameFolder(it, name) }; renameFile = null; renameFolder = null }
    if (moveFile != null || moveFolder != null) {
        DestinationDialog(viewModel, moveFolder?.id, { moveFile = null; moveFolder = null }) { destination ->
            moveFile?.let { viewModel.moveFile(it, destination) }; moveFolder?.let { viewModel.moveFolder(it, destination) }; moveFile = null; moveFolder = null
        }
    }
    if (deleteFile != null || deleteFolder != null) AlertDialog(onDismissRequest = { deleteFile = null; deleteFolder = null }, title = { Text("Delete ${if (deleteFolder != null) "folder" else "file"}?") }, text = { Text("You can undo this immediately after deleting.") }, confirmButton = { TextButton({ deleteFile?.let(viewModel::deleteFile); deleteFolder?.let(viewModel::deleteFolder); deleteFile = null; deleteFolder = null }) { Text("Delete") } }, dismissButton = { TextButton({ deleteFile = null; deleteFolder = null }) { Text("Cancel") } })
}

@Composable
fun EmptyCloud(title: String, description: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 260.dp).padding(horizontal = 20.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(topStart = 48.dp, topEnd = 28.dp, bottomEnd = 48.dp, bottomStart = 28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Outlined.CloudQueue, null, Modifier.padding(20.dp).size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        FilledTonalButton(onAction, Modifier.padding(top = 22.dp)) { Text(action) }
    }
}
@Composable
fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = { TextButton({ onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
@Composable
private fun FolderEditor(onDismiss: () -> Unit, onConfirm: (String, Boolean, String?) -> Unit) {
    var name by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var public by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("A new home for your files") }, text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, singleLine = true)
        OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Public folder", Modifier.weight(1f)); Switch(public, { public = it }) }
    } }, confirmButton = { TextButton({ onConfirm(name.trim(), public, password.ifBlank { null }) }, enabled = name.isNotBlank()) { Text("Create folder") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun DestinationDialog(viewModel: FilesViewModel, excludedId: String?, onDismiss: () -> Unit, onChoose: (String?) -> Unit) {
    var destination by remember { mutableStateOf<String?>(null) }
    var response by remember { mutableStateOf<FolderListResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(destination) { viewModel.destinationFolders(destination).onSuccess { response = it; error = null }.onFailure { error = it.message } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Move to folder") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            TextButton({ destination = null }) { Text("My files") }
            response?.path.orEmpty().forEach { crumb -> TextButton({ destination = crumb.id }) { Text("› ${crumb.name ?: "Folder"}") } }
            HorizontalDivider()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            response?.folders.orEmpty().filter { it.id != excludedId && (!it.isShared || it.shareAccess == "EDIT") }.forEach { folder ->
                TextButton({ destination = folder.id }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Folder, null); Text("  ${folder.name}", Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
    }, confirmButton = { TextButton({ onChoose(destination) }, enabled = error == null && response != null) { Text("Move here") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
