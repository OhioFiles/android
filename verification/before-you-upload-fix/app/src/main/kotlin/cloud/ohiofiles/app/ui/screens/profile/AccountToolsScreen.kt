package cloud.ohiofiles.app.ui.screens.profile

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.platform.NativeActions
import coil.compose.AsyncImage
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val archiveClient = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).retryOnConnectionFailure(false).build()
private suspend fun importArchive(context: Context, uri: Uri, api: OhioApiClient, key: String): Result<JsonElement> = withContext(Dispatchers.IO) {
    try {
        val streamBody = object : RequestBody() {
            override fun contentType() = "application/zip".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { (context.contentResolver.openInputStream(uri) ?: error("Could not open archive")).source().use { sink.writeAll(it) } }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("sessionKey", key).addFormDataPart("file", "OhioFiles-backup.zip", streamBody).build()
        val call = archiveClient.newCall(Request.Builder().url(api.url("session-backups/import-archive")).post(body).build())
        val response = suspendCancellableCoroutine<Response> { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) { continuation.resume(response) { _, value, _ -> value.close() } }
            })
        }
        response.use {
            val value = api.json.parseToJsonElement(it.body?.string() ?: "{}")
            if (!it.isSuccessful) error(value.jsonObject["error"]?.jsonPrimitive?.contentOrNull ?: "Archive import failed (${it.code})")
            Result.success(value)
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
}

private suspend fun avatarCopy(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
    val suffix = mapOf("image/png" to ".png", "image/jpeg" to ".jpg", "image/webp" to ".webp", "image/avif" to ".avif", "image/gif" to ".gif")[context.contentResolver.getType(uri)] ?: error("Choose a PNG, JPEG, WebP, AVIF or GIF image")
    val file = File.createTempFile("avatar-", suffix, context.cacheDir)
    try {
        (context.contentResolver.openInputStream(uri) ?: error("Could not open image")).use { input -> file.outputStream().use { output ->
            val buffer = ByteArray(8192); var total = 0L
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count; require(total <= 4 * 1024 * 1024) { "Avatar images must be 4 MB or smaller" }; output.write(buffer, 0, count) }
            require(total > 0) { "The selected image is empty" }
        } }
        file
    } catch (e: Exception) { file.delete(); throw e }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountToolsScreen(api: OhioApiClient, sessions: SessionRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var section by rememberSaveable { mutableIntStateOf(0) }
    var devices by remember { mutableStateOf(emptyList<JsonObject>()) }; var backups by remember { mutableStateOf(emptyList<JsonObject>()) }
    var readme by rememberSaveable { mutableStateOf("") }; var status by rememberSaveable { mutableStateOf("") }; var mood by rememberSaveable { mutableStateOf("") }; var avatarUrl by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }; var failed by remember { mutableStateOf(false) }; var loaded by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<String, suspend (String) -> Result<JsonElement>>?>(null) }
    var backupName by rememberSaveable { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    fun list(value: JsonElement, key: String): List<JsonObject> = (if (value is JsonArray) value else value.jsonObject[key]?.jsonArray)?.map { it.jsonObject }.orEmpty()
    suspend fun load() {
        val key = sessions.getSessionKey() ?: error("Your session is unavailable")
        val target = section
        val result = api.request(when (target) { 0 -> "profiles/me"; 1 -> "session-devices"; else -> "session-backups" }, query = mapOf("sessionKey" to key))
        result.onSuccess {
            if (section != target) return@onSuccess
            when (target) {
                0 -> { val profile = it.jsonObject["profile"]?.jsonObject ?: it.jsonObject; readme = profile["readme"]?.jsonPrimitive?.contentOrNull.orEmpty(); mood = profile["mood"]?.jsonPrimitive?.contentOrNull.orEmpty(); status = profile["statusText"]?.jsonPrimitive?.contentOrNull.orEmpty(); avatarUrl = profile["avatarUrl"]?.jsonPrimitive?.contentOrNull?.let { url -> if (url.startsWith("/")) api.getBaseUrl().trimEnd('/') + url else url } }
                1 -> devices = list(it, "devices")
                2 -> backups = list(it, "backups")
            }
            loaded = true
        }.getOrThrow()
    }
    fun perform(block: suspend (String) -> Result<JsonElement>) {
        if (busy) return
        scope.launch {
            busy = true; message = null
            try { val key = sessions.getSessionKey() ?: error("Your session is unavailable"); block(key).getOrThrow(); load(); failed = false; message = "Saved" }
            catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true; message = e.message ?: "Action failed" }
            finally { busy = false }
        }
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) perform { key ->
        var temporary: File? = null
        try {
            temporary = avatarCopy(context, uri)
            val upload = api.uploadFile(context, Uri.fromFile(temporary), key).getOrThrow()
            val id = upload.finalId ?: error("Upload did not return a file ID")
            val result = api.request("profiles/me/avatar", "PUT", api.body("sessionKey" to key, "fileId" to id))
            if (result.isFailure) upload.finalSlug?.let { api.deleteFile(it, key) }
            result
        } finally { withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() } }
    } }
    val archivePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirm = "Import this archive? It adds its files and folders to your current session." to { key -> importArchive(context, uri, api, key) } }
    LaunchedEffect(section) { busy = true; loaded = false; message = null; try { load() } catch (e: CancellationException) { throw e } catch (e: Exception) { failed = true; message = e.message } finally { busy = false } }
    BackHandler(enabled = !busy, onBack = onBack)
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Your account") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { listOf("Profile", "Devices", "Backups").forEachIndexed { index, label -> SegmentedButton(section == index, { section = index }, SegmentedButtonDefaults.itemShape(index, 3), enabled = !busy) { Text(label) } } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                message?.let { item { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary); if (failed && !loaded) TextButton({ scope.launch { busy = true; try { load(); message = null } catch (e: CancellationException) { throw e } catch (e: Exception) { message = e.message } finally { busy = false } } }) { Text("Retry") } } }
                if (section == 0) {
                    item { Text("Tell your story.", style = MaterialTheme.typography.headlineMedium); Text("Your public profile supports Markdown.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { avatarUrl?.let { AsyncImage(it, "Your avatar", Modifier.size(88.dp)) }; Row { FilledTonalButton({ avatarPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/avif", "image/gif")) }, enabled = !busy) { Icon(Icons.Outlined.AddPhotoAlternate, null); Text("Choose avatar") }; TextButton({ perform { api.request("profiles/me/avatar", "DELETE", query = mapOf("sessionKey" to it)) } }, enabled = !busy) { Text("Remove") } }; Text("PNG, JPEG, WebP, AVIF or GIF · up to 4 MB", style = MaterialTheme.typography.bodySmall) }
                    item { OutlinedTextField(readme, { readme = it.take(4000) }, label = { Text("About you · Markdown") }, supportingText = { Text("${readme.length}/4000") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth()) }
                    item { Button({ perform { api.request("profiles/me", "PUT", api.body("sessionKey" to it, "readme" to readme)) } }, enabled = !busy && loaded) { Text("Save profile") } }
                    item { OutlinedTextField(mood, { mood = it.take(80) }, label = { Text("Mood") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(status, { status = it.take(160) }, label = { Text("Status") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    item { FilledTonalButton({ perform { api.request("public-sessions/$it/customization", "POST", api.body("customization" to api.body("mood" to mood, "statusText" to status))) } }, enabled = !busy && loaded) { Text("Update status") } }
                }
                if (section == 1) {
                    item { Text("Connected devices", style = MaterialTheme.typography.headlineSmall); Text("Manage the devices linked to your session.") }
                    if (loaded && devices.isEmpty()) item { Text("No connected devices found.") }
                    devices.forEach { device -> item(key = device["id"]?.jsonPrimitive?.content) {
                        val id = device["id"]?.jsonPrimitive?.content.orEmpty(); var name by rememberSaveable(id) { mutableStateOf(device["name"]?.jsonPrimitive?.content.orEmpty()) }
                        Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            OutlinedTextField(name, { name = it.take(80) }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Text(device["platform"]?.jsonPrimitive?.content.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Row { TextButton({ perform { api.request("session-devices/$id", "PATCH", api.body("sessionKey" to it, "name" to name.trim())) } }, enabled = name.isNotBlank() && !busy) { Text("Rename") }; TextButton({ confirm = "Remove this device?" to { key -> api.request("session-devices/$id", "DELETE", query = mapOf("sessionKey" to key)) } }, enabled = !busy) { Text("Remove") } }
                        } }
                    } }
                }
                if (section == 2) {
                    item { Text("A little peace of mind.", style = MaterialTheme.typography.headlineMedium); Text("Create a restorable snapshot of your session.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { OutlinedTextField(backupName, { backupName = it.take(80) }, label = { Text("Backup name") }, singleLine = true, modifier = Modifier.fillMaxWidth()); Button({ perform { api.request("session-backups", "POST", api.body("sessionKey" to it, "name" to backupName.trim())) } }, enabled = backupName.isNotBlank() && !busy) { Text("Create backup") }; OutlinedButton({ archivePicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, enabled = !busy) { Icon(Icons.Outlined.UploadFile, null); Text("Import ZIP archive") } }
                    if (loaded && backups.isEmpty()) item { Text("Your backups will appear here.") }
                    backups.forEach { backup -> item(key = backup["id"]?.jsonPrimitive?.content) {
                        val id = backup["id"]?.jsonPrimitive?.content.orEmpty()
                        Card { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(backup["name"]?.jsonPrimitive?.content ?: "Session backup", style = MaterialTheme.typography.titleMedium)
                            Text(backup["createdAt"]?.jsonPrimitive?.content?.take(10).orEmpty(), style = MaterialTheme.typography.bodySmall)
                            Row { TextButton({ scope.launch { val key = sessions.getSessionKey(); if (key != null) NativeActions.download(context, api.url("session-backups/$id/download", mapOf("sessionKey" to key)), "OhioFiles-$id.zip", "application/zip") else { failed = true; message = "Your session is unavailable" } } }, enabled = !busy) { Text("Download ZIP") }
                                TextButton({ confirm = "Restore this backup? This changes the current session data." to { key -> api.request("session-backups/$id/restore", "POST", api.body("sessionKey" to key)) } }, enabled = !busy) { Text("Restore") }
                                TextButton({ confirm = "Delete this backup?" to { key -> api.request("session-backups/$id", "DELETE", query = mapOf("sessionKey" to key)) } }, enabled = !busy) { Text("Delete") } }
                        } }
                    } }
                }
            }
        }
    }
    confirm?.let { pending -> AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Confirm action") }, text = { Text(pending.first) }, confirmButton = { TextButton({ confirm = null; perform(pending.second) }) { Text("Continue") } }, dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } }) }
}
