package cloud.ohiofiles.app.ui.screens.files

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@Composable
internal fun FolderPropertiesDialog(folder: FolderItem?, onDismiss: () -> Unit, onReveal: suspend () -> Result<String>, onSave: suspend (JsonObject) -> Result<Unit>) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(folder?.name.orEmpty()) }
    var slug by remember { mutableStateOf(folder?.slug.orEmpty()) }
    var public by remember { mutableStateOf(folder?.isPublic ?: false) }
    var type by remember { mutableStateOf(folder?.folderType ?: "NORMAL") }
    var access by remember { mutableStateOf(folder?.defaultAccess ?: "NONE") }
    var password by remember { mutableStateOf("") }
    var passwordAction by remember { mutableIntStateOf(if (folder == null) 1 else 0) }
    var reveal by remember { mutableStateOf(false) }
    var revealedPassword by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmCascade by remember { mutableStateOf(false) }
    fun save() { scope.launch {
        busy = true; error = null
        try {
            val payload = buildJsonObject {
                put("name", name.trim()); put("isPublic", public); put("folderType", type); put("defaultAccess", access)
                if (slug.isNotBlank() && slug != folder?.slug) put("slug", slug.trim())
                if (passwordAction == 2) put("password", JsonNull)
                else if (passwordAction == 1) put("password", password.ifBlank { null }?.let(::JsonPrimitive) ?: JsonNull)
            }
            onSave(payload).onSuccess { onDismiss() }.onFailure { error = it.message ?: "Folder couldn't be saved" }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Folder couldn't be saved" } finally { busy = false }
    } }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (folder == null) "Create folder" else "Folder settings") }, text = {
        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Folder name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(slug, { slug = it.take(64) }, label = { Text("Custom link (optional)") }, supportingText = { Text(if (folder == null) "2–64 letters, numbers, dashes or underscores" else "Changing this can stop the old link from working.") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("List publicly", Modifier.weight(1f)); Switch(public, { public = it }, enabled = !busy) }
            Text("Folder type", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { listOf("NORMAL" to "Files", "DOCS" to "Documents").forEachIndexed { i, (value, label) -> SegmentedButton(type == value, { type = value }, SegmentedButtonDefaults.itemShape(i, 2), enabled = !busy) { Text(label) } } }
            Text("Link permissions", style = MaterialTheme.typography.labelLarge)
            listOf("NONE" to "Owner only", "VIEW" to "Read", "EDIT" to "Edit").forEach { (value, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(access == value, { access = value }, enabled = !busy); Text(label) } }
            if (folder != null) {
                Text("Password", style = MaterialTheme.typography.labelLarge)
                listOf(0 to "Keep current", 1 to "Set a password", 2 to "Remove password").forEach { (value, label) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(passwordAction == value, { passwordAction = value }, enabled = !busy); Text(label) } }
                if (folder.passwordRequired || folder.hasPassword) TextButton({ scope.launch { busy = true; try { onReveal().onSuccess { revealedPassword = it }.onFailure { error = it.message } } finally { busy = false } } }, enabled = !busy) { Text("Show current password") }
                revealedPassword?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (passwordAction == 1) OutlinedTextField(password, { password = it.take(100) }, label = { Text("Password${if (folder == null) " (optional)" else ""}") }, enabled = !busy, singleLine = true, visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "Hide password" else "Show password") } }, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton({ if (folder != null && folder.isPublic != public) confirmCascade = true else save() }, enabled = name.isNotBlank() && !busy && (passwordAction != 1 || folder == null || password.isNotBlank())) { Text(if (busy) "Saving…" else "Save") } }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } })
    if (confirmCascade) AlertDialog(onDismissRequest = { confirmCascade = false }, title = { Text("Apply visibility to everything inside?") }, text = { Text("This changes this folder, every nested folder and every file inside to ${if (public) "public" else "private"}.") }, confirmButton = { TextButton({ confirmCascade = false; save() }) { Text("Apply to all contents") } }, dismissButton = { TextButton({ confirmCascade = false }) { Text("Cancel") } })
}
