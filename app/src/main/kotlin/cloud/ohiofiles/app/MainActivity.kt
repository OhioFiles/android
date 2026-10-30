package cloud.ohiofiles.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.AppearanceConfig
import cloud.ohiofiles.app.data.repository.*
import cloud.ohiofiles.app.ui.MainAppScaffold
import cloud.ohiofiles.app.ui.screens.community.CommunityViewModel
import cloud.ohiofiles.app.ui.screens.files.FilesViewModel
import cloud.ohiofiles.app.ui.screens.profile.ProfileViewModel
import cloud.ohiofiles.app.ui.screens.settings.SettingsViewModel
import cloud.ohiofiles.app.ui.screens.upload.UploadViewModel
import cloud.ohiofiles.app.ui.theme.OhioFilesTheme
import kotlinx.coroutines.flow.MutableStateFlow

class AppGraph(application: Application) : AndroidViewModel(application) {
    val api = OhioApiClient()
    val sessions = SessionRepository(application, api)
    val appearance = AppearanceRepository(application)
    val incomingLink = MutableStateFlow<String?>(null)
}
class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private lateinit var upload: UploadViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = ViewModelProvider(this)[AppGraph::class.java]
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                UploadViewModel::class.java -> UploadViewModel(graph.api, graph.sessions, applicationContext)
                FilesViewModel::class.java -> FilesViewModel(graph.api, graph.sessions)
                CommunityViewModel::class.java -> CommunityViewModel(graph.api, graph.sessions)
                ProfileViewModel::class.java -> ProfileViewModel(graph.api, graph.sessions)
                SettingsViewModel::class.java -> SettingsViewModel(graph.appearance, graph.sessions, graph.api)
                else -> error("Unknown ViewModel")
            } as T
        }
        val provider = ViewModelProvider(this, factory)
        upload = provider[UploadViewModel::class.java]
        val files = provider[FilesViewModel::class.java]
        val community = provider[CommunityViewModel::class.java]
        val profile = provider[ProfileViewModel::class.java]
        val settings = provider[SettingsViewModel::class.java]
        if (savedInstanceState == null) handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            val config by graph.appearance.appearanceFlow.collectAsStateWithLifecycle(AppearanceConfig())
            val link by graph.incomingLink.collectAsStateWithLifecycle()
            OhioFilesTheme(config) {
                MainAppScaffold(graph.api, graph.sessions, graph.appearance, upload, files, community, profile, settings,
                    incomingLink = link, onLinkHandled = { graph.incomingLink.value = null })
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleIncomingIntent(intent) }
    @Suppress("DEPRECATION")
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("show_uploads", false)) graph.incomingLink.value = "https://ohiofiles.cloud/native-uploads"
        intent.getStringExtra("notification_link")?.let { graph.incomingLink.value = it }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) upload.onFilesSelected(applicationContext, listOf(stream))
                else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf(String::isNotBlank)?.let { text -> graph.incomingLink.value = Uri.Builder().scheme("https").authority("ohiofiles.cloud").appendPath("chats").appendQueryParameter("draft", text).build().toString() }
            }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { upload.onFilesSelected(applicationContext, it) }
            Intent.ACTION_VIEW -> intent.data?.let { graph.incomingLink.value = it.toString() }
        }
    }
}
