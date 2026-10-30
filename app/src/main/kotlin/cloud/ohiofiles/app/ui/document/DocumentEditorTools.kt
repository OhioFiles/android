package cloud.ohiofiles.app.ui.document

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SourceHighlighting {
    fun language(filename: String): String = filename.substringAfterLast('.', "text").lowercase()
    fun highlight(text: String, language: String, dark: Boolean): AnnotatedString {
        if (text.length > 250_000) return AnnotatedString(text)
        val stringColor = if (dark) Color(0xffc4e6ad) else Color(0xff326719)
        val keywordColor = if (dark) Color(0xffc8b9ff) else Color(0xff7044ab)
        val commentColor = if (dark) Color(0xff9ca8b6) else Color(0xff637260)
        val numberColor = if (dark) Color(0xffffd499) else Color(0xff975900)
        val code = language in setOf("kt", "kts", "java", "js", "jsx", "ts", "tsx", "json", "py", "css", "html", "xml", "sh", "c", "cpp", "rs", "go", "yaml", "yml", "sql", "text")
        if (!code) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            val token = Regex("(?s)(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')|(?m)(//[^\\n]*|#[^\\n]*|/\\*.*?\\*/)|\\b(class|object|fun|val|var|const|let|function|return|if|else|for|while|import|from|def|in|async|await|public|private|static|void|new|true|false|null|None|True|False|data|suspend|SELECT|FROM|WHERE)\\b|\\b\\d+(?:\\.\\d+)?\\b")
            token.findAll(text).forEach { match -> addStyle(SpanStyle(color = when { match.groups[1] != null -> stringColor; match.groups[2] != null -> commentColor; match.groups[3] != null -> keywordColor; else -> numberColor }), match.range.first, match.range.last + 1) }
        }
    }
}

@Composable fun NativeSourceEditor(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, filename: String, modifier: Modifier = Modifier, readOnly: Boolean = false) {
    val dark = MaterialTheme.colorScheme.surface.red + MaterialTheme.colorScheme.surface.green + MaterialTheme.colorScheme.surface.blue < 1.5f
    val transformation = remember(filename, dark) { VisualTransformation { input -> TransformedText(SourceHighlighting.highlight(input.text, SourceHighlighting.language(filename), dark), OffsetMapping.Identity) } }
    val history = remember(filename) { mutableStateListOf(value) }; var historyIndex by remember(filename) { mutableIntStateOf(0) }; var groupedAt by remember(filename) { mutableLongStateOf(0L) }
    LaunchedEffect(value.text) {
        if (history[historyIndex].text != value.text) {
            while (history.lastIndex > historyIndex) history.removeAt(history.lastIndex)
            val now = System.currentTimeMillis()
            if (historyIndex > 0 && now - groupedAt < 1000) history[historyIndex] = value
            else { history.add(value); historyIndex++; groupedAt = now }
            while (history.size > 100 || history.sumOf { it.text.length.toLong() } > 5_000_000L && history.size > 2) { history.removeAt(0); historyIndex-- }
        }
    }
    Column(modifier) {
        if (value.text.length > 250_000) Text("Syntax colors are paused for large documents. Editing, undo, and find still work.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { historyIndex--; groupedAt = 0; onValueChange(history[historyIndex]) }, enabled = !readOnly && historyIndex > 0) { Icon(Icons.Outlined.Undo, "Undo edit") }
            IconButton(onClick = { historyIndex++; groupedAt = 0; onValueChange(history[historyIndex]) }, enabled = !readOnly && historyIndex < history.lastIndex) { Icon(Icons.Outlined.Redo, "Redo edit") }
        }
        OutlinedTextField(value, { next -> if (next.text.toByteArray(Charsets.UTF_8).size <= NotebookInterchange.MAX_PAGE_BYTES) onValueChange(next) }, Modifier.weight(1f).fillMaxWidth(),
            readOnly = readOnly, visualTransformation = transformation, textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            placeholder = { Text("Write a note…") }, shape = MaterialTheme.shapes.large)
    }
}

@Composable fun DocumentFormattingToolbar(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, readOnly: Boolean = false) {
    var showInsert by remember { mutableStateOf<String?>(null) }; var target by rememberSaveable { mutableStateOf("") }; var label by rememberSaveable { mutableStateOf("") }
    fun apply(before: String, after: String = "", placeholder: String = "text") {
        val edit = DocumentEditing.wrap(value.text, value.selection.start, value.selection.end, before, after, placeholder)
        onValueChange(TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd)))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("H" to { apply("## ", placeholder = "Heading") }, "B" to { apply("**", "**") }, "I" to { apply("_", "_") }, "U" to { apply("!~", "~!") }, "Highlight" to { apply("==", "==") },
            "Link" to { showInsert = "Link"; target = "https://"; label = "Link text" }, "Image" to { showInsert = "Image"; target = "https://"; label = "Image description" },
            "List" to { val edit = DocumentEditing.prefixLines(value.text, value.selection.start, value.selection.end, "- "); onValueChange(TextFieldValue(edit.text, TextRange(edit.selectionStart, edit.selectionEnd))) },
            "Task" to { apply("- [ ] ", placeholder = "Task") }, "Quote" to { apply("> ") }, "Code" to { apply("```\n", "\n```", "code") },
            "Table" to { apply("\n| Column 1 | Column 2 |\n| --- | --- |\n| ", " |  |\n", "Value") }).forEach { (title, action) ->
            TextButton(onClick = action, enabled = !readOnly, modifier = Modifier.heightIn(min = 48.dp)) { Text(title, fontWeight = if (title == "B") FontWeight.Bold else FontWeight.Normal) }
        }
    }
    showInsert?.let { type -> AlertDialog(onDismissRequest = { showInsert = null }, title = { Text("Insert ${type.lowercase()}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(label, { label = it }, label = { Text(if (type == "Image") "Image description" else "Link text") })
        OutlinedTextField(target, { target = it }, label = { Text("HTTPS address") }, singleLine = true)
    } }, confirmButton = { TextButton(onClick = { val escaped = target.replace(" ", "%20").replace(")", "%29"); apply(if (type == "Image") "![" else "[", "]($escaped)", label); showInsert = null }, enabled = target.startsWith("https://")) { Text("Insert") } }, dismissButton = { TextButton(onClick = { showInsert = null }) { Text("Cancel") } }) }
}

@Composable fun DocumentFindReplace(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, readOnly: Boolean, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }; var replacement by rememberSaveable { mutableStateOf("") }
    var caseSensitive by rememberSaveable { mutableStateOf(false) }; var wholeWord by rememberSaveable { mutableStateOf(false) }; var regex by rememberSaveable { mutableStateOf(false) }
    var current by rememberSaveable { mutableIntStateOf(0) }; var error by remember { mutableStateOf<String?>(null) }
    val options = FindOptions(caseSensitive, wholeWord, regex)
    val source = value.text
    val result by produceState<Result<List<TextMatch>>>(Result.success(emptyList()), source, query, options) { this.value = withContext(Dispatchers.Default) { runCatching { DocumentEditing.matches(source, query, options) } } }
    val matches = result.getOrDefault(emptyList())
    fun select(index: Int) { if (matches.isNotEmpty()) { current = ((index % matches.size) + matches.size) % matches.size; val found = matches[current]; onValueChange(value.copy(selection = TextRange(found.start, found.end))) } }
    LaunchedEffect(query, options, matches) { current = 0; select(0) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClose) { Icon(Icons.Outlined.Close, "Close find and replace") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(caseSensitive, { caseSensitive = !caseSensitive }, { Text("Match case") }); FilterChip(wholeWord, { wholeWord = !wholeWord }, { Text("Whole word") }); FilterChip(regex, { regex = !regex }, { Text("Regular expression") })
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (matches.isEmpty()) "No matches" else "${current.coerceAtMost(matches.lastIndex) + 1} of ${matches.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton({ select(current - 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous match") }
            IconButton({ select(current + 1) }, enabled = matches.isNotEmpty()) { Icon(Icons.Outlined.KeyboardArrowDown, "Next match") }
        }
        if (!readOnly) {
            OutlinedTextField(replacement, { replacement = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { runCatching { matches.getOrNull(current.coerceAtMost(matches.lastIndex))?.let { match -> DocumentEditing.replace(value.text, match, replacement).let { onValueChange(TextFieldValue(it.text, TextRange(it.selectionStart, it.selectionEnd))) } } }.onFailure { error = it.message } }, enabled = matches.isNotEmpty()) { Text("Replace") }
                TextButton(onClick = { runCatching { DocumentEditing.replaceAll(value.text, query, replacement, options).let { require(it.text.toByteArray().size <= NotebookInterchange.MAX_PAGE_BYTES) { "Document exceeds 2 MiB" }; onValueChange(TextFieldValue(it.text, TextRange(it.selectionStart))) } }.onFailure { error = it.message } }, enabled = matches.isNotEmpty()) { Text("Replace all") }
            }
        }
        (result.exceptionOrNull()?.message ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (regex) Text("RE2 regular expressions use a bounded, linear-time engine.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (matches.size == DocumentEditing.MAX_VISIBLE_MATCHES) Text("Navigation shows the first 10,000 matches. Refine the search to see later matches. Replace all applies to the whole document.", style = MaterialTheme.typography.labelSmall)
    }
}
