package cloud.ohiofiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.KSerializer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import cloud.ohiofiles.app.data.repository.*
import cloud.ohiofiles.app.ui.components.*
import cloud.ohiofiles.app.ui.screens.community.*
import cloud.ohiofiles.app.ui.screens.files.*
import cloud.ohiofiles.app.ui.screens.files.viewer.FileViewerScreen
import cloud.ohiofiles.app.ui.screens.profile.*
import cloud.ohiofiles.app.ui.screens.settings.*
import cloud.ohiofiles.app.ui.screens.upload.*
import cloud.ohiofiles.app.ui.screens.nativefeatures.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.*
import android.net.Uri
import cloud.ohiofiles.app.platform.publicFolderLinkToken

@Composable
fun MainAppScaffold(api: OhioApiClient, sessionRepo: SessionRepository, appearanceRepo: AppearanceRepository,
    uploadViewModel: UploadViewModel, filesViewModel: FilesViewModel, communityViewModel: CommunityViewModel,
    profileViewModel: ProfileViewModel, settingsViewModel: SettingsViewModel, modifier: Modifier = Modifier,
    incomingLink: String? = null, onLinkHandled: () -> Unit = {}) {
    var profileDetailOpen by remember { mutableStateOf(false) }
    var tabName by rememberSaveable { mutableStateOf(AppTab.FILES.name) }
    val tab = AppTab.valueOf(tabName)
    var file by rememberModelState(FileItem.serializer(), api.json)
    var unlocked by remember { mutableStateOf<FileItem?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    var openingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var navigationGeneration by remember { mutableLongStateOf(0L) }
    var linkedDocument by rememberModelState(FileItem.serializer(), api.json)
    var linkedProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val composeSaver = remember { Saver<ChatComposeRequest?, List<String>>(save = { request -> request?.let { listOf(it.recipientNickname.orEmpty(), it.recipientId.orEmpty(), it.groupId.orEmpty(), it.postSlug.orEmpty(), it.fileIdOrSlug.orEmpty(), it.draft.orEmpty()) }.orEmpty() }, restore = { values -> if (values.size != 6) null else ChatComposeRequest(values[0].ifBlank { null }, values[1].ifBlank { null }, values[2].ifBlank { null }, values[3].ifBlank { null }, values[4].ifBlank { null }, values[5].ifBlank { null }) }) }
    var chatCompose by rememberSaveable(stateSaver = composeSaver) { mutableStateOf<ChatComposeRequest?>(null) }
    var linkedDocs by rememberSaveable { mutableStateOf<String?>(null) }
    var linkedChat by rememberSaveable { mutableStateOf<String?>(null) }
    var publicFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var linkedPost by rememberModelState(SocialPost.serializer(), api.json)
    var routeLink by remember { mutableStateOf<String?>(null) }
    var downloadUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(file?.id, downloadUrl) { file?.let { api.restoreFileAccess(it.id, it.slug, downloadUrl) } }
    val scope = rememberCoroutineScope()
    val sessionKey by sessionRepo.sessionKeyFlow.collectAsStateWithLifecycle(null)
    LaunchedEffect(sessionKey) { if (sessionKey != null) { filesViewModel.loadData(); communityViewModel.loadFeed(); profileViewModel.loadProfile(); uploadViewModel.loadFolders() } }
    val pushAccount by remember(sessionRepo) { combine(sessionRepo.sessionKeyFlow, sessionRepo.serverUrlFlow) { key, server -> key to server } }.collectAsStateWithLifecycle(initialValue = null)
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(pushAccount) { pushAccount?.let { (key, server) -> NativePush.bind(appContext, server, key) } }
    val nickname by sessionRepo.nicknameFlow.collectAsStateWithLifecycle(null)
    val holder = rememberSaveableStateHolder()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val open: (FileItem) -> Unit = { selected ->
        openingJob?.cancel()
        val generation = ++navigationGeneration
        if (selected.passwordRequired && !selected.passwordUnlocked) unlocked = selected
        else {
            opening = true
            openingJob = scope.launch {
                try {
                    val key = sessionRepo.getSessionKey()
                    val claim = if (selected.isOneTime) api.claimOneTime(selected.slug, key) else Result.success(true)
                    if (generation == navigationGeneration) {
                        if (claim.isSuccess) { downloadUrl = api.getFileDownloadUrl(selected.id, key, selected.slug); file = selected }
                        else error = claim.exceptionOrNull()?.message ?: "Could not open file"
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { if (generation == navigationGeneration) error = e.message ?: "Could not open file" }
                finally { if (generation == navigationGeneration) opening = false }
            }
        }
    }
    LaunchedEffect(incomingLink) { if (incomingLink != null) { routeLink = incomingLink; onLinkHandled() } }
    LaunchedEffect(routeLink) {
        val link = routeLink ?: return@LaunchedEffect
        try {
            val uri = Uri.parse(if (link.startsWith("http")) link else "https://ohiofiles.cloud/" + link.trimStart('/'))
            val parts = uri.pathSegments
            val currentFile = file
            val fileReference = if (parts.firstOrNull() == "files") parts.lastOrNull() else parts.singleOrNull()
            val sameFile = currentFile != null && fileReference in setOf(currentFile.id, currentFile.slug) && parts.firstOrNull() !in setOf("notifications", "workspace", "chats", "chat", "u", "profile", "profiles", "f", "docs", "p", "posts")
            if (sameFile) { routeLink = null; return@LaunchedEffect }
            openingJob?.cancel(); openingJob = null; navigationGeneration++
            if (!sameFile) {
                file = null
                if (currentFile?.isOneTime == true) scope.launch { api.releaseOneTime(currentFile.slug) }
            }
            // Explicit destinations replace transient overlays. Workspace/editor disposal persists unsaved drafts.
            unlocked = null; linkedProfile = null; publicFolder = null; linkedDocs = null; linkedPost = null; linkedDocument = null
            chatCompose = null; linkedChat = null; opening = false; error = null
            when {
                parts.firstOrNull() == "notifications" -> tabName = AppTab.NOTIFICATIONS.name
                parts.firstOrNull() == "workspace" -> {
                    tabName = AppTab.FILES.name
                    (uri.getQueryParameter("folder") ?: uri.getQueryParameter("folderId"))?.let(filesViewModel::navigateToBreadcrumb)
                    uri.getQueryParameter("file")?.let { reference -> api.resolveFile(reference, sessionRepo.getSessionKey()).onSuccess { selected -> if (selected.isTextOrCode && selected.canEdit) linkedDocument = selected else open(selected) }.onFailure { error = it.message } }
                }
                parts.firstOrNull() == "native-uploads" -> tabName = AppTab.UPLOAD.name
                parts.firstOrNull() == "docs" && parts.size > 1 -> linkedDocs = parts[1]
                publicFolderLinkToken(parts) != null -> publicFolder = publicFolderLinkToken(parts)
                parts.firstOrNull() in listOf("chats", "chat") -> {
                    tabName = AppTab.CHATS.name
                    chatCompose = ChatComposeRequest(recipientNickname = uri.getQueryParameter("to"), recipientId = uri.getQueryParameter("recipientId") ?: uri.getQueryParameter("person") ?: parts.getOrNull(1), groupId = uri.getQueryParameter("groupId"), postSlug = uri.getQueryParameter("post"), fileIdOrSlug = uri.getQueryParameter("file"), draft = uri.getQueryParameter("draft"))
                }
                parts.firstOrNull() in listOf("p", "posts") && parts.size > 1 -> api.request("social/posts/${parts[1]}", query = mapOf("sessionKey" to sessionRepo.getSessionKey())).onSuccess { linkedPost = api.json.decodeFromJsonElement<SocialPost>(it.jsonObject["post"]!!) }.onFailure { error = it.message }
                parts.firstOrNull() in listOf("u", "profile", "profiles") -> { if (parts.size > 1) linkedProfile = parts[1] else tabName = AppTab.PROFILE.name }
                parts.isNotEmpty() && parts.first() !in listOf("workspace", "folders", "notifications") -> {
                    val slug = if (parts.first() == "files") parts.last() else parts.first()
                    api.request("files", query = mapOf("slug" to slug, "sessionKey" to sessionRepo.getSessionKey())).onSuccess { val selected = api.json.decodeFromJsonElement<FileListResponse>(it).files.firstOrNull(); if (selected != null) { open(selected) } else error = "This file is no longer available" }.onFailure { error = it.message }
                }
                else -> { tabName = AppTab.FILES.name; uri.getQueryParameter("folder")?.let(filesViewModel::navigateToBreadcrumb) }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "This link could not be opened" }
        routeLink = null
    }
    val visibleTab by rememberUpdatedState(tab)
    LaunchedEffect(Unit) { var lastCompleted = 0; uploadViewModel.completed.collect { completed -> if (completed.size > lastCompleted && visibleTab == AppTab.PROFILE) profileViewModel.loadProfile(); lastCompleted = completed.size } }
    LaunchedEffect(Unit) { uploadViewModel.navigationRequests.collect { tabName = AppTab.UPLOAD.name } }
    BackHandler(enabled = file != null || tab in listOf(AppTab.UPLOAD, AppTab.SETTINGS, AppTab.NOTIFICATIONS)) {
        if (file != null) { val old = file; file = null; if (old?.isOneTime == true) scope.launch { api.releaseOneTime(old.slug) } }
        else tabName = AppTab.FILES.name
    }
    if (file != null) {
        FileViewerScreen(file!!, downloadUrl, { val old = file; file = null; if (old?.isOneTime == true) scope.launch { api.releaseOneTime(old.slug) } }, api = api, sessionKey = sessionKey, onOpenFile = { nested -> val old = file; if (old?.isOneTime == true) scope.launch { api.releaseOneTime(old.slug) }; open(nested) })
    } else if (linkedDocs != null) {
        cloud.ohiofiles.app.ui.document.NativeDocsScreen(linkedDocs!!, api, sessionRepo, open, { linkedDocs = null })
    } else if (linkedProfile != null && publicFolder == null) {
        PublicProfileScreen(linkedProfile!!, api, sessionRepo, open, { publicFolder = it }, { linkedChat = it; linkedProfile = null; tabName = AppTab.CHATS.name }, onOpenProfile = { linkedProfile = it }, onSharePostToChat = { slug -> linkedProfile = null; chatCompose = ChatComposeRequest(postSlug = slug); tabName = AppTab.CHATS.name }) { linkedProfile = null }
    } else if (publicFolder != null) {
        PublicFolderScreen(publicFolder!!, api, sessionRepo, { selected ->
            if (selected.isTextOrCode && selected.canEdit) { linkedDocument = selected; publicFolder = null; tabName = AppTab.FILES.name }
            else open(selected)
        }) { publicFolder = null }
    } else if (linkedPost != null) {
        SocialDetailScreen(linkedPost!!, api, sessionRepo, onOpenFile = open, onOpenProfile = { linkedProfile = it }) { linkedPost = null }
    } else {
        Scaffold(topBar = { if (!(tab == AppTab.PROFILE && profileDetailOpen)) OhioTopBar(tab.title, nickname, { tabName = AppTab.PROFILE.name },
            onUpload = { tabName = AppTab.UPLOAD.name }, onNotifications = { tabName = AppTab.NOTIFICATIONS.name }, onSettings = { tabName = AppTab.SETTINGS.name },
            onBack = if (tab in listOf(AppTab.UPLOAD, AppTab.SETTINGS, AppTab.NOTIFICATIONS)) ({ tabName = AppTab.FILES.name }) else null) },
            bottomBar = { if (!imeVisible) OhioBottomBar(tab, { tabName = it.name }) }, containerColor = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
                holder.SaveableStateProvider("${api.getBaseUrl()}|${sessionKey.orEmpty()}|${tab.name}") {
                    when (tab) {
                        AppTab.FILES -> NativeWorkspaceScreen(api, sessionRepo, open, initialDocument = linkedDocument, onDocumentHandled = { linkedDocument = null }) { FilesScreen(filesViewModel, open, { tabName = AppTab.UPLOAD.name }) }
                        AppTab.UPLOAD -> UploadScreen(uploadViewModel, { filesViewModel.loadData(); tabName = AppTab.FILES.name })
                        AppTab.COMMUNITY -> CommunityScreen(communityViewModel, open, onOpenProfile = { linkedProfile = it }, onOpenFolder = { publicFolder = it }, onSharePostToChat = { slug -> chatCompose = ChatComposeRequest(postSlug = slug); tabName = AppTab.CHATS.name })
                        AppTab.CHATS -> NativeChatsScreen(api, sessionRepo, open, initialTargetId = linkedChat, onTargetHandled = { linkedChat = null }, initialCompose = chatCompose, onComposeHandled = { chatCompose = null })
                        AppTab.PROFILE -> ProfileScreen(profileViewModel, onOpenProfile = { linkedProfile = it }, onSettings = { tabName = AppTab.SETTINGS.name }, onDetailOpenChange = { profileDetailOpen = it })
                        AppTab.SETTINGS -> SettingsScreen(settingsViewModel)
                        AppTab.NOTIFICATIONS -> NativeNotificationsScreen(api, sessionRepo, onNavigate = { routeLink = it })
                    }
                }
                if (opening) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    if (unlocked != null) PasswordUnlockDialog(itemName = unlocked!!.displayName, onDismiss = { unlocked = null }, onUnlock = { password ->
        scope.launch { val target = unlocked!!; val result = api.unlockPassword(target.slug, password); if (result.isSuccess) { unlocked = null; open(target.copy(passwordUnlocked = true)) } else error = result.exceptionOrNull()?.message ?: "Wrong password" }
    })
    if (error != null) AlertDialog(onDismissRequest = { error = null }, title = { Text("Something needs attention") }, text = { Text(error!!) }, confirmButton = { TextButton({ error = null }) { Text("OK") } })
}

@Composable
private fun <T : Any> rememberModelState(serializer: KSerializer<T>, json: Json): MutableState<T?> {
    val saver = remember(serializer, json) {
        Saver<MutableState<T?>, String>(
            save = { state -> state.value?.let { json.encodeToString(serializer, it) }.orEmpty() },
            restore = { encoded -> mutableStateOf(if (encoded.isBlank()) null else runCatching { json.decodeFromString(serializer, encoded) }.getOrNull()) }
        )
    }
    return rememberSaveable(saver = saver) { mutableStateOf<T?>(null) }
}
