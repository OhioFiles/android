package cloud.ohiofiles.app.ui.document

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.ui.preview.PreviewIO
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

private fun JsonObject.string(key: String, fallback: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
private fun JsonObject.bool(key: String, default: Boolean = false) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: default

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NativeDocsScreen(folderToken: String, api: OhioApiClient, sessionRepo: SessionRepository, onOpenFile: (FileItem) -> Unit, onClose: () -> Unit, folderAccessToken: String? = null) {
    val scope = rememberCoroutineScope(); var pages by remember { mutableStateOf(emptyList<FileItem>()) }; var folder by remember { mutableStateOf<JsonObject?>(null) }
    var config by remember { mutableStateOf(buildJsonObject {}) }; var selected by rememberSaveable { mutableIntStateOf(0) }; var content by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }; var pageMenu by remember { mutableStateOf(false) }; var toc by remember { mutableStateOf(false) }; var customize by remember { mutableStateOf(false) }
    var contentMatches by remember { mutableStateOf(emptySet<String>()) }; var searchProgress by remember { mutableStateOf("") }; var searchFailures by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }; var loading by remember { mutableStateOf(false) }; var reload by remember { mutableIntStateOf(0) }; var sessionKey by remember { mutableStateOf<String?>(null) }
    val hierarchy = config["pageHierarchy"] as? JsonObject
    val ordered = remember(pages, hierarchy) {
        fun entry(file: FileItem) = hierarchy?.get(file.id) as? JsonObject ?: hierarchy?.get(file.filename) as? JsonObject
        val aliases = pages.flatMap { listOf(it.id to it.id, it.filename to it.id) }.toMap()
        fun parent(file: FileItem) = aliases[entry(file)?.string("parentId")]
        val sorted = pages.sortedWith(compareBy<FileItem> { entry(it)?.get("order")?.jsonPrimitive?.intOrNull ?: if (it.displayName.startsWith("readme", true) || it.displayName.startsWith("index", true)) -1 else Int.MAX_VALUE }.thenBy { it.displayName })
        val visited = mutableSetOf<String>(); val result = mutableListOf<FileItem>()
        fun append(file: FileItem, depth: Int) { if (depth > 20 || !visited.add(file.id)) return; result += file; sorted.filter { parent(it) == file.id }.forEach { append(it, depth + 1) } }
        sorted.filter { parent(it) == null }.forEach { append(it, 0) }; sorted.forEach { append(it, 0) }; result
    }
    LaunchedEffect(folderToken, reload) {
        loading = true
        try {
            sessionKey = sessionRepo.getSessionKey()
            val visited = mutableSetOf<String>(); val all = mutableListOf<FileItem>()
            suspend fun walk(token: String, path: String = "", depth: Int = 0) {
                require(depth <= 16 && visited.size < 200) { "Documentation hierarchy is too large" }
                if (!visited.add(token)) return
                val result = api.request("public-folders/$token", query = mapOf("sessionKey" to sessionKey, "folderAccessToken" to folderAccessToken)).getOrThrow().jsonObject
                val current = result["folder"] as? JsonObject ?: result
                if (depth == 0) { folder = current; config = (current["docsConfig"] ?: result["docsConfig"]) as? JsonObject ?: buildJsonObject {} }
                val files = (current["files"] ?: result["files"]) as? JsonArray ?: JsonArray(emptyList())
                files.forEach { item -> val file = api.json.decodeFromJsonElement<FileItem>(item); if (file.displayName.endsWith(".md", true) || file.displayName.endsWith(".markdown", true) || file.mimeType == "text/markdown") all += file.copy(folderPath = path.ifBlank { null }, folderId = current.string("id")) }
                api.rememberFolderAccess(files.mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.content }, folderAccessToken)
                val children = api.request("public-folders/$token/subfolders", query = mapOf("sessionKey" to sessionKey, "folderAccessToken" to folderAccessToken)).getOrThrow().jsonObject
                ((children["subfolders"] ?: children["folders"]) as? JsonArray)?.forEach { child -> val item = child.jsonObject; walk(item.string("slug").ifBlank { item.string("id") }, listOf(path, item.string("name")).filter { it.isNotBlank() }.joinToString(" / "), depth + 1) }
            }
            walk(folderToken); pages = all.distinctBy { it.id }; selected = selected.coerceIn(0, (pages.size - 1).coerceAtLeast(0)); error = null
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Documentation could not load" } finally { loading = false }
    }
    LaunchedEffect(ordered.getOrNull(selected)?.id) {
        val page = ordered.getOrNull(selected) ?: return@LaunchedEffect
        loading = true; content = ""
        try { content = PreviewIO.text(api.getFileDownloadUrl(page.id, sessionKey, page.slug)); error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { loading = false }
    }
    LaunchedEffect(query, pageMenu, ordered) {
        contentMatches = emptySet(); searchProgress = ""; searchFailures = 0
        if (!pageMenu || query.trim().length < 2) return@LaunchedEffect
        delay(450)
        ordered.forEachIndexed { index, page ->
            currentCoroutineContext().ensureActive(); searchProgress = "Searching page ${index + 1} of ${ordered.size}"
            try {
                val source = if (page.id == ordered.getOrNull(selected)?.id) content else PreviewIO.text(api.getFileDownloadUrl(page.id, sessionKey, page.slug))
                if (source.contains(query, true)) contentMatches = contentMatches + page.id
            } catch (e: CancellationException) { throw e } catch (_: Exception) { searchFailures++ }
        }; searchProgress = ""
    }
    val document = rememberNativeMarkdownDocument(content); val state = rememberLazyListState()
    val theme = config.string("theme", "system"); val baseScheme = when (theme) { "dark" -> darkColorScheme(); "light" -> lightColorScheme(); else -> MaterialTheme.colorScheme }
    val accent = runCatching { Color(android.graphics.Color.parseColor(config.string("accentColor", "#6366f1"))) }.getOrDefault(baseScheme.primary)
    val scheme = baseScheme.copy(primary = accent, onPrimary = if (accent.luminance() > .45f) Color.Black else Color.White)
    val font = when (config.string("font")) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; else -> FontFamily.Default }
    val typography = MaterialTheme.typography.let { it.copy(bodyLarge = it.bodyLarge.copy(fontFamily = font), bodyMedium = it.bodyMedium.copy(fontFamily = font), headlineLarge = it.headlineLarge.copy(fontFamily = font), headlineMedium = it.headlineMedium.copy(fontFamily = font), headlineSmall = it.headlineSmall.copy(fontFamily = font), titleLarge = it.titleLarge.copy(fontFamily = font), titleMedium = it.titleMedium.copy(fontFamily = font)) }
    val uriHandler = LocalUriHandler.current
    val pattern = config.string("backgroundPattern", "none"); val opacity = (config["backgroundOpacity"] as? JsonPrimitive)?.floatOrNull?.coerceIn(0f, .4f) ?: .12f
    val maxWidth = when (config.string("contentWidth")) { "wide" -> 960.dp; "full" -> 2000.dp; else -> 720.dp }
    MaterialTheme(colorScheme = scheme, typography = typography) { ProvideTextStyle(MaterialTheme.typography.bodyLarge.copy(fontFamily = font)) {
        Scaffold(topBar = { TopAppBar(title = { Text(config.string("title").ifBlank { folder?.string("name") ?: "Documentation" }, maxLines = 1) }, navigationIcon = { IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close documentation") } }, actions = {
            IconButton(onClick = { pageMenu = true }) { Icon(Icons.Outlined.MenuBook, "Browse documentation pages") }
            if (config.bool("showToc", true)) IconButton(onClick = { toc = true }) { Icon(Icons.Outlined.Toc, "Page table of contents") }
            if (folder?.bool("canEdit") == true) IconButton(onClick = { customize = true }) { Icon(Icons.Outlined.Tune, "Documentation settings") }
        }) }) { padding -> Column(Modifier.fillMaxSize().padding(padding).drawBehind {
            val color = accent.copy(alpha = opacity)
            when (pattern) { "grid" -> { var x = 0f; while (x < size.width) { drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f); x += 32.dp.toPx() }; var y = 0f; while (y < size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y), 1f); y += 32.dp.toPx() } }
                "dots" -> { var x = 12.dp.toPx(); while (x < size.width) { var y = 12.dp.toPx(); while (y < size.height) { drawCircle(color, 1.5.dp.toPx(), Offset(x, y)); y += 24.dp.toPx() }; x += 24.dp.toPx() } }
                "glow" -> drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(size.width, 0f), radius = size.maxDimension), size.maxDimension, Offset(size.width, 0f)) }
        }) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { message -> Row(Modifier.padding(16.dp)) { Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("Retry") } } }
            if (ordered.isEmpty() && !loading) Text("This documentation has no Markdown pages yet.", Modifier.padding(24.dp))
            LazyColumn(state = state, modifier = Modifier.weight(1f).widthIn(max = maxWidth).fillMaxWidth().align(Alignment.CenterHorizontally), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { if (config.string("tagline").isNotBlank()) Text(config.string("tagline"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { (config["links"] as? JsonArray).orEmpty().forEach { value -> val link = value as? JsonObject; val url = link?.string("url").orEmpty(); if (url.startsWith("https://")) TextButton(onClick = { uriHandler.openUri(url) }) { Text(link?.string("label").orEmpty()) } } } }
                document?.let { parsed -> nativeMarkdownItems(parsed, api, sessionKey, onOpenFile, keyPrefix = ordered.getOrNull(selected)?.id.orEmpty(), onAnchor = { anchor -> parsed.headings.firstOrNull { it.anchor == anchor }?.let { scope.launch { state.animateScrollToItem(it.blockIndex + 2) } } }, assetFolderId = ordered.getOrNull(selected)?.folderId) }
                item { config.string("footerText").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected--; scope.launch { state.scrollToItem(0) } }, enabled = selected > 0) { Text("Previous") }
                Text("${if (ordered.isEmpty()) 0 else selected + 1} / ${ordered.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                if (folder?.bool("canEdit") == true) IconButton(onClick = { ordered.getOrNull(selected)?.let(onOpenFile) }, enabled = ordered.isNotEmpty()) { Icon(Icons.Outlined.Edit, "Edit documentation page") }
                TextButton(onClick = { selected++; scope.launch { state.scrollToItem(0) } }, enabled = selected + 1 < ordered.size) { Text("Next") }
            }
        } }
    } }
    if (pageMenu) ModalBottomSheet(onDismissRequest = { pageMenu = false }) {
        OutlinedTextField(query, { query = it }, label = { Text("Find a documentation page") }, modifier = Modifier.fillMaxWidth().padding(16.dp))
        if (searchProgress.isNotBlank()) Text(searchProgress, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        if (query.length == 1) Text("Use two characters to search page contents too.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        if (searchFailures > 0) Text("$searchFailures pages could not be searched. Try again when connected.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.heightIn(max = 500.dp), contentPadding = PaddingValues(16.dp)) { itemsIndexed(ordered.filter { it.displayName.contains(query, true) || it.folderPath?.contains(query, true) == true || it.id in contentMatches }) { _, page ->
            val entry = hierarchy?.get(page.id) as? JsonObject ?: hierarchy?.get(page.filename) as? JsonObject
            val parent = entry?.string("parentId").orEmpty(); val indent = if (parent.isNotBlank()) 16.dp else 0.dp
            TextButton(onClick = { selected = ordered.indexOf(page); pageMenu = false; scope.launch { state.scrollToItem(0) } }, modifier = Modifier.fillMaxWidth().padding(start = indent)) { Column(Modifier.fillMaxWidth()) { Text(page.displayName); (entry?.string("folder")?.ifBlank { null } ?: page.folderPath)?.let { Text(it, style = MaterialTheme.typography.labelSmall) } } }
        } }
    }
    if (toc) ModalBottomSheet(onDismissRequest = { toc = false }) { LazyColumn(Modifier.heightIn(max = 500.dp), contentPadding = PaddingValues(16.dp)) { items(document?.headings.orEmpty()) { heading -> TextButton(onClick = { toc = false; scope.launch { state.animateScrollToItem(heading.blockIndex + 2) } }, Modifier.padding(start = ((heading.level - 1) * 12).dp)) { Text(heading.title) } } } }
    if (customize && folder != null) DocsConfigEditor(folder!!.string("id"), config, api, sessionKey, { customize = false }) { config = it; customize = false }
}

@Composable private fun DocsConfigEditor(id: String, initial: JsonObject, api: OhioApiClient, key: String?, onDismiss: () -> Unit, onSaved: (JsonObject) -> Unit) {
    val scope = rememberCoroutineScope(); var title by remember { mutableStateOf(initial.string("title")) }; var tagline by remember { mutableStateOf(initial.string("tagline")) }
    var footer by remember { mutableStateOf(initial.string("footerText")) }; var font by remember { mutableStateOf(initial.string("font", "sans")) }; var theme by remember { mutableStateOf(initial.string("theme", "system")) }
    var showToc by remember { mutableStateOf(initial.bool("showToc", true)) }; var advanced by remember { mutableStateOf(false) }; var json by remember { mutableStateOf(initial.toString()) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Documentation settings") }, text = { Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(title, { title = it }, label = { Text("Title") }); OutlinedTextField(tagline, { tagline = it }, label = { Text("Tagline") }); OutlinedTextField(footer, { footer = it }, label = { Text("Footer") })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("sans", "serif", "mono").forEach { value -> FilterChip(font == value, { font = value }, { Text(value) }) } }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("system", "light", "dark").forEach { value -> FilterChip(theme == value, { theme = value }, { Text(value) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Show table of contents", Modifier.weight(1f)); Switch(showToc, { showToc = it }) }
        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced configuration" else "Edit hierarchy, links, and layout JSON") }
        if (advanced) OutlinedTextField(json, { json = it }, label = { Text("Full configuration JSON") }, modifier = Modifier.heightIn(min = 160.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = { scope.launch { busy = true
        try { val base = if (advanced) Json.parseToJsonElement(json).jsonObject else initial
            val next = JsonObject(base + buildJsonObject { put("title", title); put("tagline", tagline); put("footerText", footer); put("font", font); put("theme", theme); put("showToc", showToc) })
            api.request("folders/$id/docs-config", "PUT", api.body("sessionKey" to key, "config" to next)).getOrThrow(); onSaved(next)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    } }, enabled = !busy) { Text(if (busy) "Saving…" else "Save settings") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
