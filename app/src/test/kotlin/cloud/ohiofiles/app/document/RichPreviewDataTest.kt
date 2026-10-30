package cloud.ohiofiles.app.document

import cloud.ohiofiles.app.data.document.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.*

class RichPreviewDataTest {
    @Test fun csvQuotedCellsNewlinesAndTrailingCells() {
        val rows = DelimitedText.parse("Name,Description,Empty\r\n\"One, two\",\"line1\nline2 with \"\"quote\"\"\",\r\n")
        assertEquals(2, rows.size); assertEquals(listOf("One, two", "line1\nline2 with \"quote\"", ""), rows[1])
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), DelimitedText.parse("a\tb\n1\t2", '\t'))
        assertTrue(runCatching { DelimitedText.parse("\"not closed") }.isFailure)
    }
    @Test fun archiveExtractsOnlySelectedSafeEntryAndBoundsOutput() {
        val file = File.createTempFile("archive-test-", ".asset")
        try {
            ZipOutputStream(file.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("folder/note.md")); zip.write("# Note".toByteArray()); zip.closeEntry() }
            assertEquals("folder/note.md", ArchiveContents.list(file, "notes.zip").single().path)
            assertEquals("# Note", ArchiveContents.preview(file, "notes.zip", "folder/note.md").toString(Charsets.UTF_8))
            assertTrue(runCatching { ArchiveContents.preview(file, "notes.zip", "folder/note.md", 2) }.isFailure)
            assertTrue(runCatching { ArchiveContents.safePath("../escape") }.isFailure); assertTrue(runCatching { ArchiveContents.safePath("C:\\escape") }.isFailure)
        } finally { file.delete() }
    }
    @Test fun objAndBinaryStlBecomeValidGlbAndWireframe() {
        val vertices = ModelGeometry.obj("v 0 0 0\nv 1 0 0\nv 0 1 0\nf -3 -2 -1\n")
        assertEquals(9, vertices.size)
        val model = ModelGeometry.glb(vertices); val unpacked = ModelGeometry.unpack(model); assertEquals("2.0", unpacked.first["asset"]!!.toString().let { kotlinx.serialization.json.Json.parseToJsonElement(it).let { value -> (value as kotlinx.serialization.json.JsonObject)["version"].toString().trim('"') } })
        assertTrue(ModelGeometry.wireframe(model).json)
        val stl = ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN).apply { position(80); putInt(1); repeat(3) { putFloat(0f) }; vertices.forEach { putFloat(it) }; putShort(0) }.array()
        assertArrayEquals(vertices, ModelGeometry.stl(stl), .0001f)
        assertTrue(runCatching { ModelGeometry.obj("v 0 0 0\nf 1 2 3") }.isFailure)
    }
}
