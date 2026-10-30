package cloud.ohiofiles.app.ui.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PdfOutlineEntry(val title: String, val page: Int, val depth: Int)
data class PdfSearchHit(val page: Int, val boxes: List<RectF>, val snippet: String)

class NativePdfDocument private constructor(private val file: File, private val document: PDDocument) {
    private val mutex = Mutex(); private var closed = false
    val pageCount = document.numberOfPages
    suspend fun outline(): List<PdfOutlineEntry> = withContext(Dispatchers.IO) { mutex.withLock {
        check(!closed)
        val cancellation = currentCoroutineContext()
        val result = mutableListOf<PdfOutlineEntry>()
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem, Boolean>())
        fun walk(first: com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem?, depth: Int) {
            var entry = first
            while (entry != null && visited.size < 1000 && depth < 20) {
                cancellation.ensureActive()
                if (!visited.add(entry)) break
                val page = runCatching { entry!!.findDestinationPage(document)?.let { document.pages.indexOf(it) } }.getOrNull()
                if (page != null && page >= 0) result += PdfOutlineEntry(entry.title.orEmpty(), page, depth)
                walk(entry.firstChild, depth + 1); entry = entry.nextSibling
            }
        }; walk(document.documentCatalog.documentOutline?.firstChild, 0); result
    } }
    suspend fun render(page: Int, width: Int = 1200, hits: List<PdfSearchHit> = emptyList()): Bitmap = withContext(Dispatchers.IO) { mutex.withLock {
        check(!closed); currentCoroutineContext().ensureActive()
        val crop = document.getPage(page).cropBox; val rotated = document.getPage(page).rotation % 180 != 0
        val pageWidth = if (rotated) crop.height else crop.width; val pageHeight = if (rotated) crop.width else crop.height
        require(pageWidth.isFinite() && pageHeight.isFinite() && pageWidth > 0 && pageHeight > 0) { "Invalid PDF page dimensions" }
        val scale = minOf(width / pageWidth, 2400f / pageHeight, 3f).coerceAtLeast(.00001f)
        val bitmap = PDFRenderer(document).renderImage(page, scale)
        if (hits.isNotEmpty()) {
            val canvas = Canvas(bitmap); val paint = Paint().apply { color = 0x66ffbf00 }
            hits.filter { it.page == page }.flatMap { it.boxes }.forEach { box -> canvas.drawRect(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale, paint) }
        }; bitmap
    } }
    suspend fun search(query: String): List<PdfSearchHit> = withContext(Dispatchers.Default) { mutex.withLock {
        check(!closed); if (query.isBlank()) return@withLock emptyList()
        val found = mutableListOf<PdfSearchHit>()
        for (page in 0 until pageCount) {
            currentCoroutineContext().ensureActive()
            val positions = mutableListOf<RectF?>(); val text = StringBuilder()
            val stripper = object : PDFTextStripper() {
                override fun writeString(value: String, textPositions: MutableList<TextPosition>) {
                    var previous: TextPosition? = null
                    textPositions.forEach { position ->
                        val before = previous
                        if (before != null && position.xDirAdj - (before.xDirAdj + before.widthDirAdj) > position.widthOfSpace * .4f) { text.append(' '); positions.add(null) }
                        val rectangle = RectF(position.xDirAdj, position.yDirAdj - position.heightDir, position.xDirAdj + position.widthDirAdj, position.yDirAdj + 1f)
                        position.unicode.orEmpty().forEach { text.append(it); positions.add(rectangle) }; previous = position
                    }
                }
                override fun writeLineSeparator() { text.append('\n'); positions.add(null) }
                override fun writeWordSeparator() { text.append(' '); positions.add(null) }
            }.apply { startPage = page + 1; endPage = page + 1; sortByPosition = true }
            stripper.getText(document)
            val source = text.toString(); var from = 0
            while (from <= source.length - query.length) {
                val index = source.indexOf(query, from, ignoreCase = true); if (index < 0) break
                found += PdfSearchHit(page, positions.subList(index, (index + query.length).coerceAtMost(positions.size)).filterNotNull(), source.substring((index - 35).coerceAtLeast(0), (index + query.length + 50).coerceAtMost(source.length)).replace('\n', ' '))
                from = index + query.length.coerceAtLeast(1)
                require(found.size <= 20_000) { "Search is too broad. Use a longer phrase." }
            }
        }; found
    } }
    suspend fun close() = withContext(Dispatchers.IO) { mutex.withLock { if (!closed) { closed = true; try { document.close() } finally { file.delete() } } } }
    companion object {
        suspend fun open(context: Context, url: String, password: String = ""): NativePdfDocument {
            var owned: NativePdfDocument? = null; var file: File? = null
            try { return withContext(Dispatchers.IO) {
                PDFBoxResourceLoader.init(context.applicationContext); val local = PreviewIO.temporary(context, url); file = local
                NativePdfDocument(local, PDDocument.load(local, password)).also { owned = it }
            } } catch (e: Throwable) { withContext(NonCancellable) { owned?.close() }; file?.delete(); throw e }
        }
        suspend fun fromOwnedFile(context: Context, file: File, password: String = ""): NativePdfDocument = withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(context.applicationContext)
            try { NativePdfDocument(file, PDDocument.load(file, password)) } catch (e: Throwable) { file.delete(); throw e }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NativePdfPreview(url: String) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val alive = remember(url) { AtomicBoolean(true) }
    var document by remember(url) { mutableStateOf<NativePdfDocument?>(null) }; var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var page by remember(url) { mutableIntStateOf(0) }; var jump by remember { mutableStateOf("") }; var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<PdfSearchHit>()) }; var hit by remember { mutableIntStateOf(0) }; var searching by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(emptyList<PdfOutlineEntry>()) }; var showOutline by remember { mutableStateOf(false) }; var thumbnails by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }; var reload by remember { mutableIntStateOf(0) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url, reload) {
        try { val next = NativePdfDocument.open(context, url, password); if (alive.get()) { document = next; outline = next.outline(); error = null } else next.close() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "PDF could not open" }
    }
    LaunchedEffect(document, page, hits) { document?.let { pdf -> try { val next = pdf.render(page, hits = hits); if (alive.get()) bitmap = next else next.recycle() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }
    LaunchedEffect(document, query) {
        val pdf = document ?: return@LaunchedEffect
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }; delay(350); searching = true
        try { hits = pdf.search(query); hit = 0; hits.firstOrNull()?.let { page = it.page }; error = null }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { searching = false }
    }
    DisposableEffect(url) { onDispose { alive.set(false); val current = document; CoroutineScope(Dispatchers.IO).launch { current?.close() } } }
    Column(Modifier.fillMaxSize()) {
        if (document == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { message -> Column(Modifier.padding(16.dp)) { Text(message, color = MaterialTheme.colorScheme.error); if (document == null) { OutlinedTextField(password, { password = it }, label = { Text("PDF password, if protected") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()); TextButton(onClick = { reload++ }) { Text("Open PDF") } } } }
        document?.let { pdf ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { page-- }, enabled = page > 0) { Icon(Icons.Outlined.ChevronLeft, "Previous PDF page") }
                OutlinedTextField(jump, { jump = it.filter(Char::isDigit).take(5) }, label = { Text("Page ${page + 1}/${pdf.pageCount}") }, modifier = Modifier.width(130.dp), singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { jump.toIntOrNull()?.let { page = (it - 1).coerceIn(0, pdf.pageCount - 1); jump = "" } }))
                IconButton(onClick = { page++ }, enabled = page + 1 < pdf.pageCount) { Icon(Icons.Outlined.ChevronRight, "Next PDF page") }
                Spacer(Modifier.weight(1f)); IconButton(onClick = { thumbnails = !thumbnails }) { Icon(Icons.Outlined.ViewCarousel, "Show page thumbnails") }
                IconButton(onClick = { showOutline = true }, enabled = outline.isNotEmpty()) { Icon(Icons.Outlined.Toc, "Show PDF outline") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search PDF text") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true)
            if (query.isNotBlank()) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (searching) "Searching…" else if (hits.isEmpty()) "No text matches" else "${hit + 1} of ${hits.size} · ${hits[hit].snippet}", Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.labelSmall)
                IconButton(onClick = { hit = (hit - 1 + hits.size) % hits.size; page = hits[hit].page }, enabled = hits.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous PDF match") }
                IconButton(onClick = { hit = (hit + 1) % hits.size; page = hits[hit].page }, enabled = hits.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, "Next PDF match") }
            }
            if (thumbnails) LazyRow(Modifier.height(112.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(pdf.pageCount) { index ->
                val thumb by produceState<Bitmap?>(null, pdf, index) { value = pdf.render(index, 120) }
                OutlinedCard(onClick = { page = index }, Modifier.width(76.dp)) { Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { thumb?.let { Image(it.asImageBitmap(), "PDF page ${index + 1} thumbnail", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }; Text("${index + 1}", Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelSmall) }
            } }
            Box(Modifier.weight(1f)) { bitmap?.let { image -> ZoomablePreview(Modifier.fillMaxSize()) { modifier -> Image(image.asImageBitmap(), "PDF page ${page + 1}", modifier, contentScale = ContentScale.Fit) } } ?: CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        }
    }
    if (showOutline) ModalBottomSheet(onDismissRequest = { showOutline = false }) { LazyColumn(Modifier.heightIn(max = 500.dp), contentPadding = PaddingValues(16.dp)) { items(outline) { entry -> TextButton(onClick = { page = entry.page; showOutline = false }, Modifier.fillMaxWidth().padding(start = (entry.depth * 16).dp)) { Text(entry.title, Modifier.weight(1f)); Text("${entry.page + 1}") } } } }
}
