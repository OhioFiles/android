package cloud.ohiofiles.app.document

import cloud.ohiofiles.app.ui.document.NativeMarkdownParser
import org.junit.Assert.*
import org.junit.Test

class NativeMarkdownParserTest {
    @Test fun nestedGfmAndCustomSyntaxRetainAllContent() {
        val source = """# Guide

## Setup

- [x] Done
  - Nested **bold**

| Name | Value |
| --- | --- |
| One | ~~two~~ |

![Diagram](https://example.com/a.png)

> [!TIP] ==Highlighted== !~underlined~!

![[ohiofile:abc123]]

## Setup

```kt
val text = "hello"
```
"""
        val result = NativeMarkdownParser.parse(source)
        assertEquals(listOf("guide", "setup", "setup-1"), result.headings.map { it.anchor })
        assertTrue(result.headings.last().blockIndex > result.headings[1].blockIndex)
    }
}
