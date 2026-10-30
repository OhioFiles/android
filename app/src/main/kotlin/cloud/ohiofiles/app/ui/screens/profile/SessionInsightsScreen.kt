package cloud.ohiofiles.app.ui.screens.profile

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

internal fun JsonObject.insightLong(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull
internal fun existingDeviceQuery(sessionKey: String, deviceId: String?): Map<String, String?> = mapOf("sessionKey" to sessionKey, "deviceId" to deviceId?.takeIf { it.length >= 8 })
internal fun revokeOtherDevicesBody(api: OhioApiClient, sessionKey: String, existingDeviceId: String): JsonObject {
    require(existingDeviceId.length >= 8) { "Open Chats to register this device first" }
    return api.body("sessionKey" to sessionKey, "deviceId" to existingDeviceId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionInsightsScreen(api: OhioApiClient, sessions: SessionRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    var payload by remember { mutableStateOf<JsonObject?>(null) }; var busy by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() { busy = true; error = null; try { val key = sessions.getSessionKey() ?: error("Your session is unavailable"); payload = api.request("session-insights", query = mapOf("sessionKey" to key)).getOrThrow().jsonObject } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false } }
    fun bytes(value: Long?) = value?.let { Formatter.formatShortFileSize(context, it) } ?: "Not available"
    fun amount(value: Long?) = value?.toString() ?: "Not available"
    BackHandler(onBack = onBack); LaunchedEffect(Unit) { load() }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Storage & activity") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } }, actions = { IconButton({ scope.launch { load() } }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Refresh activity") } }) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton({ scope.launch { load() } }) { Text("Retry") } } }
            payload?.let { data ->
                val storage = data["storage"] as? JsonObject ?: JsonObject(emptyMap())
                val speed = data["speed"] as? JsonObject ?: JsonObject(emptyMap())
                val types = (data["types"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                val recent = (speed["recent"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                item { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text("Stored files", style = MaterialTheme.typography.titleMedium); Text(bytes(storage.insightLong("totalBytes")), style = MaterialTheme.typography.displaySmall); Text("${amount(storage.insightLong("fileCount"))} files · ${amount(storage.insightLong("folderCount"))} folders") } } }
                item { Text("At a glance", style = MaterialTheme.typography.titleLarge) }
                item { InsightRows(listOf("Public files" to amount(storage.insightLong("publicFiles")), "Private files" to amount(storage.insightLong("privateFiles")), "Password protected" to amount(storage.insightLong("protectedFiles")), "One-time files" to amount(storage.insightLong("oneTimeFiles")), "Total downloads" to amount(storage.insightLong("totalDownloads")), "Average file" to bytes(storage.insightLong("averageFileBytes")), "Largest file" to bytes(storage.insightLong("largestFileBytes")), "Recoverable storage" to bytes(storage.insightLong("reclaimableBytes")), "Recoverable files" to amount(storage.insightLong("reclaimableFiles")), "Backups" to amount(storage.insightLong("backupCount")), "Backup storage" to bytes(storage.insightLong("backupBytes")), "Maximum size per upload" to bytes(storage.insightLong("maxFileSizeBytes")))) }
                item { Text("File types", style = MaterialTheme.typography.titleLarge); if (types.isEmpty()) Text("Upload a file to see its storage breakdown.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val typeTotal = types.sumOf { it.insightLong("bytes") ?: 0L }.coerceAtLeast(1L)
                items(types) { type -> val size = type.insightLong("bytes"); Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Row { Text((type["name"] as? JsonPrimitive)?.contentOrNull ?: "Other", Modifier.weight(1f)); Text(bytes(size)) }; if (size != null) LinearProgressIndicator(progress = { (size.toDouble() / typeTotal).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()); Text("${amount(type.insightLong("count"))} files", style = MaterialTheme.typography.labelMedium) } }
                item { Text("Largest files", style = MaterialTheme.typography.titleLarge) }
                val largest = (data["largestFiles"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
                if (largest.isEmpty()) item { Text("No files yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(largest) { file -> ListItem(headlineContent = { Text(file["filename"]?.jsonPrimitive?.contentOrNull ?: "File", maxLines = 2) }, supportingContent = { Text(file["mimeType"]?.jsonPrimitive?.contentOrNull ?: "Unknown type") }, trailingContent = { Text(bytes(file.insightLong("sizeBytes"))) }) }
                item { Text("Upload activity", style = MaterialTheme.typography.titleLarge); Text("Measured transfer speeds from completed uploads.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { InsightRows(listOf("Samples" to amount(speed.insightLong("samples")), "Average speed" to (speed.insightLong("averageBps")?.let { "${bytes(it)}/s" } ?: "No samples yet"), "Lowest speed" to (speed.insightLong("lowestBps")?.let { "${bytes(it)}/s" } ?: "No samples yet"), "Highest speed" to (speed.insightLong("highestBps")?.let { "${bytes(it)}/s" } ?: "No samples yet"))) }
                if (recent.size > 1) item { val points = recent.mapNotNull { it.insightLong("speedBps")?.toFloat() }.reversed(); val lineColor = MaterialTheme.colorScheme.primary; Canvas(Modifier.fillMaxWidth().height(100.dp).semantics { contentDescription = "Recent upload speeds. ${points.size} measured samples." }) { val high = points.maxOrNull()?.coerceAtLeast(1f) ?: 1f; points.zipWithNext().forEachIndexed { index, pair -> drawLine(lineColor, Offset(size.width * index / (points.size - 1), size.height * (1f - pair.first / high)), Offset(size.width * (index + 1) / (points.size - 1), size.height * (1f - pair.second / high)), strokeWidth = 3.dp.toPx()) } } }
                if (recent.isEmpty()) item { Text("Complete an upload to see your transfer history.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(recent) { transfer -> ListItem(headlineContent = { Text(transfer["filename"]?.jsonPrimitive?.contentOrNull ?: "Upload", maxLines = 2) }, supportingContent = { Text("${bytes(transfer.insightLong("sizeBytes"))} · ${transfer["createdAt"]?.jsonPrimitive?.contentOrNull?.take(10).orEmpty()}") }, trailingContent = { Text(transfer.insightLong("speedBps")?.let { "${bytes(it)}/s" } ?: "Not available") }) }
            }
        }
    }
}
@Composable private fun InsightRows(rows: List<Pair<String, String>>) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { rows.forEach { (name, value) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(name, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.labelLarge) } } } } }
