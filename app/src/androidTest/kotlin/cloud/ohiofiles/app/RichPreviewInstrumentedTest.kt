package cloud.ohiofiles.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.ohiofiles.app.ui.preview.NativePdfDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.*
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RichPreviewInstrumentedTest {
    @Test fun realPdfTextSearchHighlightOutlineAndRendering() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext; PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("pdf-test-", ".asset", context.cacheDir)
        PDDocument().use { pdf ->
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use { stream -> stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 18f); stream.newLineAtOffset(72f, 700f); stream.showText("OhioFiles Android searchable PDF"); stream.endText() }
            val outline = PDDocumentOutline(); pdf.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply { title = "Introduction"; destination = PDPageFitDestination().apply { this.page = page } }); pdf.save(file)
        }
        val document = NativePdfDocument.fromOwnedFile(context, file)
        try {
            val matches = document.search("Android"); assertEquals(1, matches.size); assertEquals(0, matches.first().page); assertTrue(matches.first().boxes.isNotEmpty())
            assertEquals("Introduction", document.outline().first().title)
            val bitmap = document.render(0, width = 400, hits = matches); assertTrue(bitmap.width in 1..400); assertTrue(bitmap.height > 0); bitmap.recycle()
        } finally { document.close() }
        assertFalse(file.exists())
    }
}
