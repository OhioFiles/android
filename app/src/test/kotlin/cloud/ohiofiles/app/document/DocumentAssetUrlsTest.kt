package cloud.ohiofiles.app.document

import cloud.ohiofiles.app.data.document.DocumentAssetUrls
import org.junit.Assert.*
import org.junit.Test

class DocumentAssetUrlsTest {
    @Test fun privateFileAuthenticationStaysOnExactApiDownloadOrigin() {
        val base = "https://api.example.test"
        assertEquals("https://api.example.test/files/owned?sessionKey=private-key", DocumentAssetUrls.image("/files/owned", base, "private-key"))
        assertEquals("https://cdn.example.test/image.png", DocumentAssetUrls.image("https://cdn.example.test/image.png", base, "private-key"))
        assertFalse(DocumentAssetUrls.image("https://api.example.test:444/files/owned", base, "private-key")!!.contains("private-key"))
        assertFalse(DocumentAssetUrls.image("https://api.example.test/other-route", base, "private-key")!!.contains("private-key"))
        assertNull(DocumentAssetUrls.image("//cdn.example.test/image.png", base, "private-key")); assertNull(DocumentAssetUrls.image("http://api.example.test/files/owned", base, "private-key"))
    }
}
