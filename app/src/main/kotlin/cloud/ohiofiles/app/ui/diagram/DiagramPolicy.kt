package cloud.ohiofiles.app.ui.diagram

import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

object DiagramPolicy {
    const val VERSION = "12.2.0"
    const val MAX_SOURCE_CHARACTERS = 50_000
    const val MAX_EDGES = 500
    const val MAX_SVG_CHARACTERS = 4_000_000
    const val TIMEOUT_MILLIS = 12_000L
    fun source(value: String): String {
        require(value.length <= MAX_SOURCE_CHARACTERS + 20_000) { "Diagram source exceeds the preview limit" }
        val stripped = value.trim().replace(Regex("%%\\{[\\s\\S]*?\\}%%"), "").trim().replace(Regex("^---[ \\t]*\\r?\\n[\\s\\S]*?\\r?\\n---[ \\t]*(?:\\r?\\n|$)"), "").trim()
        require(stripped.isNotEmpty()) { "The diagram is empty" }; require(stripped.length <= MAX_SOURCE_CHARACTERS) { "Diagrams support up to 50,000 characters" }
        require(Regex("-->|---|==>|-\\.->|->>|-->>|\\.\\.>|=>|--\\|>").findAll(stripped).take(MAX_EDGES + 1).count() <= MAX_EDGES) { "Diagrams support up to 500 edges" }
        return stripped
    }
    /** Keeps the returned SVG self-contained and inert for both native rendering and export. */
    fun svg(source: String, allowMath: Boolean = false): String {
        require(source.length <= MAX_SVG_CHARACTERS) { "The rendered diagram exceeds the preview limit" }
        require(!source.contains("<!DOCTYPE", true) && !source.contains("<!ENTITY", true)) { "External SVG definitions are not allowed" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) }; setErrorHandler(object : DefaultHandler() { override fun error(e: SAXParseException) { throw IllegalArgumentException("Invalid diagram SVG") }; override fun fatalError(e: SAXParseException) { throw IllegalArgumentException("Invalid diagram SVG") } }) }
        val document = builder.parse(InputSource(StringReader(source))); val root = document.documentElement
        require(root.localName == "svg" && root.namespaceURI == "http://www.w3.org/2000/svg") { "The renderer returned an invalid SVG" }
        val forbidden = setOf("script", "foreignobject", "iframe", "object", "embed", "image", "video", "audio", "animate", "animatetransform", "animatemotion", "set")
        val queue = java.util.ArrayDeque<Pair<Element, Int>>(); queue.add(root to 0); var count = 0
        while (queue.isNotEmpty()) {
            val (element, depth) = queue.removeFirst(); require(++count <= 25_000 && depth <= 64) { "The diagram is too complex for preview" }
            val tag = (element.localName ?: element.tagName).lowercase()
            var ancestor = element.parentNode; var insideForeign = false
            while (ancestor is Element) { if ((ancestor.localName ?: ancestor.tagName).equals("foreignObject", true)) { insideForeign = true; break }; ancestor = ancestor.parentNode }
            if (insideForeign && element.namespaceURI != "http://www.w3.org/2000/svg" && tag !in setOf("div", "span", "br", "math", "semantics", "mrow", "mi", "mo", "mn", "mfrac", "msup", "msub", "msubsup", "msqrt", "mroot", "mtext", "mspace", "mover", "munder", "munderover", "mtable", "mtr", "mtd", "mstyle", "mpadded", "menclose", "mphantom", "annotation")) { element.parentNode?.removeChild(element); continue }
            if (tag == "foreignobject" && allowMath) {
                val markup = element.textContent
                val hasMath = element.getElementsByTagNameNS("http://www.w3.org/1998/Math/MathML", "math").length > 0 || (0 until element.getElementsByTagName("*").length).any { index -> (element.getElementsByTagName("*").item(index) as? Element)?.getAttribute("class")?.contains("katex") == true }
                require(hasMath && markup.length <= MAX_SOURCE_CHARACTERS) { "Embedded HTML is not supported in diagram previews" }
            } else if (tag in forbidden) { element.parentNode?.removeChild(element); continue }
            for (index in element.attributes.length - 1 downTo 0) {
                val attribute = element.attributes.item(index); val name = attribute.nodeName.lowercase(); val value = attribute.nodeValue
                if (name.startsWith("on") || name in setOf("href", "xlink:href", "src") && !value.startsWith('#') || unsafeCss(value)) element.removeAttributeNode(attribute as org.w3c.dom.Attr)
                else if (name == "d") require(value.length <= 200_000) { "A diagram path exceeds the preview limit" }
            }
            if (tag == "style") { require(element.textContent.length <= 128_000) { "Diagram styles exceed the preview limit" }; if (unsafeCss(element.textContent)) element.textContent = "" }
            val children = element.childNodes; for (index in 0 until children.length) (children.item(index) as? Element)?.let { queue.add(it to depth + 1) }
        }
        val transformer = TransformerFactory.newInstance().apply { runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }; runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }; runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "") } }.newTransformer()
        transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
        val output = StringWriter(); transformer.transform(DOMSource(document), StreamResult(output)); return output.toString().also { require(it.length <= MAX_SVG_CHARACTERS) }
    }
    private fun unsafeCss(value: String): Boolean = Regex("(?i)@import|javascript\\s*:|expression\\s*\\(|url\\s*\\(\\s*['\"]?\\s*(?!#)[^)]").containsMatchIn(value)
}
