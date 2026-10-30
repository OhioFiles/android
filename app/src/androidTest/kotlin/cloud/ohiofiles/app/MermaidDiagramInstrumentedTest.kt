package cloud.ohiofiles.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import cloud.ohiofiles.app.ui.diagram.DiagramPolicy
import cloud.ohiofiles.app.ui.diagram.OfflineDiagramRenderer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MermaidDiagramInstrumentedTest {
    @get:Rule val activity = createAndroidComposeRule<ComponentActivity>()
    @Test fun androidIcuStripsDirectivesAndFrontMatter() {
        val safe = DiagramPolicy.source("%%{init: {\"securityLevel\":\"loose\"}}%%\n---\nconfig: unsafe\n---\nflowchart LR\nA[Safe]-->B[Done]")
        assertTrue(safe.startsWith("flowchart LR")); assertFalse(safe.contains("securityLevel")); assertFalse(safe.contains("unsafe"))
    }
    @Test fun actualOfflineFlowSequenceGanttPieRenderWithLabelsInBothThemes() = runBlocking {
        val fixtures = listOf(
            "flowchart LR\nA[Native Start] --> B[Native Finish]" to "Native Start",
            "sequenceDiagram\nparticipant Alice\nparticipant Bob\nAlice->>Bob: Native hello" to "Native hello",
            "gantt\ntitle Native Plan\ndateFormat YYYY-MM-DD\nsection Work\nNative Task :2026-10-30, 2d" to "Native Task",
            "pie title Native Storage\n\"Documents\" : 40\n\"Images\" : 60" to "Documents"
        )
        for (dark in listOf(false, true)) for ((source, label) in fixtures) {
            val result = withTimeout(20_000) { OfflineDiagramRenderer.render(activity.activity, source, dark) }
            assertTrue(result.svg.contains(label)); assertTrue(result.svg.contains("<text") || result.svg.contains("<tspan")); assertTrue(result.bitmap.width > 0 && result.bitmap.height > 0)
            var opaque = false; loop@ for (x in 0 until result.bitmap.width step 11) for (y in 0 until result.bitmap.height step 11) if (result.bitmap.getPixel(x, y) ushr 24 != 0) { opaque = true; break@loop }
            assertTrue("The native diagram bitmap must contain visible pixels", opaque)
        }
    }
    @Test fun embeddedMathIsPaintedOfflineAndOwnDeadlineRecovers() = runBlocking {
        val source = "flowchart LR\nA[\"${'$'}${'$'}E=mc^2${'$'}${'$'}\"] --> B[Native Math]"
        val math = withTimeout(20_000) { OfflineDiagramRenderer.render(activity.activity, source, false) }
        assertTrue(math.svg.contains("Native Math")); assertTrue(math.svg.contains("katex", true) || math.svg.contains("MathML")); assertTrue(math.bitmap.width > 0)
        val timeout = runCatching { OfflineDiagramRenderer.render(activity.activity, "flowchart LR\nA[Deadline unique native test] --> B[Done]", true, timeoutMillis = 1) }
        assertTrue(timeout.isFailure)
        val recovered = withTimeout(20_000) { OfflineDiagramRenderer.render(activity.activity, "flowchart LR\nA[After deadline] --> B[Done]", false) }
        assertTrue(recovered.svg.contains("After deadline"))
    }
    @Test fun rejectsInvalidConfigurationAndCancellationLeavesRendererReusable() = runBlocking {
        assertTrue(runCatching { withTimeout(20_000) { OfflineDiagramRenderer.render(activity.activity, "not-a-supported-diagram !!!", false) } }.isFailure)
        val injected = "%%{init: {\"securityLevel\":\"loose\",\"themeCSS\":\"@import url(https://untrusted.invalid/probe)\"}}%%\nflowchart LR\nA[Safe Native Label] --> B[Done]"
        val result = withTimeout(20_000) { OfflineDiagramRenderer.render(activity.activity, injected, false) }
        assertTrue(result.svg.contains("Safe Native Label")); assertFalse(result.svg.contains("untrusted.invalid")); assertFalse(result.svg.contains("foreignObject"))
        val canceled = launch { OfflineDiagramRenderer.render(activity.activity, "flowchart LR\nA[Cancellation Native Label] --> B[Done]", true) }; delay(25); canceled.cancelAndJoin()
        val after = withTimeout(20_000) { OfflineDiagramRenderer.render(activity.activity, "flowchart LR\nA[After cancellation] --> B[Done]", false) }
        assertTrue(after.svg.contains("After cancellation"))
        assertTrue(DiagramPolicy.source(injected).startsWith("flowchart LR"))
    }
}
