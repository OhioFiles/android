package cloud.ohiofiles.app.document

import cloud.ohiofiles.app.ui.diagram.DiagramPolicy
import org.junit.Assert.*
import org.junit.Test

class DiagramPolicyTest {
    @Test fun uploadedConfigurationCannotOverrideTheApp() {
        val source = """%%{init: {"securityLevel":"loose","themeCSS":"@import url(https://bad.test)"}}%%
---
config:
  securityLevel: loose
---
flowchart LR
A[Start] --> B[Finish]
"""
        val safe = DiagramPolicy.source(source)
        assertTrue(safe.startsWith("flowchart LR")); assertFalse(safe.contains("securityLevel")); assertFalse(safe.contains("bad.test"))
        assertTrue(runCatching { DiagramPolicy.source("flowchart LR\n" + "A-->B\n".repeat(501)) }.isFailure)
        assertTrue(runCatching { DiagramPolicy.source("x".repeat(50_001)) }.isFailure)
    }
    @Test fun sanitizedSvgKeepsLocalMarkersAndRemovesActiveExternalContent() {
        val unsafe = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 100 100"><defs><marker id="arrow"><path d="M0 0L5 5"/></marker></defs><style>.edge{marker-end:url(#arrow)}.bad{fill:url(https://bad.test/a)}</style><script>alert('bad')</script><foreignObject><div xmlns="http://www.w3.org/1999/xhtml">external</div></foreignObject><path d="M0 0L10 10" marker-end="url(#arrow)" onclick="alert('bad')"/><use xlink:href="https://bad.test/a"/><text>Visible label</text></svg>"""
        val safe = DiagramPolicy.svg(unsafe)
        assertTrue(safe.contains("Visible label")); assertTrue(safe.contains("url(#arrow)")); assertFalse(safe.contains("<script")); assertFalse(safe.contains("foreignObject")); assertFalse(safe.contains("onclick")); assertFalse(safe.contains("https://bad.test"))
        assertTrue(runCatching { DiagramPolicy.svg("<!DOCTYPE svg [<!ENTITY external SYSTEM 'file:///secret'>]><svg xmlns='http://www.w3.org/2000/svg'>&external;</svg>") }.isFailure)
    }
    @Test fun onlySanitizedGeneratedMathForeignObjectsCanBeRetained() {
        val math = """<svg xmlns="http://www.w3.org/2000/svg"><foreignObject width="100" height="40"><div xmlns="http://www.w3.org/1999/xhtml"><span class="katex"><math xmlns="http://www.w3.org/1998/Math/MathML"><mi>x</mi></math></span><form action="https://bad.test"><input/></form></div></foreignObject></svg>"""
        val safe = DiagramPolicy.svg(math, allowMath = true)
        assertTrue(safe.contains("foreignObject")); assertTrue(safe.contains("katex")); assertFalse(safe.contains("<form")); assertFalse(safe.contains("bad.test"))
        assertTrue(runCatching { DiagramPolicy.svg("<svg xmlns='http://www.w3.org/2000/svg'><foreignObject><div>arbitrary HTML</div></foreignObject></svg>", allowMath = true) }.isFailure)
    }
}
