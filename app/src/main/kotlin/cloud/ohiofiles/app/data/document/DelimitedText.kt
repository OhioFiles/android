package cloud.ohiofiles.app.data.document

object DelimitedText {
    /** RFC4180-style quoting, escaped quotes, embedded newlines, CRLF, and trailing empty cells. */
    fun parse(source: String, separator: Char = ','): List<List<String>> {
        require(source.length <= 4 * 1024 * 1024) { "Table preview supports up to 4 MiB" }
        val rows = mutableListOf<List<String>>(); var cells = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var index = 0; var total = 0
        fun pushCell() { require(++total <= 1_000_000) { "Table contains too many cells" }; cells.add(cell.toString()); cell.clear(); require(cells.size <= 1000) { "Table supports up to 1000 columns" } }
        fun pushRow() { pushCell(); rows.add(cells); cells = mutableListOf(); require(rows.size <= 100_000) { "Table contains too many rows" } }
        while (index < source.length) {
            val char = source[index]
            when {
                char == '"' && quoted && source.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
                char == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                char == separator && !quoted -> pushCell()
                (char == '\r' || char == '\n') && !quoted -> { pushRow(); if (char == '\r' && source.getOrNull(index + 1) == '\n') index++ }
                else -> cell.append(char)
            }; index++
        }
        require(!quoted) { "A quoted table cell is not closed" }
        if (cell.isNotEmpty() || cells.isNotEmpty() || source.lastOrNull() == separator) pushRow()
        return rows
    }
}
