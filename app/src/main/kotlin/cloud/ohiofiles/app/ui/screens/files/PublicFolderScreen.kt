package cloud.ohiofiles.app.ui.screens.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cloud.ohiofiles.app.platform.NativeActions
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicFolderScreen(initialToken: String, api: OhioApiClient, sessions: SessionRepository, onOpenFile: (FileItem) -> Unit, onBack: () -> Unit) {
    var token by remember { mutableStateOf(initialToken) }; var access by remember { mutableStateOf<String?>(null) }; var folder by remember { mutableStateOf<JsonObject?>(null) }
    var files by remember { mutableStateOf(emptyList<FileItem>()) }; var subfolders by remember { mutableStateOf(emptyList<JsonObject>()) }; var error by remember { mutableStateOf<String?>(null) }; var password by remember { mutableStateOf("") }; var locked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    suspend fun load() {
        error = null; val query = mapOf("sessionKey" to sessions.getSessionKey(), "folderAccessToken" to access)
        val result = api.request("public-folders/$token", query = query)
        if (result.isFailure) { locked = (result.exceptionOrNull() as? cloud.ohiofiles.app.data.api.ApiException)?.status == 423; error = result.exceptionOrNull()?.message; return }
        locked = false; val data = result.getOrThrow().jsonObject; folder = data["folder"]?.jsonObject ?: data
        val id = folder?.get("id")?.jsonPrimitive?.content ?: token
        api.request("files", query = (query - "sessionKey") + mapOf("folderId" to id, "public" to "true", "limit" to "100")).onSuccess { files = api.json.decodeFromJsonElement<FileListResponse>(it).files }.onFailure { error = it.message }
        val embedded = (data["files"] as? JsonArray ?: folder?.get("files") as? JsonArray)?.let { api.json.decodeFromJsonElement<List<FileItem>>(it) }.orEmpty()
        val owned = folder?.get("isOwner")?.jsonPrimitive?.booleanOrNull == true
        val editable = folder?.get("canEdit")?.jsonPrimitive?.booleanOrNull == true
        files = (files + embedded).distinctBy { it.id }.map { file ->
            if (owned || editable) file.copy(isOwner = owned, access = if (owned) "OWNER" else "EDIT", shareAccess = if (editable && !owned) "EDIT" else file.shareAccess) else file
        }
        api.rememberFolderAccess(files.map { it.id }, access)
        api.request("public-folders/$token/subfolders", query = query).onSuccess { subfolders = (it.jsonObject["subfolders"] ?: it.jsonObject["folders"])?.jsonArray?.map { item -> item.jsonObject }.orEmpty() }
    }
    LaunchedEffect(token) { load() }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text(folder?.get("name")?.jsonPrimitive?.content ?: "Shared folder") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } }, actions = {
        if (!locked && folder != null) IconButton({ scope.launch {
            NativeActions.download(context, api.url("public-folders/$token/download-zip", mapOf("sessionKey" to sessions.getSessionKey(), "folderAccessToken" to access)), "${folder?.get("name")?.jsonPrimitive?.content ?: "Folder"}.zip", "application/zip")
        } }) { Icon(Icons.Outlined.Download, "Download folder ZIP") }
    }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (locked) item { OutlinedTextField(password, { password = it }, label = { Text("Folder password") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()); Button({ scope.launch { api.request("public-folders/$token/access", "POST", api.body("password" to password)).onSuccess { access = it.jsonObject["token"]?.jsonPrimitive?.content; load() }.onFailure { error = it.message } } }) { Text("Unlock folder") } }
            subfolders.forEach { child -> item { FilledTonalButton({ token = child["slug"]?.jsonPrimitive?.contentOrNull ?: child["id"]!!.jsonPrimitive.content }, Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Folder, null); Text("  ${child["name"]?.jsonPrimitive?.content ?: "Folder"}") } } }
            files.forEach { file -> item { ListItem(headlineContent = { Text(file.displayName) }, supportingContent = { Text(file.formattedSize) }, leadingContent = { Icon(Icons.Outlined.InsertDriveFile, null) }, trailingContent = { IconButton({ onOpenFile(file) }) { Icon(Icons.Outlined.OpenInNew, "Open ${file.displayName}") } }) } }
        }
    }
}
