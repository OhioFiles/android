package cloud.ohiofiles.app.ui.document

import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileItem
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import cloud.ohiofiles.app.data.document.DocumentAssetUrls
import cloud.ohiofiles.app.ui.diagram.NativeMermaidDiagram

data class MarkdownHeading(val title: String, val level: Int, val anchor: String, val blockIndex: Int)
data class NativeMarkdownDocument internal constructor(internal val blocks: List<MarkdownBlock>, val headings: List<MarkdownHeading>, val error: String? = null)
internal data class MarkdownBlock(val node: Node, val depth: Int = 0, val prefix: String = "", val quote: Boolean = false)

object NativeMarkdownParser {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).build()
    fun parse(source: String): NativeMarkdownDocument {
        require(source.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024) { "Rich preview supports up to 2 MiB. Open the source or download the original." }
        val prepared = prepare(source)
        val root = parser.parse(prepared); val blocks = mutableListOf<MarkdownBlock>(); val headings = mutableListOf<MarkdownHeading>(); val anchors = mutableMapOf<String, Int>()
        val pending = java.util.ArrayDeque<Pair<Node, Int>>(); pending.add(root to 0); var count = 0
        while (pending.isNotEmpty()) { val (node, depth) = pending.removeLast(); require(++count <= 100_000 && depth <= 64) { "This document is too deeply nested or complex for rich preview. Open the source or download it." }; children(node).forEach { pending.add(it to depth + 1) } }
        fun visit(node: Node, depth: Int = 0, prefix: String = "", quote: Boolean = false) {
            when (node) {
                is Document -> children(node).forEach { visit(it, depth, prefix, quote) }
                is BulletList, is OrderedList -> {
                    var number = (node as? OrderedList)?.startNumber ?: 1
                    children(node).forEach { item -> children(item).forEachIndexed { index, child -> visit(child, depth + 1, if (index == 0) if (node is OrderedList) "${number++}." else "•" else "", quote) } }
                }
                is BlockQuote -> blocks += MarkdownBlock(node, depth, prefix, true)
                else -> {
                    if (node is Heading) { val title = plain(node); val base = title.lowercase().replace(Regex("[^\\p{L}\\p{N} -]"), "").trim().replace(Regex("\\s+"), "-"); val duplicate = anchors.getOrDefault(base, 0); anchors[base] = duplicate + 1; headings += MarkdownHeading(title, node.level, if (duplicate == 0) base else "$base-$duplicate", blocks.size) }
                    blocks += MarkdownBlock(node, depth, prefix, quote)
                }
            }
        }
        visit(root); return NativeMarkdownDocument(blocks, headings)
    }
    internal fun children(node: Node): List<Node> = buildList { var cursor = node.firstChild; while (cursor != null) { add(cursor); cursor = cursor.next } }
    internal fun plain(node: Node): String = when (node) { is Text -> node.literal; is Code -> node.literal; is SoftLineBreak, is HardLineBreak -> "\n"; else -> children(node).joinToString("") { plain(it) } }
    private fun prepare(source: String): String {
        var prepared = source.replace(Regex("!\\[\\[ohiofile:([a-zA-Z0-9_-]{3,40})]]"), "![OhioFiles attachment](/_ohiofile/$1 \"ohiofiles-embed\")")
        val lines = prepared.lines(); val output = mutableListOf<String>(); var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("!!! ") || line.startsWith("??? ")) {
                val details = line.startsWith("??? "); val rest = line.drop(4)
                output += if (details) "> [!DETAILS] $rest" else "> [!${rest.substringBefore(' ').uppercase()}] ${rest.substringAfter(' ', "")}"
                index++
                while (index < lines.size && (lines[index].startsWith("    ") || lines[index].isBlank())) { output += "> " + lines[index].removePrefix("    "); index++ }
            } else { output += line; index++ }
        }
        prepared = output.joinToString("\n")
        return prepared
    }
}

@Composable fun rememberNativeMarkdownDocument(source: String): NativeMarkdownDocument? = produceState<NativeMarkdownDocument?>(null, source) {
    value = withContext(Dispatchers.Default) { try { NativeMarkdownParser.parse(source) } catch (e: Exception) { NativeMarkdownDocument(emptyList(), emptyList(), e.message ?: "Rich preview could not load") } }
}.value

@Composable fun NativeMarkdownContent(source: String, api: OhioApiClient? = null, sessionKey: String? = null, onOpenFile: ((FileItem) -> Unit)? = null, modifier: Modifier = Modifier, assetFolderId: String? = null) {
    val document = rememberNativeMarkdownDocument(source)
    if (document == null) LinearProgressIndicator(modifier.fillMaxWidth())
    else Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { document.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; document.blocks.forEach { MarkdownBlockView(it, document, api, sessionKey, onOpenFile, null, assetFolderId) } }
}

fun LazyListScope.nativeMarkdownItems(document: NativeMarkdownDocument, api: OhioApiClient? = null, sessionKey: String? = null, onOpenFile: ((FileItem) -> Unit)? = null, keyPrefix: String = "markdown", onAnchor: ((String) -> Unit)? = null, assetFolderId: String? = null) {
    document.error?.let { message -> item(key = "$keyPrefix-error") { Text(message, color = MaterialTheme.colorScheme.error) } }
    items(document.blocks.size, key = { "$keyPrefix-$it" }) { index -> MarkdownBlockView(document.blocks[index], document, api, sessionKey, onOpenFile, onAnchor, assetFolderId) }
}

@Composable fun NativeMarkdownReader(source: String, api: OhioApiClient? = null, sessionKey: String? = null, onOpenFile: ((FileItem) -> Unit)? = null, modifier: Modifier = Modifier, assetFolderId: String? = null) {
    val document = rememberNativeMarkdownDocument(source); val state = rememberLazyListState(); val scope = rememberCoroutineScope()
    if (document == null) Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    else SelectionContainer(modifier) { LazyColumn(state = state, contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        nativeMarkdownItems(document, api, sessionKey, onOpenFile, onAnchor = { anchor -> document.headings.firstOrNull { it.anchor == anchor }?.let { heading -> scope.launch { state.animateScrollToItem(heading.blockIndex) } } }, assetFolderId = assetFolderId)
    } }
}

@Composable private fun MarkdownBlockView(block: MarkdownBlock, document: NativeMarkdownDocument, api: OhioApiClient?, sessionKey: String?, onOpenFile: ((FileItem) -> Unit)?, onAnchor: ((String) -> Unit)?, assetFolderId: String?) {
    val node = block.node; val plain = NativeMarkdownParser.plain(node)
    var expanded by remember(node) { mutableStateOf(false) }
    val callout = if (block.quote) Regex("^\\[!(NOTE|INFO|TIP|WARNING|DANGER|SPOILER|DETAILS(?::OPEN)?)][ \\n]*", RegexOption.IGNORE_CASE).find(plain) else null
    val hidden = callout?.groupValues?.get(1)?.uppercase() in listOf("SPOILER", "DETAILS")
    val modifier = Modifier.fillMaxWidth().padding(start = ((block.depth - 1).coerceAtLeast(0) * 16).dp)
    Surface(modifier, color = if (block.quote) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .6f) else Color.Transparent, shape = RoundedCornerShape(12.dp)) {
        Column(if (block.quote) Modifier.padding(12.dp) else Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (callout != null) TextButton(onClick = { expanded = !expanded }, enabled = hidden) { Text(callout.groupValues[1].substringBefore(':').lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelLarge) }
            if (!hidden || expanded) when (node) {
                is Heading -> Text(inline(node, onAnchor), style = when (node.level) { 1 -> MaterialTheme.typography.headlineLarge; 2 -> MaterialTheme.typography.headlineMedium; 3 -> MaterialTheme.typography.headlineSmall; else -> MaterialTheme.typography.titleLarge }, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                is FencedCodeBlock -> if (node.info.orEmpty().substringBefore(' ').equals("mermaid", true)) NativeMermaidDiagram(node.literal) else CodeBlock(node.literal, node.info.orEmpty().substringBefore(' '))
                is IndentedCodeBlock -> CodeBlock(node.literal, "text")
                is ThematicBreak -> HorizontalDivider()
                is TableBlock -> MarkdownTable(node)
                is HtmlBlock -> Text(node.literal, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                else -> {
                    if (Regex("^\\[TOC[1-6]?]$", RegexOption.IGNORE_CASE).matches(plain.trim())) {
                        val maximumLevel = plain.filter(Char::isDigit).toIntOrNull() ?: 6
                        document.headings.filter { it.level <= maximumLevel }.forEach { heading -> TextButton(onClick = { onAnchor?.invoke(heading.anchor) }, modifier = Modifier.padding(start = ((heading.level - 1) * 12).dp)) { Text(heading.title) } }
                    } else {
                        val task = if (block.prefix.isNotEmpty()) Regex("^\\[([ xX])] ").find(plain) else null
                        val centered = plain.startsWith("->") && plain.endsWith("<-")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (task != null) Checkbox(checked = task.groupValues[1].equals("x", true), onCheckedChange = null, modifier = Modifier.size(24.dp))
                            else if (block.prefix.isNotEmpty()) Text(block.prefix, style = MaterialTheme.typography.bodyLarge)
                            val annotated = inline(node, onAnchor)
                            val text = if (task != null) annotated.subSequence(task.value.length.coerceAtMost(annotated.length), annotated.length) else if (callout != null) annotated.subSequence(callout.value.length.coerceAtMost(annotated.length), annotated.length) else if (centered && annotated.length >= 4) annotated.subSequence(2, annotated.length - 2) else annotated
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                        }
                        imageNodes(node).forEach { image ->
                            when {
                                image.title == "ohiofiles-embed" && image.destination.startsWith("/_ohiofile/") -> OhioMarkdownEmbed(image.destination.substringAfterLast('/'), api, sessionKey, onOpenFile)
                                else -> MarkdownImage(image.destination, NativeMarkdownParser.plain(image).ifBlank { "Document image" }, api, sessionKey, assetFolderId)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun CodeBlock(source: String, language: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dark = MaterialTheme.colorScheme.surface.red + MaterialTheme.colorScheme.surface.green + MaterialTheme.colorScheme.surface.blue < 1.5f
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(language.ifBlank { "Code" }, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); IconButton(onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Code", source)) }) { Icon(Icons.Outlined.ContentCopy, "Copy code") } }
            val lines = remember(source) { source.trimEnd().lines() }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) { items(lines.size) { index -> Text(remember(lines[index], language, dark) { SourceHighlighting.highlight(lines[index], language, dark) }, Modifier.horizontalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}
@Composable private fun MarkdownTable(table: TableBlock) {
    val rows = NativeMarkdownParser.children(table).flatMap { section -> NativeMarkdownParser.children(section) }
    val columns = rows.maxOfOrNull { NativeMarkdownParser.children(it).size } ?: 1
    Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))) {
        LazyColumn(Modifier.width((columns * 180).dp).heightIn(max = 500.dp)) { items(rows.size) { index -> val row = rows[index]; Row { NativeMarkdownParser.children(row).forEach { cell -> Surface(color = if ((cell as? TableCell)?.isHeader == true) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent) { Text(inline(cell, null), Modifier.width(180.dp).padding(12.dp), fontWeight = if ((cell as? TableCell)?.isHeader == true) FontWeight.Bold else FontWeight.Normal) } } }; HorizontalDivider() } }
    }
}
@Composable private fun OhioMarkdownEmbed(slug: String, api: OhioApiClient?, sessionKey: String?, onOpenFile: ((FileItem) -> Unit)?) {
    var file by remember(slug) { mutableStateOf<FileItem?>(null) }; var error by remember(slug) { mutableStateOf<String?>(null) }
    LaunchedEffect(slug, api, sessionKey) { if (api != null) api.request("files", query = mapOf("slug" to slug, "sessionKey" to sessionKey)).onSuccess { result -> file = (result.jsonObject["files"] as? JsonArray)?.firstOrNull()?.let { api.json.decodeFromJsonElement<FileItem>(it) }; if (file == null) error = "Attachment unavailable" }.onFailure { error = it.message } }
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.AttachFile, null); Text(file?.displayName ?: "OhioFiles · $slug", Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleSmall) }; error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }; if (file != null && onOpenFile != null) TextButton({ onOpenFile(file!!) }) { Text(if (file!!.passwordRequired) "Unlock attachment" else "Open attachment") } } }
}
@Composable private fun MarkdownImage(destination: String, description: String, api: OhioApiClient?, key: String?, folderId: String?) {
    var url by remember(destination, key, folderId) { mutableStateOf<String?>(null) }; var error by remember(destination, key, folderId) { mutableStateOf<String?>(null) }
    LaunchedEffect(destination, api, key, folderId) {
        try {
            if (!destination.startsWith("https://") && !destination.startsWith('/') && api != null && folderId != null) {
                val parts = android.net.Uri.decode(destination.removePrefix("./")).split('/'); require(parts.none { it == ".." || it.isBlank() }) { "Image path must remain inside this documentation folder" }
                var directory: String? = folderId
                for (part in parts.dropLast(1)) directory = api.listFolders(key, directory).getOrThrow().folders.firstOrNull { it.name == part }?.id ?: error("Image folder is unavailable")
                var page = 1; var selected: FileItem? = null
                do { val listing = api.listFiles(key, directory, page = page, limit = 100).getOrThrow(); selected = listing.files.firstOrNull { it.displayName == parts.last() }; page++ } while (selected == null && listing.hasMore && page <= 100)
                require(selected != null) { "Image file is unavailable" }; url = api.getFileDownloadUrl(selected.id, key, selected.slug)
            } else {
                url = DocumentAssetUrls.image(destination, api?.getBaseUrl(), key) ?: error("Images require an HTTPS address")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    url?.let { AsyncImage(it, description, Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 400.dp)) }
}
private fun imageNodes(node: Node): List<Image> = if (node is Image) listOf(node) else NativeMarkdownParser.children(node).flatMap(::imageNodes)

@Composable private fun inline(node: Node, onAnchor: ((String) -> Unit)?): AnnotatedString {
    val primary = MaterialTheme.colorScheme.primary; val highlight = MaterialTheme.colorScheme.tertiaryContainer
    var spoilerRevealed by remember(node) { mutableStateOf(false) }
    return remember(node, primary, highlight, onAnchor, spoilerRevealed) { buildAnnotatedString {
        fun custom(value: String) {
            val regex = Regex("==(.+?)==|!~(.+?)~!|%([a-z]+)%(.+?)%%|\\|\\|(.+?)\\|\\||::badge\\[([^]]+)]|\\[\\[([^]]+)]]")
            var offset = 0
            regex.findAll(value).forEach { match ->
                append(value.substring(offset, match.range.first)); val g = match.groupValues
                when { g[1].isNotEmpty() -> withStyle(SpanStyle(background = highlight)) { append(g[1]) }; g[2].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(g[2]) }
                    g[3].isNotEmpty() -> withStyle(SpanStyle(color = when (g[3]) { "blue" -> Color(0xff4588d4); "green" -> Color(0xff338b60); "red" -> Color(0xffc95454); "orange" -> Color(0xffb97425); else -> primary })) { append(g[4]) }
                    g[5].isNotEmpty() -> withLink(LinkAnnotation.Clickable("reveal-spoiler", TextLinkStyles(SpanStyle(background = highlight, textDecoration = TextDecoration.Underline))) { spoilerRevealed = !spoilerRevealed }) { append(if (spoilerRevealed) g[5] else "Tap to reveal spoiler") }
                    g[6].isNotEmpty() -> withStyle(SpanStyle(background = highlight, fontWeight = FontWeight.Bold)) { append(" ${g[6]} ") }
                    else -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = highlight)) { append(" ${g[7]} ") } }
                offset = match.range.last + 1
            }; append(value.substring(offset))
        }
        fun walk(current: Node) {
            when (current) {
                is Text -> custom(current.literal)
                is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = highlight.copy(alpha = .45f))) { append(current.literal) }
                is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { NativeMarkdownParser.children(current).forEach(::walk) }
                is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { NativeMarkdownParser.children(current).forEach(::walk) }
                is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { NativeMarkdownParser.children(current).forEach(::walk) }
                is Link -> {
                    val url = current.destination
                    if (url.startsWith('#')) withLink(LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline))) { onAnchor?.invoke(url.removePrefix("#")) }) { NativeMarkdownParser.children(current).forEach(::walk) }
                    else if (Uri.parse(url).scheme?.lowercase() in listOf("https", "http", "mailto")) withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline)))) { NativeMarkdownParser.children(current).forEach(::walk) }
                    else NativeMarkdownParser.children(current).forEach(::walk)
                }
                is Image -> Unit
                is HtmlInline -> append(current.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> NativeMarkdownParser.children(current).forEach(::walk)
            }
        }; walk(node)
    } }
}
