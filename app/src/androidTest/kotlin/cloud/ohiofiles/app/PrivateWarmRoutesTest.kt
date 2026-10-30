package cloud.ohiofiles.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PrivateWarmRoutesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun route(url: String) { compose.activityRule.scenario.onActivity { it.startActivity(Intent(it, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) } }
    private fun waitFor(text: String) { compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    @Test fun warmViewerRoutesReplaceOverlaysAndKeepUnsavedWorkspaceText() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageName == "cloud.ohiofiles.app.qa")
        val api = OhioApiClient(); val sessions = SessionRepository(context, api)
        api.setBaseUrl(sessions.getServerUrl())
        val key = sessions.getSessionKey() ?: error("Run the guarded QA account fixture first")
        check(key in context.getSharedPreferences("qa_identity_registry", Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty())
        val session = sessions.ensureSession().getOrThrow()
        val image = File(context.cacheDir, "private-route-image.asset")
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val png = api.uploadFile(context, Uri.fromFile(image), key, customFilename = "private-route.png", mimeTypeOverride = "image/png").getOrThrow()
        val note = api.uploadText(key, "private-route.md", "# Private route document").getOrThrow()
        val folder = api.createFolder(key, "Private route folder").getOrThrow()
        try {
            route(api.getPublicFileUrl(png.finalSlug!!)); waitFor("private-route.png")
            route("https://ohiofiles.cloud/notifications"); waitFor("Your updates")
            compose.onAllNodesWithText("private-route.png", useUnmergedTree = true).assertCountEquals(0)
            route(api.getPublicFileUrl(png.finalSlug!!)); waitFor("private-route.png")
            route("https://ohiofiles.cloud/workspace?file=${note.id}"); waitFor("# Private route document")
            compose.onNodeWithText("# Private route document", useUnmergedTree = true).performTextReplacement("# Private route document\nUNSAVED_ROUTE_QA")
            route("https://ohiofiles.cloud/notifications"); waitFor("Your updates")
            route("https://ohiofiles.cloud/workspace?file=${note.id}"); waitFor("UNSAVED_ROUTE_QA")
            route(api.getPublicFileUrl(png.finalSlug!!)); waitFor("private-route.png")
            route("https://ohiofiles.cloud/chats?to=saved"); waitFor("Saved messages")
            compose.onAllNodesWithText("private-route.png", useUnmergedTree = true).assertCountEquals(0)
            route(api.getPublicFolderUrl(folder)); waitFor("Private route folder")
            route("https://ohiofiles.cloud/u/${session.profileSlug}"); waitFor(session.nickname ?: "Profile")
            compose.onAllNodesWithText("Private route folder", useUnmergedTree = true).assertCountEquals(0)
        } finally {
            withContext(NonCancellable) { api.deleteFile(png.finalSlug!!, key); api.deleteFile(note.slug, key); api.deleteFolder(folder.id, key); image.delete() }
        }
    }
}
