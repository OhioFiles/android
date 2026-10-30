package cloud.ohiofiles.app.ui.screens.upload

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.model.FolderItem
import cloud.ohiofiles.app.data.upload.*

@Composable
internal fun UploadItemOptionsDialog(file: SelectedFileInfo, viewModel: UploadViewModel, onDismiss: () -> Unit) {
    val initial = remember(file.uri) { viewModel.itemOptions(file) }
    var filename by remember { mutableStateOf(initial.filename) }
    var slug by remember { mutableStateOf(initial.slug.orEmpty()) }
    var public by remember { mutableStateOf(initial.options.isPublic) }
    var oneTime by remember { mutableStateOf(initial.options.isOneTime) }
    var password by remember { mutableStateOf(initial.options.password.orEmpty()) }
    var reveal by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf(initial.options.folderId?.let { FolderItem(it, "Selected folder") }) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Options for this file") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(filename, { filename = it.take(255) }, label = { Text("Filename") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(slug, { slug = it.take(32) }, label = { Text("Custom link (optional)") }, supportingText = { Text("Blank gives a six-character link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FilledTonalButton({ picking = true }, Modifier.fillMaxWidth()) { Text("Folder: ${folder?.name ?: "My files"}") }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(if (folder == null) "List publicly" else "Visibility follows folder", Modifier.weight(1f)); Switch(folder?.isPublic ?: public, { public = it }, enabled = folder == null) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("One-time access", Modifier.weight(1f)); Switch(oneTime, { oneTime = it }) }
            OutlinedTextField(password, { password = it.take(100) }, label = { Text("Password (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Hide password" else "Show password") } })
            TextButton({ password = generateUploadPassword(); reveal = true }) { Text("Generate password") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        runCatching { validateUploadFilename(filename); validateCustomUploadSlug(slug.trim().ifBlank { null }) }.onSuccess {
            viewModel.setItemOptions(file.uri, PerFileUploadOptions(filename.trim(), slug.trim().ifBlank { null }, UploadItemOptions(folder?.id, public, oneTime, password.ifBlank { null }))); onDismiss()
        }.onFailure { error = it.message }
    }) { Text("Apply to this file") } }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
    if (picking) UploadFolderPicker(viewModel, folder, { picking = false }) { folder = it; picking = false }
}
