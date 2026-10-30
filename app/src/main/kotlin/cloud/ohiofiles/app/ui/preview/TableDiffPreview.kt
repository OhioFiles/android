package cloud.ohiofiles.app.ui.preview

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.document.DelimitedText
import kotlinx.coroutines.*

@Composable fun DelimitedTablePreview(url: String, separator: Char) {
    var rows by remember(url) { mutableStateOf<List<List<String>>?>(null) }; var error by remember(url) { mutableStateOf<String?>(null) }; var query by remember { mutableStateOf("") }; var sort by remember { mutableIntStateOf(-1) }; var ascending by remember { mutableStateOf(true) }
    LaunchedEffect(url) { try { val source = PreviewIO.text(url, 4 * 1024 * 1024); rows = withContext(Dispatchers.Default) { DelimitedText.parse(source, separator) } } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    val filtered by produceState(emptyList<List<String>>(), rows, query, sort, ascending) {
        value = withContext(Dispatchers.Default) {
            val matches = rows.orEmpty().drop(1).filter { row -> query.isBlank() || row.any { it.contains(query, true) } }
            if (sort < 0) matches else matches.sortedWith { a, b -> val left = a.getOrElse(sort) { "" }; val right = b.getOrElse(sort) { "" }; val compare = if (left.toDoubleOrNull() != null && right.toDoubleOrNull() != null) left.toDouble().compareTo(right.toDouble()) else left.compareTo(right, true); if (ascending) compare else -compare }
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("Search table") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(12.dp))
        Text("${filtered.size} rows · ${rows?.firstOrNull()?.size ?: 0} columns", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelMedium)
        error?.let { Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
        if (rows == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        rows?.let { table ->
            val columns = table.maxOfOrNull { it.size } ?: 0
            Column(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row { repeat(columns) { column -> TextButton(onClick = { if (sort == column) ascending = !ascending else { sort = column; ascending = true } }, Modifier.width(160.dp)) { Text(table.firstOrNull()?.getOrElse(column) { "Column ${column + 1}" }.orEmpty() + if (sort == column) if (ascending) " ↑" else " ↓" else "") } } }
                HorizontalDivider()
                SelectionContainer { LazyColumn(Modifier.width((columns * 160).dp).fillMaxHeight()) { itemsIndexed(filtered) { index, row -> Row(Modifier.background(if (index % 2 == 0) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)) { repeat(columns) { column -> Text(row.getOrElse(column) { "" }, Modifier.width(160.dp).padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }; HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)) } } }
            }
        }
    }
}

@Composable fun UnifiedDiffPreview(url: String) {
    var lines by remember(url) { mutableStateOf<List<String>?>(null) }; var error by remember(url) { mutableStateOf<String?>(null) }; var changedOnly by remember { mutableStateOf(false) }
    LaunchedEffect(url) { try { lines = PreviewIO.text(url, 4 * 1024 * 1024).lines() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Changes only", Modifier.weight(1f)); Switch(changedOnly, { changedOnly = it }) }
        error?.let { Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
        if (lines == null && error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        SelectionContainer { LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(lines.orEmpty().filter { !changedOnly || it.startsWith('+') || it.startsWith('-') || it.startsWith("@@") || it.startsWith("diff ") }) { index, line ->
                val color = when { line.startsWith("@@") -> MaterialTheme.colorScheme.secondaryContainer; line.startsWith('+') && !line.startsWith("+++") -> Color(0xff37a86f).copy(alpha = .15f); line.startsWith('-') && !line.startsWith("---") -> Color(0xffdd6464).copy(alpha = .15f); else -> Color.Transparent }
                Row(Modifier.fillMaxWidth().background(color).padding(vertical = 4.dp)) { Text("${index + 1}", Modifier.width(52.dp).padding(horizontal = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(line, Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = 12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) }
            }
        } }
    }
}
