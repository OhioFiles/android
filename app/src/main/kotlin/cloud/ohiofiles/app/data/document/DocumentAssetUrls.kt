package cloud.ohiofiles.app.data.document

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object DocumentAssetUrls {
    /** Credentials are added only to the configured API's file-download route. */
    fun image(destination: String, apiBase: String?, sessionKey: String?): String? {
        val trusted = apiBase?.toHttpUrlOrNull()
        val target = when { destination.startsWith("https://") -> destination.toHttpUrlOrNull(); destination.startsWith('/') && !destination.startsWith("//") -> trusted?.resolve(destination); else -> null } ?: return null
        if (target.scheme != "https") return null
        val ownedDownload = trusted != null && target.host == trusted.host && target.port == trusted.port && target.scheme == trusted.scheme && target.pathSegments.firstOrNull() == "files" && target.pathSegments.size >= 2
        return if (ownedDownload && sessionKey != null) target.newBuilder().setQueryParameter("sessionKey", sessionKey).build().toString() else target.toString()
    }
}
