package cloud.ohiofiles.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.model.FolderItem

@Composable
fun FolderCard(folder: FolderItem, onClick: (FolderItem) -> Unit, onDelete: (FolderItem) -> Unit, modifier: Modifier = Modifier,
    onRename: (FolderItem) -> Unit = {}, onMove: (FolderItem) -> Unit = {}, onShare: (FolderItem) -> Unit = {}, onQr: (FolderItem) -> Unit = {}, compact: Boolean = true, onDownload: ((FolderItem) -> Unit)? = null, onSettings: ((FolderItem) -> Unit)? = null,
    selectionMode: Boolean = false, selected: Boolean = false, onSelectionToggle: ((FolderItem) -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    val editable = !folder.isShared || folder.shareAccess == "EDIT"
    val info = buildList {
        if (folder.childCount > 0) add(if (folder.childCount == 1) "1 folder" else "${folder.childCount} folders")
        if (folder.fileCount > 0) add(if (folder.fileCount == 1) "1 file" else "${folder.fileCount} files")
        if (isEmpty()) add("Empty folder")
    }.joinToString(" · ")
    val menuContent: @Composable () -> Unit = {
        Box {
            IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Actions for ${folder.name}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Open folder") }, { menu = false; onClick(folder) })
                if (editable) {
                    DropdownMenuItem({ Text("Rename") }, { menu = false; onRename(folder) })
                    DropdownMenuItem({ Text("Move to folder") }, { menu = false; onMove(folder) })
                }
                if (!folder.isShared && onSettings != null) DropdownMenuItem({ Text("Folder settings") }, { menu = false; onSettings(folder) })
                if (onDownload != null) DropdownMenuItem({ Text("Download ZIP") }, { menu = false; onDownload(folder) })
                DropdownMenuItem({ Text("Share link") }, { menu = false; onShare(folder) })
                DropdownMenuItem({ Text("QR code") }, { menu = false; onQr(folder) })
                if (editable) DropdownMenuItem({ Text("Delete folder", color = MaterialTheme.colorScheme.error) }, { menu = false; onDelete(folder) })
            }
        }
    }
    Card(onClick = { if (selectionMode) onSelectionToggle?.invoke(folder) else onClick(folder) }, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 10.dp, topEnd = 26.dp, bottomEnd = 26.dp, bottomStart = 26.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        if (compact) Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selectionMode) Checkbox(selected, { onSelectionToggle?.invoke(folder) })
            Icon(Icons.Outlined.Folder, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            if (folder.hasPassword || folder.passwordRequired) Icon(Icons.Outlined.Lock, "Password protected", Modifier.size(16.dp))
            menuContent()
        } else Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) Checkbox(selected, { onSelectionToggle?.invoke(folder) })
                Icon(Icons.Outlined.Folder, null, Modifier.weight(1f).wrapContentWidth(Alignment.Start).size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                if (folder.hasPassword || folder.passwordRequired) Icon(Icons.Outlined.Lock, "Password protected", Modifier.size(16.dp))
                menuContent()
            }
            Text(folder.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
