package cloud.ohiofiles.app.platform

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object NativeActions {
    fun share(context: Context, url: String, title: String = "Share link") {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }, title))
    }

    fun download(context: Context, url: String, name: String, mime: String? = null) {
        runCatching {
            val safeName = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "OhioFiles download" }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle(safeName)
                .setDescription("OhioFiles · saving to Downloads")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
                .setAllowedOverMetered(true)
            mime?.let(request::setMimeType)
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(context, "Saving to Downloads", Toast.LENGTH_SHORT).show()
        }.onFailure { Toast.makeText(context, it.message ?: "Download could not start", Toast.LENGTH_LONG).show() }
    }
}
