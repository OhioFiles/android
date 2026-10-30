package cloud.ohiofiles.app.data.document

import com.google.re2j.Pattern
import com.google.re2j.Matcher

data class FindOptions(val caseSensitive: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)
data class TextMatch(val start: Int, val end: Int)
data class TextEdit(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart)

object DocumentEditing {
    const val MAX_VISIBLE_MATCHES = 10_000
    fun pattern(query: String, options: FindOptions): Pattern? {
        if (query.isEmpty()) return null
        require(query.length <= 4096) { "Find expressions can contain up to 4096 characters" }
        val expression = if (options.regex) query else Pattern.quote(query)
        return Pattern.compile(expression, if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE)
    }
    private fun boundary(text: String, start: Int, end: Int): Boolean {
        fun word(code: Int) = code == '_'.code || Character.isLetterOrDigit(code)
        return (start == 0 || !word(text.codePointBefore(start))) && (end == text.length || !word(text.codePointAt(end)))
    }
    fun matches(text: String, query: String, options: FindOptions = FindOptions()): List<TextMatch> {
        val matcher = pattern(query, options)?.matcher(text) ?: return emptyList()
        return buildList { while (size < MAX_VISIBLE_MATCHES && matcher.find()) { if (!options.wholeWord || boundary(text, matcher.start(), matcher.end())) add(TextMatch(matcher.start(), matcher.end())) } }
    }
    fun replace(text: String, match: TextMatch, replacement: String): TextEdit {
        require(match.start in 0..text.length && match.end in match.start..text.length)
        return TextEdit(text.replaceRange(match.start, match.end, replacement), match.start, match.start + replacement.length)
    }
    fun replaceAll(text: String, query: String, replacement: String, options: FindOptions = FindOptions()): TextEdit {
        val matcher = pattern(query, options)?.matcher(text) ?: return TextEdit(text, 0)
        require(replacement.length <= NotebookInterchange.MAX_PAGE_BYTES) { "Replacement exceeds the document size limit" }
        val output = StringBuffer()
        while (matcher.find()) { matcher.appendReplacement(output, if (options.wholeWord && !boundary(text, matcher.start(), matcher.end())) Matcher.quoteReplacement(matcher.group()) else if (options.regex) replacement else Matcher.quoteReplacement(replacement)); require(output.length <= NotebookInterchange.MAX_PAGE_BYTES) { "Replacement would exceed the 2 MiB document limit" } }
        matcher.appendTail(output)
        require(output.toString().toByteArray(Charsets.UTF_8).size <= NotebookInterchange.MAX_PAGE_BYTES) { "Replacement would exceed the 2 MiB document limit" }
        return TextEdit(output.toString(), 0)
    }
    fun wrap(text: String, start: Int, end: Int, before: String, after: String, placeholder: String): TextEdit {
        val from = minOf(start, end).coerceIn(0, text.length); val to = maxOf(start, end).coerceIn(from, text.length)
        val selected = text.substring(from, to).ifEmpty { placeholder }
        return TextEdit(text.replaceRange(from, to, before + selected + after), from + before.length, from + before.length + selected.length)
    }
    fun prefixLines(text: String, start: Int, end: Int, prefix: String): TextEdit {
        val from = text.lastIndexOf('\n', (minOf(start, end) - 1).coerceAtLeast(-1)).let { it + 1 }
        val to = text.indexOf('\n', maxOf(start, end)).let { if (it < 0) text.length else it }
        val replacement = text.substring(from, to).lines().joinToString("\n") { prefix + it }
        return TextEdit(text.replaceRange(from, to, replacement), from, from + replacement.length)
    }
}
