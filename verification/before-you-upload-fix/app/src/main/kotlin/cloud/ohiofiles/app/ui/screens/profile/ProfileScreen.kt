package cloud.ohiofiles.app.ui.screens.profile

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.ui.components.QrCodeDialog

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var tools by remember { mutableStateOf(false) }
    if (tools) { AccountToolsScreen(viewModel.api, viewModel.sessionRepo) { tools = false }; return }
    LaunchedEffect(notice) { notice?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showEditNicknameDialog by remember { mutableStateOf(false) }
    var showImportSessionDialog by remember { mutableStateOf(false) }
    var showRevokeConfirmDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf<String?>(null) }
    var showKeyVisible by remember { mutableStateOf(false) }

    when (val state = uiState) {
        ProfileUiState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is ProfileUiState.Error -> {
            Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
            }
        }

        is ProfileUiState.Success -> {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Make yourself at home.", style = MaterialTheme.typography.displaySmall)
                FilledTonalButton(onClick = { tools = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.ManageAccounts, null); Spacer(Modifier.width(8.dp)); Text("Profile, devices & backups") }
                // Identity Card
                Card(
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Face,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(40.dp)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.nickname,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            IconButton(
                                onClick = { showEditNicknameDialog = true },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Edit nickname",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Text(
                            text = "${state.session?.fileCount ?: 0} hosted files • Login-Free Session",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }

                // Session Key Card
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Your Session Key",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showKeyVisible = !showKeyVisible }) {
                                Icon(
                                    if (showKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Toggle key visibility"
                                )
                            }
                        }

                        val displayKey = if (showKeyVisible) state.sessionKey else "••••••••••••••••••••••••••••••••"
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = displayKey,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("OhioFiles Session Key", state.sessionKey))
                                    Toast.makeText(context, "Session key copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Copy Key")
                            }

                            Button(
                                onClick = { showQrDialog = state.sessionKey },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pair Device")
                            }
                        }
                    }
                }

                // Session Actions
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        ListItem(
                            headlineContent = { Text("Switch / Import Session Key") },
                            supportingContent = { Text("Load existing files from your PC or another browser") },
                            leadingContent = { Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable { showImportSessionDialog = true }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text("Renew Session Expiry") },
                            supportingContent = { Text("Extends active file hosting lifetime") },
                            leadingContent = { Icon(Icons.Default.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.clickable {
                                viewModel.renewSession()

                            }
                        )

                        HorizontalDivider()

                        ListItem(
                            headlineContent = { Text("Revoke & Destroy Session", color = MaterialTheme.colorScheme.error) },
                            supportingContent = { Text("Permanently revoke access to this session") },
                            leadingContent = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            modifier = Modifier.clickable { showRevokeConfirmDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Edit Nickname Dialog
    if (showEditNicknameDialog) {
        var newNick by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showEditNicknameDialog = false },
            title = { Text("Choose your nickname") },
            text = {
                OutlinedTextField(
                    value = newNick,
                    onValueChange = { newNick = it },
                    label = { Text("Nickname · can be changed once") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newNick.isNotBlank()) {
                        viewModel.updateNickname(newNick)
                        showEditNicknameDialog = false
                    }
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditNicknameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Import Session Dialog
    if (showImportSessionDialog) {
        var importKeyInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportSessionDialog = false },
            title = { Text("Import Session Key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste your session key from OhioFiles web or OhioCLI to access your files on this phone.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = importKeyInput,
                        onValueChange = { importKeyInput = it },
                        label = { Text("Session Key") },
                        placeholder = { Text("ohio_... or sec_...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (importKeyInput.isNotBlank()) {
                        viewModel.importSessionKey(importKeyInput) { success, err ->
                            if (success) {
                                Toast.makeText(context, "Session imported!", Toast.LENGTH_SHORT).show()
                                showImportSessionDialog = false
                            } else {
                                Toast.makeText(context, err ?: "Failed to import", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportSessionDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Revoke Confirm Dialog
    if (showRevokeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Revoke Current Session?") },
            text = {
                Text("This action cannot be undone. All hosted files associated with this session will be permanently deleted.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.revokeSession {
                            Toast.makeText(context, "Session revoked and new session initialized", Toast.LENGTH_SHORT).show()
                            showRevokeConfirmDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // QR Code Dialog for Session Pairing
    showQrDialog?.let { key ->
        QrCodeDialog(
            title = "Pair OhioFiles Device",
            content = key,
            subtitle = "Scan this QR code from OhioFiles web or another device to transfer your session",
            onDismiss = { showQrDialog = null }
        )
    }
}
