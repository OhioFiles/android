package cloud.ohiofiles.app.ui.screens.profile

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.model.SocialPost
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.platform.NativeActions
import cloud.ohiofiles.app.ui.screens.community.SocialDetailScreen
import cloud.ohiofiles.app.ui.screens.community.SocialPostCard
import cloud.ohiofiles.app.ui.screens.community.communityWebUrl
import cloud.ohiofiles.app.ui.screens.nativefeatures.NativeMarkdownPreview
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicProfileScreen(slug: String, api: OhioApiClient, sessions: SessionRepository, onOpenFile: (FileItem) -> Unit, onOpenFolder: (String) -> Unit, onContact: (String) -> Unit, onOpenProfile: ((String) -> Unit)? = null, onSharePostToChat: ((String) -> Unit)? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember(slug) { mutableStateOf<JsonObject?>(null) }
    var files by remember(slug) { mutableStateOf(emptyList<FileItem>()) }
    var folders by remember(slug) { mutableStateOf(emptyList<JsonObject>()) }
    var posts by remember(slug) { mutableStateOf(emptyList<SocialPost>()) }
    var loading by remember(slug) { mutableStateOf(true) }
    var mutating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable(slug) { mutableIntStateOf(0) }
    var selectedPost by remember { mutableStateOf<SocialPost?>(null) }
    var selectedTag by rememberSaveable(slug) { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    suspend fun load() {
        try {
            error = null
            val payload = api.request("profiles/$slug", query = mapOf("sessionKey" to sessions.getSessionKey())).getOrThrow().jsonObject
            profile = payload["profile"]?.jsonObject ?: error("This profile is unavailable")
            files = payload["files"]?.jsonArray?.map { element ->
                val value = element.jsonObject
                FileItem(id = value.text("id"), slug = value.text("slug"), filename = value.text("filename"), mimeType = value["mimeType"]?.jsonPrimitive?.contentOrNull, size = value["size"]?.jsonPrimitive?.longOrNull ?: value["sizeBytes"]?.jsonPrimitive?.longOrNull ?: 0L, uploadedAt = value.text("uploadedAt"), downloads = value["downloads"]?.jsonPrimitive?.intOrNull ?: 0, isPublic = true, isOneTime = value["isOneTime"]?.jsonPrimitive?.booleanOrNull ?: false, passwordRequired = value["passwordRequired"]?.jsonPrimitive?.booleanOrNull ?: false, folderId = value["folderId"]?.jsonPrimitive?.contentOrNull, readOnly = true)
            }.orEmpty()
            folders = payload["folders"]?.jsonArray?.map { it.jsonObject }.orEmpty()
            posts = payload["posts"]?.jsonArray?.map { api.json.decodeFromJsonElement<SocialPost>(it) }.orEmpty()
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Could not load profile" }
        finally { loading = false }
    }
    fun mutate(post: SocialPost, action: String) {
        if (mutating) return
        scope.launch {
            mutating = true
            try {
                val key = sessions.getSessionKey() ?: error("Open You to reconnect your session")
                val result = if (action == "REPOST") api.repostPost(post.id, key, !post.isReposted) else api.reactToPost(post.id, key, if (post.reaction == action) null else action)
                result.getOrThrow()
                load()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Action failed") }
            finally { mutating = false }
        }
    }
    LaunchedEffect(slug) { load() }
    BackHandler(onBack = onBack)
    selectedPost?.let { post -> SocialDetailScreen(post, api, sessions, onOpenFile, onOpenProfile) { selectedPost = null; scope.launch { load() } }; return }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text("Public profile") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } }, actions = { IconButton({ NativeActions.share(context, communityWebUrl(api, "u/${Uri.encode(slug)}"), "Share profile") }) { Icon(Icons.Outlined.Share, "Share profile") }; IconButton({ scope.launch { loading = true; load() } }) { Icon(Icons.Outlined.Refresh, "Refresh profile") } })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (profile == null && !loading) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(error ?: "Profile unavailable"); Button({ scope.launch { loading = true; load() } }) { Text("Retry") } }
            } else profile?.let { person ->
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val image = person.text("avatarUrl").let { if (it.startsWith("/")) api.getBaseUrl().trimEnd('/') + it else it }
                    if (image.isNotBlank()) AsyncImage(image, "${person.text("nickname")}'s avatar", Modifier.size(68.dp).clip(CircleShape)) else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.Outlined.Person, null, Modifier.size(68.dp).padding(16.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Column(Modifier.weight(1f)) { Text(person.text("nickname"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); if (person.text("mood").isNotBlank()) Text(person.text("mood"), color = MaterialTheme.colorScheme.primary, maxLines = 2); if (person.text("statusText").isNotBlank()) Text(person.text("statusText"), style = MaterialTheme.typography.bodyMedium, maxLines = 3) }
                    if (person["owner"]?.jsonPrimitive?.booleanOrNull != true && person.text("id").isNotBlank()) FilledTonalIconButton({ onContact(person.text("id")) }) { Icon(Icons.Outlined.ChatBubbleOutline, "Message ${person.text("nickname")}") }
                }
                val stats = person["stats"] as? JsonObject
                LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("publicFiles" to "files", "publicFolders" to "folders", "posts" to "posts", "reposts" to "reposts")) { (key, label) -> Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) { Text("${stats?.text(key).orEmpty().ifBlank { "0" }} ${if (stats?.text(key) == "1") label.removeSuffix("s") else label}", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge) } }
                }
                PrimaryScrollableTabRow(tab, edgePadding = 16.dp) { listOf("About", "Files", "Folders", "Posts").forEachIndexed { index, title -> Tab(tab == index, { tab = index }, text = { Text(title) }) } }
                if (tab == 0) Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("Joined ${person.text("joinedAt").take(10)}", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (person.text("readme").isBlank()) ProfileEmpty("Nothing here yet", "This person hasn't added an introduction.")
                    else NativeMarkdownPreview(person.text("readme"))
                } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                    when (tab) {
                        1 -> {
                            if (files.isEmpty()) item { ProfileEmpty("No public files", "Public files shared by this person appear here.") }
                            items(files, key = { it.id }) { file -> ElevatedCard(onClick = { onOpenFile(file) }, modifier = Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(file.displayName, maxLines = 2) }, supportingContent = { Text("${file.formattedSize} · ${file.downloads} ${if (file.downloads == 1) "download" else "downloads"}") }, leadingContent = { Icon(if (file.passwordRequired) Icons.Outlined.Lock else if (file.isImage) Icons.Outlined.Image else Icons.Outlined.InsertDriveFile, null) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, null) }) } }
                        }
                        2 -> {
                            if (folders.isEmpty()) item { ProfileEmpty("No public folders", "Public collections shared by this person appear here.") }
                            items(folders, key = { it.text("id") }) { folder -> val token = folder.text("shortCode").ifBlank { folder.text("slug") }.ifBlank { folder.text("id") }; ElevatedCard(onClick = { onOpenFolder(token) }, modifier = Modifier.fillMaxWidth()) { ListItem(headlineContent = { Text(folder.text("name")) }, supportingContent = { val counts = folder["stats"] as? JsonObject; Text("${profileCount(counts?.text("fileCount"), "file")} · ${profileCount(counts?.text("childCount"), "folder")}") }, leadingContent = { Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(if (folder["passwordRequired"]?.jsonPrimitive?.booleanOrNull == true) Icons.Outlined.Lock else Icons.Outlined.ChevronRight, null) }) } }
                        }
                        3 -> {
                            if (posts.isEmpty()) item { ProfileEmpty("No public posts", "Posts and reposts shared by this person appear here.") }
                            selectedTag?.let { tag -> item { InputChip(true, { selectedTag = null }, label = { Text("#$tag") }, trailingIcon = { Icon(Icons.Outlined.Close, "Clear tag filter", Modifier.size(18.dp)) }) } }
                            items(posts.filter { selectedTag == null || it.tags.contains(selectedTag) }, key = { it.id }) { post -> SocialPostCard(post.copy(owner = false), onLike = { mutate(post, "LIKE") }, onDislike = { mutate(post, "DISLIKE") }, onRepost = { mutate(post, "REPOST") }, onShare = { NativeActions.share(context, communityWebUrl(api, "p/${Uri.encode(post.slug)}")) }, onComments = { selectedPost = post }, onTag = { tag -> selectedTag = tag }, onOpenFile = onOpenFile, api = api, enabled = !mutating, onAuthor = onOpenProfile, onSendToChat = onSharePostToChat?.let { action -> { action(post.slug) } }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileEmpty(title: String, description: String) { Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) } }


private fun profileCount(value: String?, noun: String): String { val count = value?.toLongOrNull() ?: 0L; return "$count $noun${if (count == 1L) "" else "s"}" }
