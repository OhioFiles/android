package cloud.ohiofiles.app.ui.screens.upload

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import cloud.ohiofiles.app.data.upload.UploadPhase
import cloud.ohiofiles.app.data.upload.generateUploadPassword
import cloud.ohiofiles.app.data.repository.AppearanceRepository
import cloud.ohiofiles.app.data.model.AppearanceConfig
import androidx.compose.ui.text.style.TextOverflow
import cloud.ohiofiles.app.data.model.FolderItem
import cloud.ohiofiles.app.data.model.FolderListResponse
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.ui.components.QrCodeDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreen(
    viewModel: UploadViewModel,
    onNavigateToFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val completed by viewModel.completed.collectAsStateWithLifecycle()
    val selectedFile by viewModel.selectedFile.collectAsStateWithLifecycle()
    val customSlug by viewModel.customSlug.collectAsStateWithLifecycle()
    val customFilename by viewModel.customFilename.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val isPublic by viewModel.isPublic.collectAsStateWithLifecycle()
    val isOneTime by viewModel.isOneTime.collectAsStateWithLifecycle()
    val availableFolders by viewModel.availableFolders.collectAsStateWithLifecycle()
    val selectedFolder by viewModel.selectedFolder.collectAsStateWithLifecycle()

    val uploadPreferences = remember { AppearanceRepository(context) }
    val preferences by uploadPreferences.appearanceFlow.collectAsStateWithLifecycle(AppearanceConfig(autoShowQrOnUpload = false))
    var editingItem by remember { mutableStateOf<SelectedFileInfo?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf<String?>(null) }
    var folderMenuExpanded by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            viewModel.onFilesSelected(context, uris)
        }
    }

    val uploadPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.startUpload(context) }
    fun startNativeUpload() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) uploadPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else viewModel.startUpload(context)
    }
    LaunchedEffect(Unit) { viewModel.loadFolders() }
    val latestSuccess = (uiState as? UploadUiState.Success)
    LaunchedEffect(latestSuccess?.response?.finalId, preferences.autoShowQrOnUpload) {
        val latest = latestSuccess ?: return@LaunchedEffect
        val shown = context.getSharedPreferences("upload_preferences", Context.MODE_PRIVATE)
        val id = latest.response.finalId ?: return@LaunchedEffect
        if (preferences.autoShowQrOnUpload && shown.getString("last_qr", null) != id) { shown.edit().putString("last_qr", id).apply(); showQrDialog = latest.fullUrl }
    }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Send something great.", style = MaterialTheme.typography.displaySmall)
        Text("From your phone to anywhere.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (queue.size > 1) Text("${queue.size} files in your upload queue", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (completed.size > 1) completed.forEach { result ->
            TextButton(onClick = { cloud.ohiofiles.app.platform.NativeActions.share(context, result.fullUrl) }) { Text("Share ${result.response.finalFilename ?: "uploaded file"}") }
        }
        if (queue.isNotEmpty()) queue.forEach { selected ->
            ListItem(headlineContent = { Text(selected.overrides?.filename ?: selected.name, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(if (selected.overrides == null) "Shared upload options" else "Custom options for this file") }, trailingContent = { TextButton({ editingItem = selected }, enabled = uiState is UploadUiState.Idle && viewModel.editableOptions()) { Text("Options") } })
        }
        // Hero Header
        Card(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.CloudUpload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Ready when you are",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Private by default · Share on your terms",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Main File Drop / Selection Area
        when (val state = uiState) {
            UploadUiState.Idle -> {
                if (selectedFile == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.large
                            )
                            .clickable { filePicker.launch(arrayOf("*/*")) },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                            Text(
                                text = "Choose files",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Photos, videos, PDFs, zip archives, code, docs",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Selected file summary card
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedFile!!.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = selectedFile!!.formattedSize,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { viewModel.clearSelection() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        }
                    }

                    // Options toggle
                    OutlinedCard(
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showOptions = !showOptions }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Advanced Upload Options",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (showOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }

                    AnimatedVisibility(visible = showOptions) {
                        Card(
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Custom Slug
                                OutlinedTextField(
                                    value = customSlug,
                                    enabled = queue.size <= 1,
                                    onValueChange = { viewModel.setCustomSlug(it) },
                                    label = { Text("Custom URL Slug (optional)") },
                                    placeholder = { Text("e.g. project-report") },
                                    prefix = { Text("ohiofiles.cloud/") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Custom Filename
                                OutlinedTextField(
                                    value = customFilename,
                                    enabled = queue.size <= 1,
                                    onValueChange = { viewModel.setCustomFilename(it) },
                                    label = { Text("Custom Filename (optional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                FilledTonalButton(onClick = { folderMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Outlined.FolderOpen, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Upload to: ${selectedFolder?.name ?: "My files"}", modifier = Modifier.weight(1f))
                                    Icon(Icons.Outlined.ChevronRight, "Choose upload folder")
                                }

                                // Password Protection
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { viewModel.setPassword(it) },
                                    label = { Text("Password (optional)") },
                                    visualTransformation = if (passwordVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                    trailingIcon = { IconButton({ passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (passwordVisible) "Hide password" else "Show password") } },
                                    placeholder = { Text("Leave blank for open access") },
                                    singleLine = true,
                                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                TextButton({ viewModel.setPassword(generateUploadPassword()); passwordVisible = true }) { Text("Generate password") }
                                // Toggles
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Public File", fontWeight = FontWeight.SemiBold)
                                        Text(if (selectedFolder != null) "Visibility follows the selected folder" else "Visible in community explorer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = selectedFolder?.isPublic ?: isPublic, onCheckedChange = { viewModel.setIsPublic(it) }, enabled = selectedFolder == null)
                                }

                                HorizontalDivider()

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("1-Time Self Destruct", fontWeight = FontWeight.SemiBold)
                                        Text("File burns after 1st view/download", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = isOneTime, onCheckedChange = { viewModel.setIsOneTime(it) })
                                }
                            }
                        }
                    }

                    // Upload Action Button
                    Button(
                        onClick = { startNativeUpload() },
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Upload to OhioFiles", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            is UploadUiState.Duplicate -> {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Already in your files", style = MaterialTheme.typography.titleLarge)
                        Text("${state.filename} has the same content as ${state.existingName}.")
                        Text("Skip reuses the existing file and its current link, visibility and password. Upload again creates your selected link and options.", style = MaterialTheme.typography.bodyMedium)
                        Button({ viewModel.resolveDuplicate(true) }, Modifier.fillMaxWidth()) { Text("Skip and use existing file") }
                        OutlinedButton({ viewModel.resolveDuplicate(false) }, Modifier.fillMaxWidth()) { Text("Upload again") }
                    }
                }
            }
            is UploadUiState.Uploading -> {
                val sending = state.phase == UploadPhase.TRANSFERRING && state.progress < 1f
                val title = when (state.phase) {
                    UploadPhase.QUEUED -> "Upload queued"
                    UploadPhase.PREPARING -> "Preparing upload…"
                    UploadPhase.FINALIZING -> "Finishing upload…"
                    UploadPhase.RETRYING -> "Waiting to retry"
                    UploadPhase.TRANSFERRING -> if (sending) "Uploading…" else "Finishing upload…"
                }
                Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (sending) CircularProgressIndicator(progress = { state.progress.coerceIn(0f, .99f) }, modifier = Modifier.size(64.dp), strokeWidth = 6.dp)
                        else CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Text(state.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        if (preferences.detailedChunkProgress && state.totalChunks > 0) Text("${state.acknowledgedChunks} of ${state.totalChunks} chunks confirmed", style = MaterialTheme.typography.labelLarge)
                        if (sending) LinearProgressIndicator(progress = { state.progress.coerceIn(0f, .99f) }, modifier = Modifier.fillMaxWidth())
                        Text(when {
                            sending -> "${(state.progress * 100).toInt().coerceIn(0, 99)}% sent · continues in the background"
                            state.phase == UploadPhase.FINALIZING -> "Your bytes are sent. Waiting for the server to confirm the file."
                            state.phase == UploadPhase.RETRYING -> "The connection will be retried. Completed files are kept."
                            state.phase == UploadPhase.QUEUED -> "Ready to continue when Android and your connection are available."
                            else -> "Keeping a safe copy for this upload."
                        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                OutlinedButton(onClick = viewModel::cancelUpload, modifier = Modifier.fillMaxWidth()) { Text("Cancel upload") }
            }

            is UploadUiState.Success -> {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        Text(
                            text = "Upload Complete!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        Text(
                            text = state.fullUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("OhioFiles Link", state.fullUrl))
                                    Toast.makeText(context, "Link copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copy Link")
                            }

                            Button(
                                onClick = {
                                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, state.fullUrl)
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share OhioFiles link"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Share")
                            }
                        }

                        Button(
                            onClick = { showQrDialog = state.fullUrl },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.QrCode, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Show QR Code")
                        }

                        TextButton(
                            onClick = { viewModel.clearSelection() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Upload Another File")
                        }
                    }
                }
            }

            is UploadUiState.Error -> {
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "Upload needs attention",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Button(
                            onClick = { if (queue.isEmpty()) filePicker.launch(arrayOf("*/*")) else startNativeUpload() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text(if (queue.isEmpty()) "Choose files" else "Retry remaining uploads")
                        }
                        TextButton(onClick = viewModel::clearSelection, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onErrorContainer)) { Text("Discard this queue") }
                    }
                }
            }
        }
    }

    editingItem?.let { UploadItemOptionsDialog(it, viewModel) { editingItem = null } }
    if (folderMenuExpanded) UploadFolderPicker(viewModel, selectedFolder, { folderMenuExpanded = false }) { folder -> viewModel.setSelectedFolder(folder); folderMenuExpanded = false }
    showQrDialog?.let { url ->
        QrCodeDialog(
            title = "OhioFiles Link",
            content = url,
            subtitle = "Scan with your phone to view this file",
            onDismiss = { showQrDialog = null }
        )
    }
}

@Composable
internal fun UploadFolderPicker(viewModel: UploadViewModel, selected: FolderItem?, onDismiss: () -> Unit, onChoose: (FolderItem?) -> Unit) {
    var current by remember { mutableStateOf(selected) }
    var data by remember { mutableStateOf<FolderListResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(current?.id, refresh) {
        loading = true
        viewModel.listUploadFolders(current?.id).onSuccess { data = it; error = null }.onFailure { error = it.message ?: "Folders could not load" }
        loading = false
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose upload folder") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            TextButton({ current = null }) { Text("My files") }
            data?.path.orEmpty().forEach { crumb -> TextButton({ current = FolderItem(crumb.id, crumb.name ?: "Folder") }) { Text("› ${crumb.name ?: "Folder"}") } }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ refresh++ }) { Text("Retry") } }
            data?.folders.orEmpty().filter { !it.isShared || it.shareAccess == "EDIT" }.forEach { folder ->
                TextButton({ current = folder }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Folder, null); Text("  ${folder.name}", Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
    }, confirmButton = { TextButton({ onChoose(data?.currentFolder ?: current) }, enabled = !loading && error == null) { Text("Upload here") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}
