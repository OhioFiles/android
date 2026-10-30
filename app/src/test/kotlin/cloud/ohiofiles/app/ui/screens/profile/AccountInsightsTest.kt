package cloud.ohiofiles.app.ui.screens.profile

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AccountInsightsTest {
    @Test fun realZeroCountsAreDifferentFromUnavailableUsage() {
        val zero = accountInsights(Json.parseToJsonElement("""{"storage":{"fileCount":0,"folderCount":1,"totalBytes":0,"backupCount":0}}"""))!!
        assertEquals(0L, zero.files); assertEquals(1L, zero.folders); assertEquals(0L, zero.bytes)
        assertNull(accountInsights(Json.parseToJsonElement("""{"error":"Offline"}""")))
    }
    @Test fun missingOrInvalidTotalsAreNeverPresentedAsInventedZeros() {
        val partial = accountInsights(Json.parseToJsonElement("""{"storage":{"fileCount":3,"folderCount":-1,"totalBytes":"unknown"}}"""))!!
        assertEquals(3L, partial.files); assertNull(partial.folders); assertNull(partial.bytes); assertNull(partial.backups)
    }
}
