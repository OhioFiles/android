package cloud.ohiofiles.app.ui.screens.profile

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.ohiofiles.app.ui.components.QrCodeDialog
import coil.compose.AsyncImage
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, modifier: Modifier = Modifier,
    onOpenProfile: ((String) -> Unit)? = null, onSettings: (() -> Unit)? = null,
    onDetailOpenChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tools by rememberSaveable { mutableIntStateOf(-1) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.loadProfile() }
    LaunchedEffect(notice, recovery) { if (!recovery) notice?.let { snackbar.showSnackbar(it); viewModel.clearNotice() } }
    DisposableEffect(tools >= 0) { onDetailOpenChange(tools >= 0); onDispose { onDetailOpenChange(false) } }
    if (tools == 3) { SessionInsightsScreen(viewModel.api, viewModel.sessionRepo) { tools = -1 }; return }
    if (tools >= 0) { AccountToolsScreen(viewModel.api, viewModel.sessionRepo, initialSection = tools, onChangeNickname = { tools = -1; rename = true }) { tools = -1; viewModel.loadProfile() }; return }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) }, modifier = modifier.fillMaxSize()) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (val current = state) {
                ProfileUiState.Loading -> item { Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Text("Opening your profile…", Modifier.padding(top = 16.dp)) } }
                is ProfileUiState.Error -> item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Outlined.PersonOutline, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Your profile is still here", style = MaterialTheme.typography.headlineSmall)
                            Text(current.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(viewModel::loadProfile) { Text("Try again") }; TextButton({ importing = true }) { Text("Import session") } }
                        }
                    }
                }
                is ProfileUiState.Success -> {
                    item {
                        Card(shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomEnd = 40.dp, bottomStart = 12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .09f)), contentAlignment = Alignment.Center) {
                                        if (current.avatarUrl != null) AsyncImage(current.avatarUrl, "Your profile photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        else Text(current.nickname.trim().take(1).uppercase(Locale.getDefault()).ifEmpty { "O" }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Your profile", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                                        Text(current.nickname, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        current.session?.createdAt?.let(::joinedDate)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .8f)) }
                                    }
                                }
                                val status = listOfNotNull(current.mood, current.status).filter(String::isNotBlank).joinToString(" · ")
                                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                val fontScale = LocalDensity.current.fontScale
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    val stacked = maxWidth < 280.dp || fontScale > 1.2f
                                    val edit: @Composable (Modifier) -> Unit = { actionModifier ->
                                        Button({ tools = 0 }, actionModifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Edit profile", maxLines = 1) }
                                    }
                                    val view: @Composable (Modifier) -> Unit = { actionModifier ->
                                        FilledTonalButton({ current.profileSlug?.let { onOpenProfile?.invoke(it) } }, actionModifier.heightIn(min = 48.dp), enabled = current.profileSlug != null && onOpenProfile != null, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("View profile", maxLines = 1) }
                                    }
                                    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { edit(Modifier.fillMaxWidth()); view(Modifier.fillMaxWidth()) }
                                    else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { edit(Modifier.weight(1f)); view(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            UsageTile(current.fileCount?.toString() ?: "—", "Files", Icons.Outlined.Description, Modifier.weight(1f))
                            UsageTile(current.folderCount?.toString() ?: "—", "Folders", Icons.Outlined.FolderOpen, Modifier.weight(1f))
                            UsageTile(current.totalBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "—", "Stored", Icons.Outlined.CloudQueue, Modifier.weight(1f))
                        }
                        if (current.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    if (current.message != null) item {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(current.message, style = MaterialTheme.typography.bodyMedium)
                                Row { TextButton(viewModel::loadProfile) { Text("Refresh") }; TextButton({ recovery = true }) { Text("Session & recovery") } }
                            }
                        }
                    }
                    item {
                        Text("Manage", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ProfileAction("Profile & status", "Avatar, about you and what you're up to", Icons.Outlined.Badge, { tools = 0 })
                                ProfileAction("Storage & activity", "File types, storage and upload history", Icons.Outlined.Insights, { tools = 3 })
                                ProfileAction("Connected devices", "Manage your linked devices", Icons.Outlined.Devices, { tools = 1 })
                                ProfileAction("Backups", when (val count = current.backupCount) { null -> "Keep a restorable copy of your session"; 0L -> "Create your first session backup"; 1L -> "1 saved backup"; else -> "$count saved backups" }, Icons.Outlined.Restore, { tools = 2 })
                                if (onSettings != null) ProfileAction("Appearance", "Colors, theme and app settings", Icons.Outlined.Palette, onSettings)
                            }
                        }
                    }
                    item {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                ProfileAction("Connect another device", "Move your session with a QR code", Icons.Outlined.QrCode2, { qr = current.sessionKey })
                                ProfileAction("Session & recovery", "Session key, import and renewal", Icons.Outlined.Key, { recovery = true })
                            }
                        }
                    }
                }
            }
        }
    }
    val current = state as? ProfileUiState.Success
    if (recovery && current != null) ModalBottomSheet(onDismissRequest = { recovery = false }) {
        SessionRecovery(current, busy, notice, onCopy = {
            val clip = ClipData.newPlainText("OhioFiles session key", current.sessionKey)
            if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }, onConnect = { qr = current.sessionKey }, onImport = { importing = true }, onRenew = viewModel::renewSession, onRevoke = { revoking = true })
    }
    if (rename) {
        var name by remember { mutableStateOf(current?.nickname.orEmpty().takeUnless { it == "Your profile" }.orEmpty()) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) rename = false }, title = { Text("Choose your nickname") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your nickname can be changed once.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it; error = null }, label = { Text("Nickname") }, singleLine = true, enabled = !busy, isError = error != null, supportingText = error?.let { { Text(it) } })
            }
        }, confirmButton = { TextButton({ viewModel.updateNickname(name) { success, message -> if (success) rename = false else error = message } }, enabled = name.isNotBlank() && !busy) { Text(if (busy) "Saving…" else "Save nickname") } }, dismissButton = { TextButton({ rename = false }, enabled = !busy) { Text("Cancel") } })
    }
    if (importing) {
        var key by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) importing = false }, title = { Text("Import a session") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Paste the key from your other device. Your current session is kept until the key is verified.")
                OutlinedTextField(key, { key = it; error = null }, label = { Text("Session key") }, singleLine = true, enabled = !busy, isError = error != null, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), supportingText = error?.let { { Text(it) } })
            }
        }, confirmButton = { TextButton({ viewModel.importSessionKey(key) { success, message -> if (success) { importing = false; recovery = false } else error = message } }, enabled = key.isNotBlank() && !busy) { Text(if (busy) "Checking…" else "Import session") } }, dismissButton = { TextButton({ importing = false }, enabled = !busy) { Text("Cancel") } })
    }
    if (revoking) {
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) revoking = false }, title = { Text("Revoke this session?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This permanently revokes this session and deletes its hosted files. This cannot be undone. A new empty session will be created.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ viewModel.revokeSession { success, message -> if (success) { revoking = false; recovery = false } else error = message } }, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(if (busy) "Revoking…" else "Revoke session") } }, dismissButton = { TextButton({ revoking = false }, enabled = !busy) { Text("Cancel") } })
    }
    qr?.let { QrCodeDialog(title = "Connect another device", content = it, subtitle = "Scanning this code gives the other device access to your session.", onDismiss = { qr = null }) }
}

@Composable
private fun UsageTile(value: String, label: String, icon: ImageVector, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun ProfileAction(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) }, supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Icon(icon, null, Modifier.padding(12.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick))
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionRecovery(state: ProfileUiState.Success, busy: Boolean, notice: String?, onCopy: () -> Unit, onConnect: () -> Unit, onImport: () -> Unit, onRenew: () -> Unit, onRevoke: () -> Unit) {
    var visible by remember { mutableStateOf(false) }; var advanced by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Session & recovery", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Keep this key to access the same files on another device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(if (visible) state.sessionKey else "•••• •••• •••• ••••", {}, label = { Text("Session key") }, readOnly = true, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth(), trailingIcon = { IconButton({ visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide session key" else "Reveal session key") } })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onCopy) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy key") }
            OutlinedButton(onConnect) { Icon(Icons.Outlined.QrCode2, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Connect device") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        ProfileAction("Import a session", "Switch using a verified key", Icons.Outlined.SwapHoriz, onImport)
        ProfileAction("Renew session", "Extend your session's active lifetime", Icons.Outlined.Autorenew, onRenew)
        TextButton({ advanced = !advanced }) { Text(if (advanced) "Hide advanced actions" else "Advanced actions"); Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }
        if (advanced) TextButton(onRevoke, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Icon(Icons.Outlined.DeleteForever, null); Spacer(Modifier.width(8.dp)); Text("Revoke session") }
    }
}
private fun joinedDate(value: String): String? = runCatching {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { isLenient = false }.parse(value.take(10)) ?: return null
    "Joined ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(date)}"
}.getOrNull()
