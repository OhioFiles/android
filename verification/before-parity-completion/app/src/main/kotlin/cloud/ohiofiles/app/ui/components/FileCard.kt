package cloud.ohiofiles.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import cloud.ohiofiles.app.data.model.FileItem

@Composable
fun FileCard(
    file: FileItem,
    downloadUrl: String,
    onFileClick: (FileItem) -> Unit,
    onDownload: (FileItem) -> Unit,
    onShare: (FileItem) -> Unit,
    onQrCode: (FileItem) -> Unit,
    onSaveToggle: (FileItem) -> Unit,
    onDelete: (FileItem) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = true,
    onRename: (FileItem) -> Unit = {},
    onMove: (FileItem) -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onFileClick(file) }
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            if (!compact) {
                Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    if (file.isImage) AsyncImage(model = downloadUrl, contentDescription = file.displayName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(getFileIcon(file), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.height(10.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File Type Icon / Image Thumbnail
                Box(
                    modifier = Modifier
                        .size(if (compact) 46.dp else 0.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (file.isImage) {
                        AsyncImage(
                            model = downloadUrl,
                            contentDescription = file.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val icon = getFileIcon(file)
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(if (compact) 12.dp else 0.dp))

                // Name & Metadata
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = file.formattedSize,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (file.downloads > 0) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "${file.downloads}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }

                // Dropdown Menu Button
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open preview") },
                            leadingIcon = { Icon(Icons.Default.Visibility, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onFileClick(file)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Download") },
                            leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onDownload(file)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Share link") },
                            leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onShare(file)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("QR code") },
                            leadingIcon = { Icon(Icons.Default.QrCode, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onQrCode(file)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (file.isSaved) "Remove from saved" else "Save file") },
                            leadingIcon = {
                                Icon(
                                    if (file.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onSaveToggle(file)
                            }
                        )
                        if (file.canEdit) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename(file) })
                            DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menuExpanded = false; onMove(file) })
                        }
                        HorizontalDivider()
                        if (file.canEdit) DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete(file)
                            }
                        )
                    }
                }
            }

            // Badges row
            if (file.isOneTime || file.passwordRequired || file.isPublic) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (file.isOneTime) {
                        BadgePill(
                            text = if (file.oneTimeConsumed) "Consumed" else "One time",
                            backgroundColor = MaterialTheme.colorScheme.errorContainer,
                            textColor = MaterialTheme.colorScheme.onErrorContainer,
                            icon = Icons.Default.LocalFireDepartment
                        )
                    }
                    if (file.passwordRequired) {
                        BadgePill(
                            text = "Locked",
                            backgroundColor = MaterialTheme.colorScheme.tertiaryContainer,
                            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            icon = Icons.Default.Lock
                        )
                    }
                    if (file.isPublic) {
                        BadgePill(
                            text = "Public",
                            backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                            textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            icon = Icons.Default.Public
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BadgePill(
    text: String,
    backgroundColor: Color,
    textColor: Color,
    icon: ImageVector? = null
) {
    Surface(
        shape = CircleShape,
        color = backgroundColor,
        modifier = Modifier.height(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(12.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = textColor,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun getFileIcon(file: FileItem): ImageVector {
    return when {
        file.isImage -> Icons.Default.Image
        file.isVideo -> Icons.Default.VideoFile
        file.isAudio -> Icons.Default.AudioFile
        file.isPdf -> Icons.Default.PictureAsPdf
        file.isTextOrCode -> Icons.Default.Code
        file.displayName.endsWith(".zip", true) || file.displayName.endsWith(".tar", true) || file.displayName.endsWith(".gz", true) -> Icons.Default.FolderZip
        else -> Icons.Default.InsertDriveFile
    }
}
