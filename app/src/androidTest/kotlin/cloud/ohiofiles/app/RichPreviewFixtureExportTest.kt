package cloud.ohiofiles.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.ohiofiles.app.data.document.ModelGeometry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.encryption.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.*
import java.nio.*
import java.util.zip.*

/** Exports disposable fixtures into the app cache, with MIME/display names in manifest.json. */
@RunWith(AndroidJUnit4::class)
class RichPreviewFixtureExportTest {
    @Test fun exportDisposableNativePreviewFixtures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; val folder = File(context.cacheDir, "qa-preview-fixtures").apply { mkdirs() }
        val manifest = mutableListOf<JsonElement>()
        fun emit(key: String, filename: String, mime: String, bytes: ByteArray) {
            val path = File(folder, "$key.asset"); path.writeBytes(bytes)
            manifest += buildJsonObject { put("path", path.absolutePath); put("filename", filename); put("mimeType", mime); put("size", bytes.size) }
        }
        val text = "# Native preview fixture\n\n**Bold** and _italic_.\n\n- [x] Read\n- [ ] Extract\n".toByteArray()
        val zip = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { archive -> archive.putNextEntry(ZipEntry("docs/note.md")); archive.write(text); archive.closeEntry(); archive.putNextEntry(ZipEntry("docs/detail.txt")); archive.write("Native archive search target".toByteArray()); archive.closeEntry() } }.toByteArray()
        val tar = ByteArrayOutputStream().also { output -> TarArchiveOutputStream(output).use { archive -> val entry = TarArchiveEntry("docs/note.md").apply { size = text.size.toLong() }; archive.putArchiveEntry(entry); archive.write(text); archive.closeArchiveEntry(); archive.finish() } }.toByteArray()
        emit("zip", "QA-Preview.zip", "application/zip", zip); emit("tar", "QA-Preview.tar", "application/x-tar", tar)
        emit("tgz", "QA-Preview.tgz", "application/gzip", ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(tar) } }.toByteArray())
        emit("gzip", "QA-Note.md.gz", "application/gzip", ByteArrayOutputStream().also { output -> GZIPOutputStream(output).use { it.write(text) } }.toByteArray())
        emit("csv", "QA-Quoted.csv", "text/csv", "Name,Count,Description\r\nBeta,12,\"comma, quoted\"\r\nAlpha,2,\"line one\nline two\"\r\n".toByteArray())
        emit("tsv", "QA-Table.tsv", "text/tab-separated-values", "Name\tCount\nBeta\t12\nAlpha\t2\n".toByteArray())
        emit("diff", "QA-Changes.diff", "text/plain", "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-val old = 1\n+val new = 2\n".toByteArray())
        val mermaid = "# Offline diagrams\n\n```mermaid\nflowchart LR\nA[Native Start] --> B[Native Finish]\n```\n\n```mermaid\nsequenceDiagram\nAlice->>Bob: Native hello\n```\n\n```mermaid\ngantt\ntitle Native Plan\ndateFormat YYYY-MM-DD\nsection Work\nNative Task :2026-10-30, 2d\n```\n\n```mermaid\npie title Native Storage\n\"Documents\" :40\n\"Images\" :60\n```\n\n```mermaid\nflowchart LR\nA[\"${'$'}${'$'}E=mc^2${'$'}${'$'}\"] --> B[Native Math]\n```\n"
        emit("mermaid", "QA-Mermaid.md", "text/markdown", mermaid.toByteArray())
        val image = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888); Canvas(image).apply { drawColor(Color.rgb(35, 61, 95)); drawCircle(450f, 300f, 170f, Paint().apply { color = Color.rgb(156, 231, 193) }); drawText("OhioFiles", 300f, 320f, Paint().apply { color = Color.BLACK; textSize = 60f }) }
        emit("image", "QA-Image.png", "image/png", ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()); image.recycle()
        PDFBoxResourceLoader.init(context)
        fun pdf(password: Boolean): ByteArray = ByteArrayOutputStream().also { output -> PDDocument().use { document -> val outline = PDDocumentOutline(); document.documentCatalog.documentOutline = outline
            repeat(2) { index -> val page = PDPage(); document.addPage(page); PDPageContentStream(document, page).use { stream -> stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 22f); stream.newLineAtOffset(64f, 700f); stream.showText("OhioFiles Android search target page ${index + 1}"); stream.endText() }; outline.addLast(PDOutlineItem().apply { title = "Chapter ${index + 1}"; destination = PDPageFitDestination().apply { this.page = page } }) }
            if (password) document.protect(StandardProtectionPolicy("owner-secret", "OhioPreview123", AccessPermission()).apply { encryptionKeyLength = 128 })
            document.save(output)
        } }.toByteArray()
        emit("pdf", "QA-TwoPages.pdf", "application/pdf", pdf(false)); emit("pdf-password", "QA-Password-OhioPreview123.pdf", "application/pdf", pdf(true))
        val obj = """v -1 -1 -1
v 1 -1 -1
v 1 1 -1
v -1 1 -1
v -1 -1 1
v 1 -1 1
v 1 1 1
v -1 1 1
f 1 4 3 2
f 5 6 7 8
f 1 2 6 5
f 4 8 7 3
f 1 5 8 4
f 2 3 7 6
"""; val vertices = ModelGeometry.obj(obj); val glb = ModelGeometry.glb(vertices)
        emit("obj", "QA-Cube.obj", "model/obj", obj.toByteArray()); emit("glb", "QA-Cube.glb", "model/gltf-binary", glb.bytes)
        val binary = ByteBuffer.allocate(84 + vertices.size / 9 * 50).order(ByteOrder.LITTLE_ENDIAN).apply { position(80); putInt(vertices.size / 9); for (offset in vertices.indices step 9) { repeat(3) { putFloat(0f) }; repeat(9) { putFloat(vertices[offset + it]) }; putShort(0) } }.array(); emit("stl", "QA-Cube.stl", "model/stl", binary)
        val (gltf, resources) = ModelGeometry.unpack(glb)
        emit("gltf", "QA-Cube.gltf", "model/gltf+json", gltf.toString().toByteArray()); emit("gltf-buffer", "ohio-internal-buffer.bin", "application/octet-stream", resources.getValue("ohio-internal-buffer.bin"))
        File(folder, "manifest.json").writeText(JsonArray(manifest).toString())
    }
}
