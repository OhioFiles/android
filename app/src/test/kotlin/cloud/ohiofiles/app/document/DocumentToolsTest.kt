package cloud.ohiofiles.app.document

import cloud.ohiofiles.app.data.document.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentToolsTest {
    @Test fun findRespectsCaseWholeWordAndUnicode() {
        assertEquals(listOf(TextMatch(0, 4), TextMatch(15, 19)), DocumentEditing.matches("Note notebook. NOTE", "note", FindOptions(wholeWord = true)))
        assertEquals(listOf(TextMatch(0, 4)), DocumentEditing.matches("note NOTE", "note", FindOptions(caseSensitive = true)))
        assertEquals(1, DocumentEditing.matches("żółć żółćx", "żółć", FindOptions(wholeWord = true)).size)
    }
    @Test fun regexReplacementAndFormattingPreserveSelection() {
        assertEquals("two one", DocumentEditing.replaceAll("one two", "(one) (two)", "$2 $1", FindOptions(regex = true)).text)
        assertEquals("$1 $1", DocumentEditing.replaceAll("x x", "x", "$1").text)
        val edit = DocumentEditing.wrap("hello world", 6, 11, "**", "**", "text")
        assertEquals("hello **world**", edit.text); assertEquals(8, edit.selectionStart); assertEquals(13, edit.selectionEnd)
        assertTrue(runCatching { DocumentEditing.matches("source", "[", FindOptions(regex = true)) }.isFailure)
    }
    @Test fun notebookZipRoundTripKeepsAllPagesAndOrder() {
        val first = NotebookPage(title = "First", filename = "intro.md", content = "# Intro\n\nzażółć", order = 0)
        val second = NotebookPage(title = "Second", filename = "detail.md", content = "# Detail\n\n**Bold**", order = 1)
        val output = ByteArrayOutputStream(); NotebookInterchange.exportZip(LocalNotebook(pages = listOf(second, first)), output)
        val imported = NotebookInterchange.import(ByteArrayInputStream(output.toByteArray()), "web-notebook.zip")
        assertEquals(listOf("intro.md", "detail.md"), imported.pages.map { it.filename }); assertEquals(first.content, imported.pages[0].content); assertEquals(second.content, imported.pages[1].content)
    }
    @Test fun notebookRejectsTraversalAndOversizedPage() {
        val bytes = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("../escape.md")); zip.write("unsafe".toByteArray()); zip.closeEntry() } }.toByteArray()
        assertTrue(runCatching { NotebookInterchange.import(ByteArrayInputStream(bytes), "bad.zip") }.isFailure)
        assertTrue(runCatching { NotebookInterchange.import(ByteArrayInputStream(ByteArray(NotebookInterchange.MAX_PAGE_BYTES + 1)), "large.md") }.isFailure)
    }
    @Test fun importedJsonNeverRetainsRemoteOverwriteAuthority() {
        val parent = NotebookPage(id = "parent", title = "Parent", editingDocument = NotebookDocument("remote-id", "remote-slug", "remote.md", "OWNER", "f".repeat(64)))
        val child = NotebookPage(id = "child", parentId = "parent", title = "Child")
        val exported = NotebookInterchange.json.encodeToString(LocalNotebook.serializer(), LocalNotebook(pages = listOf(parent, child)))
        val imported = NotebookInterchange.import(ByteArrayInputStream(exported.toByteArray()), "notebook.json")
        assertTrue(imported.pages.none { it.id in listOf("parent", "child") }); assertTrue(imported.pages.all { it.editingDocument == null }); assertEquals(imported.pages[0].id, imported.pages[1].parentId)
        val duplicate = NotebookInterchange.json.encodeToString(LocalNotebook.serializer(), LocalNotebook(pages = listOf(parent, parent)))
        assertTrue(runCatching { NotebookInterchange.import(ByteArrayInputStream(duplicate.toByteArray()), "duplicate.json") }.isFailure)
    }
    @Test fun ignoredZipEntryStillCountsAgainstDecompressionBudget() {
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip -> zip.putNextEntry(ZipEntry("ignored.bin")); val block = ByteArray(1024 * 1024); repeat(21) { zip.write(block) }; zip.closeEntry(); zip.putNextEntry(ZipEntry("note.md")); zip.write("hello".toByteArray()); zip.closeEntry() } }.toByteArray()
        assertTrue(runCatching { NotebookInterchange.import(ByteArrayInputStream(bytes), "bomb.zip") }.isFailure)
    }
}
