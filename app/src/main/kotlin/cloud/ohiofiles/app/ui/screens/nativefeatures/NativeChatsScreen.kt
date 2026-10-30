package cloud.ohiofiles.app.ui.screens.nativefeatures

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.security.MessageDigest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.model.SocialPost
import cloud.ohiofiles.app.ui.screens.community.SocialDetailScreen
import cloud.ohiofiles.app.data.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeChatsScreen(api: OhioApiClient, sessionRepo: SessionRepository, onOpenFile: (FileItem) -> Unit = {}, initialTargetId: String? = null, onTargetHandled: () -> Unit = {}, initialCompose: ChatComposeRequest? = null, onComposeHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val repository = remember(api, sessionRepo) { ChatRepository(context.applicationContext, api, sessionRepo) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val accountKey by sessionRepo.sessionKeyFlow.collectAsStateWithLifecycle(initialValue = null)
    val mediaKey = remember(accountKey, api.getBaseUrl()) { MessageDigest.getInstance("SHA-256").digest("${api.getBaseUrl()}|${accountKey.orEmpty()}".toByteArray()).joinToString("") { "%02x".format(it) } }
    val media: ChatMediaController = viewModel(key = "chat-media-$mediaKey", factory = remember(mediaKey) { object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatMediaController(context, api.getBaseUrl(), accountKey) as T
    } })
    val mediaState by media.state.collectAsStateWithLifecycle()
    val localVoiceDrafts = rememberChatDraftStore()
    var voiceRestored by remember(media) { mutableStateOf(false) }
    LaunchedEffect(media) {
        localVoiceDrafts.forEach { (thread, encoded) -> val split = encoded.indexOf('|'); if (split > 0 && media.state.value.voices[thread] == null) runCatching { media.restoreVoice(thread, encoded.substring(split + 1), encoded.substring(0, split).toInt()) } }
        voiceRestored = true
    }
    LaunchedEffect(mediaState.voices, voiceRestored) { if (voiceRestored) { localVoiceDrafts.clear(); mediaState.voices.filterValues { !it.recording }.forEach { (thread, voice) -> localVoiceDrafts[thread] = "${voice.seconds}|${voice.path}" } } }
    DisposableEffect(media, lifecycle) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) media.stopVoice() }; lifecycle.addObserver(observer); onDispose { media.stopVoice(); lifecycle.removeObserver(observer) } }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var threads by remember { mutableStateOf(emptyList<JsonObject>()) }
    var messages by remember { mutableStateOf(emptyList<JsonObject>()) }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetName by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(false) }
    val drafts = rememberSaveable(saver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, String>(
        save = { it.entries.flatMap { entry -> listOf(entry.key, entry.value) } },
        restore = { values -> mutableStateMapOf<String, String>().apply { values.chunked(2).forEach { put(it[0], it[1]) } } }
    )) { mutableStateMapOf<String, String>() }
    val draft = drafts[targetId].orEmpty()
    var stickerPicker by remember { mutableStateOf(false) }
    var openedPost by remember { mutableStateOf<SocialPost?>(null) }
    var reactionMessage by remember { mutableStateOf<String?>(null) }
    var customReaction by rememberSaveable { mutableStateOf("") }
    var showNew by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var details by remember(targetId) { mutableStateOf<JsonObject?>(null) }
    var typingNames by remember(targetId) { mutableStateOf("") }
    var lastTypedAt by remember(targetId) { mutableLongStateOf(0L) }
    var pendingCompose by remember { mutableStateOf<ChatComposeRequest?>(null) }
    var preparingCompose by remember { mutableStateOf(false) }
    val threadKey = (if (group) "group:" else "peer:") + targetId.orEmpty()
    val attachmentDrafts = rememberChatDraftStore()
    var postId by rememberChatNullableText(attachmentDrafts, "$threadKey.post")
    var postTitle by rememberChatText(attachmentDrafts, "$threadKey.post-title")
    var linkedFileName by rememberChatText(attachmentDrafts, "$threadKey.file-name")
    var fileIds by rememberChatAttachmentSet(attachmentDrafts, "$threadKey.files")
    var folderIds by rememberChatAttachmentSet(attachmentDrafts, "$threadKey.folders")
    var accessDraft by rememberChatText(attachmentDrafts, "$threadKey.access")
    val editAccess = accessDraft == "EDIT"
    val selectedVoice = mediaState.voices[threadKey]
    val selectedUploads = mediaState.uploads.filter { it.thread == threadKey }
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    var pendingVoiceThread by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerThread by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerAttachedCount by rememberSaveable { mutableIntStateOf(0) }
    var voiceSeconds by remember(threadKey) { mutableIntStateOf(0) }
    val latestThread by rememberUpdatedState(threadKey)
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        microphoneDenied = !allowed
        if (allowed && pendingVoiceThread == latestThread && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) pendingVoiceThread?.let(media::startVoice)
    }
    val phoneFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> pickerThread?.let { media.add(it, uris, pickerAttachedCount) }; pickerThread = null }
    LaunchedEffect(threadKey) { media.stopVoice() }
    LaunchedEffect(threadKey, selectedVoice?.recording) { while (selectedVoice?.recording == true) { voiceSeconds = media.recordingSeconds(threadKey); delay(500) } }
    LaunchedEffect(mediaState.error) { mediaState.error?.let { error = it; media.dismissError() } }
    var folderOpen by remember { mutableStateOf<String?>(null) }
    suspend fun loadThreads() {
        val response = repository.get("chat/threads")
        threads = response.objects("threads") + response.objects("groups")
    }
    LaunchedEffect(repository, reload) {
        busy = true; error = null
        try { repository.initialize(); ready = true; loadThreads() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Chats could not load" }
        finally { busy = false }
    }
    LaunchedEffect(ready, initialTargetId) { if (ready && initialTargetId != null && initialCompose == null) { targetId = initialTargetId; targetName = "Conversation"; group = false; onTargetHandled() } }
    LaunchedEffect(ready, initialCompose) {
        if (ready && initialCompose != null) {
            preparingCompose = true; pendingCompose = initialCompose
            try {
                when {
                    initialCompose.groupId != null -> { targetId = initialCompose.groupId; targetName = "Group conversation"; group = true }
                    initialCompose.recipientId != null -> { targetId = initialCompose.recipientId; targetName = "Conversation"; group = false }
                    !initialCompose.recipientNickname.isNullOrBlank() -> { val person = repository.resolveRecipient(initialCompose.recipientNickname); targetId = person.text("id"); targetName = person.text("nickname"); group = false }
                    else -> { targetId = null; showNew = true }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message; targetId = null; showNew = true }
            finally { preparingCompose = false }
        }
    }
    LaunchedEffect(ready, targetId, pendingCompose, reload, preparingCompose) {
        val request = pendingCompose; val id = targetId
        if (ready && !preparingCompose && request != null && id != null) {
            try {
                val preparedPost = request.postSlug?.takeIf(String::isNotBlank)?.let { repository.resolvePost(it) }
                val preparedFile = request.fileIdOrSlug?.takeIf(String::isNotBlank)?.let { repository.resolveFile(it) }
                if (targetId != id) return@LaunchedEffect
                request.draft?.takeIf(String::isNotBlank)?.let { incoming -> val existing = drafts[id].orEmpty(); if (!existing.contains(incoming)) drafts[id] = if (existing.isBlank()) incoming.take(8000) else (existing + "\n" + incoming).take(8000) }
                if (preparedPost != null) { postId = preparedPost.text("id"); postTitle = preparedPost.text("title") }
                if (preparedFile != null) { fileIds = fileIds + preparedFile.id; linkedFileName = preparedFile.displayName }
                pendingCompose = null; onComposeHandled()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not prepare the shared content" }
        }
    }
    LaunchedEffect(ready, targetId, lifecycle) {
        if (ready) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                try { targetId?.let { messages = repository.messages(it, group); details = repository.conversationDetails } ?: loadThreads() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Chat could not refresh" }
                delay(12_000)
            }
        }
    }
    val latestTypedAt by rememberUpdatedState(lastTypedAt)
    LaunchedEffect(ready, targetId, group, lifecycle) {
        val id = targetId
        if (ready && id != null && id != repository.sessionId) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var wasTyping = false
            while (true) {
                try {
                    val isTyping = System.currentTimeMillis() - latestTypedAt < 3500
                    if (isTyping || wasTyping) repository.post("chat/typing", (if (group) "groupId" else "recipientId") to id, "isTyping" to isTyping)
                    wasTyping = isTyping
                    typingNames = repository.get("chat/typing", mapOf((if (group) "groupId" else "recipientId") to id)).objects("typingUsers").joinToString(", ") { it.text("nickname") }
                } catch (e: CancellationException) { throw e } catch (_: Exception) { typingNames = "" }
                delay(2000)
            }
        }
    }
    DisposableEffect(lifecycle, targetId, group, ready) {
        val id = targetId; val isGroup = group
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && ready && id != null) scope.launch { try { repository.post("chat/typing", (if (isGroup) "groupId" else "recipientId") to id, "isTyping" to false) } catch (e: CancellationException) { throw e } catch (_: Exception) {} } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(openedPost) { if (openedPost != null) media.stopVoice() }
    openedPost?.let { post -> SocialDetailScreen(post, api, sessionRepo, onOpenFile = onOpenFile) { openedPost = null }; return }
    BackHandler(targetId != null) { targetId = null; messages = emptyList(); error = null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (targetId != null) IconButton(onClick = { targetId = null; messages = emptyList(); error = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to chats") }
            Column(Modifier.weight(1f)) {
                Text(if (targetId == null) "Chats" else targetName, style = if (targetId == null) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("End-to-end encrypted text", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Refresh chats") }
            if (targetId == null) FilledTonalIconButton(onClick = { showNew = true }, enabled = ready) { Icon(Icons.Outlined.Edit, "New chat") }
            else IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Tune, "Conversation settings") }
        }
        error?.let { FeatureError(it) { reload++ } }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (targetId == null) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilledTonalButton(onClick = { targetId = repository.sessionId; targetName = "Saved messages"; group = false; messages = emptyList() }, enabled = ready,
                        modifier = Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(24.dp)) {
                        Icon(Icons.Outlined.BookmarkBorder, null); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text("Saved messages", style = MaterialTheme.typography.titleMedium); Text("Your private pocket notebook", style = MaterialTheme.typography.bodySmall) }
                        Icon(Icons.Outlined.ChevronRight, null)
                    }
                }
                if (threads.isEmpty() && !busy && error == null) item { FeatureEmpty("A little closer", "Share a thought, a file, or a whole folder. Start a chat to connect.") }
                items(threads, key = { it.text("kind") + it.text("id") }) { thread ->
                    val isGroup = thread.text("kind") == "group"
                    val name = thread.text("customNickname").ifBlank { thread.obj("group")?.text("name") ?: thread.text("nickname", "Conversation") }
                    Card(onClick = { targetId = thread.text("id"); targetName = name; group = isGroup; messages = emptyList(); error = null },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = RoundedCornerShape(24.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                if (isGroup) Icon(Icons.Outlined.Groups, null) else Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleLarge)
                            }
                            Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleMedium); Text(if (isGroup) "Group conversation" else "Private conversation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (thread.number("unreadCount") > 0) Badge { Text(thread.number("unreadCount").toString()) }
                            IconButton(onClick = { scope.launch {
                                try { repository.post("chat/conversations/settings", if (isGroup) "groupId" to thread.text("id") else "peerId" to thread.text("id"), "isPinned" to !thread.flag("isPinned"), method = "PATCH"); loadThreads() }
                                catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
                            } }) { Icon(Icons.Outlined.PushPin, if (thread.flag("isPinned")) "Unpin chat" else "Pin chat", tint = if (thread.flag("isPinned")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (messages.isEmpty() && error == null) item { FeatureEmpty("Make it a conversation", "Text is encrypted for the registered devices in this chat.") }
                items(messages, key = { it.text("id") }) { message ->
                    val mine = message.flag("mine")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                        val themeColor = when (details?.text("theme")) { "emerald" -> Color(0xff00a77b); "sunset" -> Color(0xffe88938); "ocean" -> Color(0xff2199df); "violet" -> Color(0xffa16cec); "midnight" -> Color(0xff6274b4); else -> MaterialTheme.colorScheme.primary }
                        Surface(color = if (mine) themeColor.copy(alpha = .18f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = if (mine) 24.dp else 6.dp, bottomEnd = if (mine) 6.dp else 24.dp), modifier = Modifier.widthIn(max = 310.dp)) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                if (group && !mine) Text(message.obj("sender")?.text("nickname").orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                val body = message.text("displayBody").ifBlank { message.text("body") }
                                val stickerMood = blobatarMood(body)
                                if (stickerMood != null) BlobatarSticker(stickerMood)
                                else if (body.isNotEmpty()) Text(body, style = MaterialTheme.typography.bodyLarge, color = if (message.flag("decryptionFailed")) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                val attachments = (message.objects("files") + listOfNotNull(message.obj("file"))).distinctBy { it.text("id") }
                                attachments.forEach { payload ->
                                    val file = api.json.decodeFromJsonElement<FileItem>(payload)
                                    if (file.isAudio && !file.isOneTime && !file.passwordRequired) ChatAudioPlayer(file.displayName, api = api, sessions = sessionRepo, file = file, onOpen = { onOpenFile(file) })
                                    else TextButton(onClick = { onOpenFile(file) }) { Icon(Icons.Outlined.Description, null); Text(file.displayName, maxLines = 2) }
                                }
                                (message.objects("folders") + listOfNotNull(message.obj("folder"))).distinctBy { it.text("id") }.forEach { folder -> TextButton(onClick = { folderOpen = folder.text("id") }) { Icon(Icons.Outlined.FolderOpen, null); Text(folder.text("name", "Folder")) } }
                                message.obj("post")?.let { post -> OutlinedCard(onClick = { scope.launch { try { openedPost = api.json.decodeFromJsonElement<SocialPost>(repository.resolvePost(post.text("slug"))) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }) { Column(Modifier.padding(12.dp)) { Text(post.text("title"), style = MaterialTheme.typography.titleSmall); Text(post.text("content"), maxLines = 4, overflow = TextOverflow.Ellipsis); Text("Open post", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) } } }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(message.text("createdAt").replace('T', ' ').take(16), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    if (mine && (message.text("readAt").isNotEmpty() || message.number("readCount") > 0)) Icon(Icons.Outlined.DoneAll, "Read", Modifier.size(16.dp))
                                    IconButton(onClick = { reactionMessage = message.text("id"); customReaction = "" }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.AddReaction, "Choose a reaction", Modifier.size(18.dp)) }
                                }
                                if (message.objects("reactions").isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { message.objects("reactions").forEach { reaction -> FilterChip(reaction.flag("reacted"), { val current = targetId; val currentGroup = group; scope.launch { try { repository.post("chat/messages/${message.text("id")}/reactions", "emoji" to reaction.text("emoji")); if (current != null && targetId == current && group == currentGroup) messages = repository.messages(current, currentGroup) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }, label = { Text("${reaction.text("emoji")} ${reaction.number("count")}") }) } }
                            }
                        }
                    }
                }
            }
            if (typingNames.isNotBlank()) Text("$typingNames typing…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            postId?.let { Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Article, null); Text(postTitle.ifBlank { "Shared post" }, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 2); IconButton({ postId = null; postTitle = "" }) { Icon(Icons.Outlined.Close, "Remove shared post") } } }
            if (fileIds.isNotEmpty() || folderIds.isNotEmpty()) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (linkedFileName.isNotBlank() && fileIds.size == 1 && folderIds.isEmpty()) linkedFileName else "${fileIds.size + folderIds.size} attached", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { accessDraft = if (editAccess) "VIEW" else "EDIT" }, enabled = selectedVoice == null) { Text(if (editAccess && selectedVoice == null) "Can edit" else "Can view") }
                IconButton(onClick = { fileIds = emptySet(); folderIds = emptySet() }) { Icon(Icons.Outlined.Close, "Remove attachments") }
            }
            if (selectedUploads.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                selectedUploads.forEach { upload -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(upload.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(upload.error ?: upload.status, style = MaterialTheme.typography.bodySmall, color = if (upload.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant); if (upload.status == "Uploading") LinearProgressIndicator(progress = { upload.progress }, modifier = Modifier.fillMaxWidth()) }
                    if (upload.status in listOf("Failed", "Canceled")) IconButton({ media.upload(upload.id) }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Retry ${upload.name}") }
                    if (upload.status in listOf("Uploading", "Queued")) IconButton({ media.cancel(upload.id) }) { Icon(Icons.Outlined.Stop, "Cancel ${upload.name} upload") }
                    IconButton({ media.remove(upload.id) }, enabled = !busy) { Icon(Icons.Outlined.Close, "Remove ${upload.name}") }
                } }
            }
            if (selectedVoice != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp)) {
                    if (selectedVoice.recording) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Mic, "Recording", tint = MaterialTheme.colorScheme.error); Text("Recording ${audioTime(voiceSeconds * 1000)}", Modifier.weight(1f).padding(horizontal = 8.dp)); FilledTonalButton({ media.stopVoice() }) { Text("Stop") } }
                    else ChatAudioPlayer("Voice message · ${audioTime(selectedVoice.seconds * 1000)}", localPath = selectedVoice.path)
                    if (busy && selectedVoice.progress > 0 && selectedVoice.uploadedId == null) LinearProgressIndicator(progress = { selectedVoice.progress }, modifier = Modifier.fillMaxWidth())
                    selectedVoice.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    TextButton({ media.discardVoice(threadKey) }, enabled = !busy) { Text(if (selectedVoice.recording) "Cancel recording" else "Discard recording") }
                }
            }
            if (microphoneDenied) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text("Allow microphone access to record a voice message.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton({ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("App permissions") } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton({ showAttach = true }, enabled = ready && !busy && selectedVoice?.recording != true) { Icon(Icons.Outlined.AttachFile, null); Text("Attach") }
                IconButton({ stickerPicker = true }, enabled = ready && !busy && selectedVoice?.recording != true) { Icon(Icons.Outlined.EmojiEmotions, "Choose a Blobatar sticker") }
                IconButton({
                    if (selectedVoice?.recording == true) media.stopVoice()
                    else if (fileIds.size + folderIds.size + selectedUploads.size >= 10) error = "Attach up to 10 files and folders per message"
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { microphoneDenied = false; media.startVoice(threadKey) }
                    else { pendingVoiceThread = threadKey; microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
                }, enabled = ready && !busy && accountKey != null && (selectedVoice == null || selectedVoice.recording)) { Icon(if (selectedVoice?.recording == true) Icons.Outlined.Stop else Icons.Outlined.Mic, if (selectedVoice?.recording == true) "Stop recording" else "Record voice message") }
            }
            val selectedSticker = blobatarMood(draft)
            if (selectedSticker != null) Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) { BlobatarSticker(selectedSticker, Modifier.size(60.dp)); Text("${selectedSticker.replaceFirstChar(Char::uppercase)} sticker", Modifier.weight(1f)); IconButton({ targetId?.let { drafts.remove(it) } }) { Icon(Icons.Outlined.Close, "Remove sticker") } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(if (selectedSticker == null) draft else "", { value -> if (value.length <= 8000) targetId?.let { drafts[it] = value; lastTypedAt = System.currentTimeMillis() } }, Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 5, shape = RoundedCornerShape(28.dp))
                FilledIconButton(onClick = {
                    val sendTarget = targetId ?: return@FilledIconButton
                    val sendGroup = group; val sendPost = postId; val sendDraft = draft; val sendThread = threadKey
                    val sendFiles = fileIds + mediaState.readyFileIds(threadKey); val sendFolders = folderIds; val sendEdit = editAccess; val sendVoicePath = selectedVoice?.path
                    scope.launch {
                        busy = true; error = null
                        try {
                            require(!media.state.value.hasUnreadyUploads(sendThread)) { "Wait for attachments to finish, retry them, or remove them before sending" }
                            val voiceId = media.uploadVoice(sendThread)
                            val finalFiles = sendFiles + listOfNotNull(voiceId)
                            require(finalFiles.size + sendFolders.size <= 10) { "Attach up to 10 files and folders per message" }
                            repository.send(sendTarget, sendGroup, sendDraft.trim(), finalFiles, sendFolders, if (voiceId != null) false else sendEdit, sendPost)
                            media.clearSent(sendThread, finalFiles)
                            if (sendVoicePath != null && media.state.value.voices[sendThread]?.path == sendVoicePath) media.discardVoice(sendThread)
                            if (drafts[sendTarget] == sendDraft) {
                                drafts.remove(sendTarget)
                            }
                            if (targetId == sendTarget && group == sendGroup) {
                                fileIds = emptySet(); folderIds = emptySet(); if (postId == sendPost) { postId = null; postTitle = "" }; linkedFileName = ""; messages = repository.messages(sendTarget, sendGroup)
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Message could not send" }
                        finally { busy = false }
                    }
                }, enabled = ready && !busy && selectedVoice?.recording != true && !mediaState.hasUnreadyUploads(threadKey) && (draft.isNotBlank() || fileIds.isNotEmpty() || folderIds.isNotEmpty() || postId != null || selectedVoice != null || selectedUploads.any { it.fileId != null }), modifier = Modifier.padding(bottom = 4.dp)) { Icon(Icons.AutoMirrored.Outlined.Send, "Send message") }
            }
        }
    }
    if (stickerPicker) ChatStickerPicker(onChoose = { mood -> targetId?.let { drafts[it] = blobatarMessage(mood) }; stickerPicker = false }, onDismiss = { stickerPicker = false })
    reactionMessage?.let { messageId -> ModalBottomSheet(onDismissRequest = { reactionMessage = null }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("React to message", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("❤️", "👍", "😂", "😮", "😢", "🔥", "🎉", "🙏", "👀", "💯").forEach { emoji -> TextButton({ val current = targetId; val currentGroup = group; reactionMessage = null; scope.launch { try { repository.post("chat/messages/$messageId/reactions", "emoji" to emoji); if (current != null && targetId == current && group == currentGroup) messages = repository.messages(current, currentGroup) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }) { Text(emoji, style = MaterialTheme.typography.headlineSmall) } } }
            OutlinedTextField(customReaction, { customReaction = it.take(12) }, label = { Text("Another emoji") }, singleLine = true, supportingText = { Text("Tap an existing reaction to remove yours.") })
            FilledTonalButton({ val emoji = customReaction.trim(); val current = targetId; val currentGroup = group; reactionMessage = null; scope.launch { try { repository.post("chat/messages/$messageId/reactions", "emoji" to emoji); if (current != null && targetId == current && group == currentGroup) messages = repository.messages(current, currentGroup) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } } }, enabled = customReaction.isNotBlank()) { Text("Add reaction") }
        }
    } }
    if (showNew && ready) NewChatDialog(repository, onDismiss = { showNew = false }, onSelect = { id, name, isGroup -> targetId = id; targetName = name; group = isGroup; messages = emptyList(); showNew = false })
    if (showAttach) ChatAttachmentDialog(repository, fileIds, folderIds, remainingSlots = (10 - selectedUploads.size - if (selectedVoice == null) 0 else 1).coerceAtLeast(0), onDeviceFiles = { pickerThread = threadKey; pickerAttachedCount = fileIds.size + folderIds.size + if (selectedVoice == null) 0 else 1; phoneFiles.launch(arrayOf("*/*")) }, onDismiss = { showAttach = false }, onSelect = { files, folders -> fileIds = files; folderIds = folders; showAttach = false })
    if (showSettings && targetId != null) ConversationSettingsDialog(repository, targetId!!, group, onDismiss = { showSettings = false }, onUpdated = { newName ->
        showSettings = false; if (newName != null) targetName = newName
        scope.launch { try { val id = targetId; if (id != null) { messages = repository.messages(id, group); details = repository.conversationDetails }; loadThreads() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    }, onLeft = { showSettings = false; targetId = null; messages = emptyList(); reload++ })
    folderOpen?.let { FolderAttachmentDialog(api, sessionRepo, it, onOpenFile) { folderOpen = null } }
}

@Composable private fun NewChatDialog(repository: ChatRepository, onDismiss: () -> Unit, onSelect: (String, String, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }; var groupName by rememberSaveable { mutableStateOf("") }
    var people by remember { mutableStateOf(emptyList<JsonObject>()) }; var selected by remember { mutableStateOf(emptySet<String>()) }
    var error by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    LaunchedEffect(query) { delay(300); try { people = repository.get("chat/people", mapOf("query" to query.take(32))).objects("people") } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Start a conversation") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Find people by nickname") }, singleLine = true)
            Text("Tap to chat. Select people to make a group.", style = MaterialTheme.typography.bodySmall)
            people.forEach { person -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(person.text("id") in selected, { checked -> selected = if (checked) selected + person.text("id") else selected - person.text("id") })
                TextButton(onClick = { onSelect(person.text("id"), person.text("nickname"), false) }, Modifier.weight(1f)) { Text(person.text("nickname")) }
            } }
            if (selected.isNotEmpty()) OutlinedTextField(groupName, { groupName = it.take(60) }, label = { Text("Group name") }, singleLine = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { if (selected.isNotEmpty()) TextButton(onClick = { scope.launch {
        busy = true
        try { val group = repository.post("chat/groups", "name" to groupName.trim(), "memberIds" to jsonStrings(selected)).obj("group") ?: error("Missing group"); onSelect(group.text("id"), group.text("name"), true) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    } }, enabled = !busy && groupName.trim().length >= 2 && selected.size <= 19) { Text("Create group") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable private fun ChatAttachmentDialog(repository: ChatRepository, initialFiles: Set<String>, initialFolders: Set<String>, remainingSlots: Int = 10, onDeviceFiles: () -> Unit, onDismiss: () -> Unit, onSelect: (Set<String>, Set<String>) -> Unit) {
    var files by remember { mutableStateOf(emptyList<JsonObject>()) }; var folders by remember { mutableStateOf(emptyList<JsonObject>()) }
    var selectedFiles by remember { mutableStateOf(initialFiles) }; var selectedFolders by remember { mutableStateOf(initialFolders) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(repository) { try { val data = repository.get("chat/attachments"); files = data.objects("files"); folders = data.objects("folders") } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Share from your files") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            Text("Choose up to $remainingSlots files and folders", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ onDismiss(); onDeviceFiles() }) { Icon(Icons.Outlined.UploadFile, null); Text("Choose files from this phone") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            files.forEach { file -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(file.text("id") in selectedFiles, { checked -> if (!checked || selectedFiles.size + selectedFolders.size < remainingSlots) selectedFiles = if (checked) selectedFiles + file.text("id") else selectedFiles - file.text("id") }); Text(file.text("filename"), maxLines = 2) } }
            folders.forEach { folder -> Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(folder.text("id") in selectedFolders, { checked -> if (!checked || selectedFiles.size + selectedFolders.size < remainingSlots) selectedFolders = if (checked) selectedFolders + folder.text("id") else selectedFolders - folder.text("id") }); Icon(Icons.Outlined.Folder, null); Text(folder.text("name"), maxLines = 2) } }
            if (files.isEmpty() && folders.isEmpty() && error == null) Text("Your uploaded files and folders will appear here.")
        }
    }, confirmButton = { TextButton(onClick = { onSelect(selectedFiles, selectedFolders) }) { Text("Attach") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun FolderAttachmentDialog(api: OhioApiClient, sessions: SessionRepository, initial: String, onOpenFile: (FileItem) -> Unit, onDismiss: () -> Unit) {
    var folderId by remember { mutableStateOf(initial) }; var files by remember { mutableStateOf(emptyList<FileItem>()) }; var children by remember { mutableStateOf(emptyList<JsonObject>()) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(folderId) {
        try { val key = sessions.getSessionKey() ?: error("Session unavailable"); files = api.listFiles(key, folderId).getOrThrow().files; children = api.request("folders", query = mapOf("sessionKey" to key, "parentId" to folderId)).getOrThrow().jsonObject.objects("folders") }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Shared folder") }, text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (folderId != initial) TextButton(onClick = { folderId = initial }) { Text("Back to shared folder") }
        children.forEach { child -> TextButton(onClick = { folderId = child.text("id") }) { Icon(Icons.Outlined.Folder, null); Text(child.text("name")) } }
        files.forEach { file -> TextButton(onClick = { onOpenFile(file); onDismiss() }) { Text(file.displayName) } }
        if (files.isEmpty() && children.isEmpty() && error == null) Text("This folder is empty.")
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable private fun ConversationSettingsDialog(repository: ChatRepository, id: String, group: Boolean, onDismiss: () -> Unit, onUpdated: (String?) -> Unit, onLeft: () -> Unit) {
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<JsonObject?>(null) }; var nickname by rememberSaveable { mutableStateOf("") }
    var theme by rememberSaveable { mutableStateOf("default") }; var name by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }; var people by remember { mutableStateOf(emptyList<JsonObject>()) }
    var addIds by remember { mutableStateOf(emptySet<String>()) }; var removeIds by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(true) }; var error by remember { mutableStateOf<String?>(null) }; var leaveConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        try {
            val response = repository.get(if (group) "chat/groups/$id/messages" else "chat/messages/$id")
            details = response.obj(if (group) "group" else "person"); nickname = details?.text("customNickname").orEmpty(); theme = details?.text("theme").orEmpty().ifBlank { "default" }; name = details?.text("name").orEmpty()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    }
    val canManage = group && details?.text("role") in listOf("OWNER", "ADMIN")
    LaunchedEffect(search, canManage) {
        if (canManage) { delay(300); try { people = repository.get("chat/people", mapOf("query" to search.take(32))).objects("people") } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (group) "Group settings" else "Conversation settings") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(nickname, { nickname = it.take(60) }, label = { Text("Your name for this chat") }, supportingText = { Text("Only you see this nickname") }, singleLine = true, enabled = !busy)
            Text("Conversation color", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("default", "midnight", "emerald", "sunset", "ocean", "violet").forEach { value -> FilterChip(theme == value, { theme = value }, label = { Text(value.replaceFirstChar(Char::uppercase)) }, enabled = !busy) }
            }
            if (group) {
                HorizontalDivider(); Text("Members", style = MaterialTheme.typography.titleSmall)
                details?.objects("members")?.forEach { member ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(member.text("nickname"), style = MaterialTheme.typography.bodyMedium); Text(member.text("role").lowercase().replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (canManage && member.text("id") != details?.text("createdById")) Checkbox(member.text("id") in removeIds, { checked -> removeIds = if (checked) removeIds + member.text("id") else removeIds - member.text("id") }, enabled = !busy)
                    }
                }
                if (canManage) {
                    Text("Select current members to remove them.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Group name") }, singleLine = true, enabled = !busy)
                    OutlinedTextField(search, { search = it.take(32) }, label = { Text("Find people to add") }, singleLine = true, enabled = !busy)
                    people.filter { person -> details?.objects("members")?.none { it.text("id") == person.text("id") } != false }.forEach { person -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(person.text("id") in addIds, { checked -> addIds = if (checked) addIds + person.text("id") else addIds - person.text("id") }, enabled = !busy)
                        Text(person.text("nickname"))
                    } }
                }
                TextButton(onClick = { leaveConfirm = true }, enabled = !busy) { Icon(Icons.Outlined.ExitToApp, null); Text(if (details?.text("role") == "OWNER") "Delete group" else "Leave group", color = MaterialTheme.colorScheme.error) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { scope.launch {
        busy = true; error = null
        try {
            if (canManage) {
                val previousName = details?.text("name").orEmpty()
                if (name.trim() != previousName || addIds.isNotEmpty() || removeIds.isNotEmpty()) repository.post("chat/groups/$id", "name" to name.trim(), "addMemberIds" to jsonStrings(addIds), "removeMemberIds" to jsonStrings(removeIds), method = "PATCH")
            }
            repository.post("chat/conversations/settings", (if (group) "groupId" else "peerId") to id, "customNickname" to nickname.trim().ifBlank { null }, "theme" to theme, method = "PATCH")
            onUpdated(nickname.trim().ifBlank { if (group) name.trim() else details?.text("nickname") })
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message } finally { busy = false }
    } }, enabled = !busy && details != null && (!canManage || name.trim().length >= 2)) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
    if (leaveConfirm) AlertDialog(onDismissRequest = { leaveConfirm = false }, title = { Text(if (details?.text("role") == "OWNER") "Delete this group?" else "Leave this group?") }, text = { Text(if (details?.text("role") == "OWNER") "This deletes the group for all members." else "You will no longer receive messages in this group.") }, confirmButton = { TextButton(onClick = { scope.launch {
        busy = true
        try { repository.getDeleteGroup(id); onLeft() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message; leaveConfirm = false } finally { busy = false }
    } }) { Text(if (details?.text("role") == "OWNER") "Delete group" else "Leave group") } }, dismissButton = { TextButton(onClick = { leaveConfirm = false }) { Text("Cancel") } })
}
