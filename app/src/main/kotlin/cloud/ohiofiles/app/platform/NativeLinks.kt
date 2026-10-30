package cloud.ohiofiles.app.platform

// These namespaces both resolve public folder tokens in the web/API contract.
fun publicFolderLinkToken(segments: List<String>): String? =
    segments.getOrNull(1)?.takeIf { segments.firstOrNull() in setOf("f", "docs") && it.isNotBlank() }
