package cloud.ohiofiles.app.ui.screens.community

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.*
import cloud.ohiofiles.app.platform.NativeActions
import cloud.ohiofiles.app.ui.components.FileCard
import cloud.ohiofiles.app.ui.components.QrCodeDialog
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import cloud.ohiofiles.app.ui.document.NativeMarkdownContent

internal fun communityWebUrl(api: OhioApiClient, path: String): String {
    val base = Uri.parse(api.getBaseUrl())
    return base.buildUpon().authority(base.authority?.removePrefix("api.")).encodedPath("/" + path.trimStart('/')).clearQuery().build().toString()
}
internal fun SocialAttachment.asFile() = FileItem(id = id, slug = slug, filename = filename.ifBlank { originalName ?: slug }, originalName = originalName, mimeType = mimeType, size = if (size > 0) size else sizeBytes, isPublic = isPublic, passwordRequired = passwordRequired)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(viewModel: CommunityViewModel, onOpenFile: (FileItem) -> Unit, modifier: Modifier = Modifier, onOpenProfile: ((String) -> Unit)? = null, onOpenFolder: ((String) -> Unit)? = null, onSharePostToChat: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val mutating by viewModel.mutating.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedPost by remember { mutableStateOf<SocialPost?>(null) }
    var composer by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SocialPost?>(null) }
    var deleting by remember { mutableStateOf<SocialPost?>(null) }
    var loadingEditor by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) { delay(350); viewModel.search(query.trim()) }
    LaunchedEffect(message, composer) { if (!composer) message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() } }
    selectedPost?.let { post -> SocialDetailScreen(post, viewModel.api, viewModel.sessionRepo, onOpenFile = onOpenFile, onOpenProfile = onOpenProfile, onBack = { selectedPost = null; viewModel.loadFeed() }); return }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), modifier = modifier.fillMaxSize(), snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
        if (tab == 0) ExtendedFloatingActionButton(onClick = { editing = null; composer = true; viewModel.loadAttachments() }, icon = { Icon(Icons.Outlined.Edit, null) }, text = { Text("New post") })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text("A little inspiration.", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp))
            OutlinedTextField(query, { query = it.take(80) }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text(when (tab) { 0 -> "Search posts, people or tags"; 1 -> "Search loaded public files"; else -> "Search public folders" }) }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (query.isNotBlank()) IconButton({ query = "" }) { Icon(Icons.Outlined.Close, "Clear search") } }, shape = MaterialTheme.shapes.extraLarge)
            PrimaryTabRow(tab) { listOf("Posts", "Files", "Folders").forEachIndexed { index, label -> Tab(tab == index, { tab = index }, text = { Text(label) }) } }
            when (val value = state) {
                CommunityUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is CommunityUiState.Error -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(value.message); Button(viewModel::loadFeed) { Text("Retry") } }
                is CommunityUiState.Success -> {
                    if (loadingEditor) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        value.selectedTag?.let { tag -> item { InputChip(true, { viewModel.filterByTag(null) }, label = { Text("#$tag") }, trailingIcon = { Icon(Icons.Outlined.Close, "Clear tag", Modifier.size(18.dp)) }) } }
                        if (tab == 0) {
                            if (value.posts.isEmpty()) item { EmptyCommunity("No posts found", "Try a different search, or share something of your own.") }
                            items(value.posts, key = { it.id }) { post -> SocialPostCard(post, onLike = { viewModel.reactToPost(post, if (post.reaction == "LIKE") null else "LIKE") }, onRepost = { viewModel.repost(post) }, onShare = { NativeActions.share(context, communityWebUrl(viewModel.api, "p/${Uri.encode(post.slug)}")) }, onComments = { selectedPost = post }, onDislike = { viewModel.reactToPost(post, if (post.reaction == "DISLIKE") null else "DISLIKE") }, onEdit = { scope.launch { loadingEditor = true; viewModel.loadFullPost(post.slug).onSuccess { editing = it; composer = true; viewModel.loadAttachments() }.onFailure { snackbar.showSnackbar(it.message ?: "Could not load the complete post") }; loadingEditor = false } }, onDelete = { deleting = post }, onTag = viewModel::filterByTag, onOpenFile = onOpenFile, api = viewModel.api, enabled = !mutating, onAuthor = onOpenProfile, onSendToChat = onSharePostToChat?.let { action -> { action(post.slug) } }) }
                            if (value.hasMore) item { TextButton(viewModel::loadMore, Modifier.fillMaxWidth()) { Text("Load more posts") } }
                        } else if (tab == 1) {
                            item { Text("Searching ${value.publicFiles.size} loaded public files. Load more to include the next page.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            value.discoveryError?.let { problem -> item { Text(problem, color = MaterialTheme.colorScheme.error); TextButton(viewModel::retryDiscovery) { Text("Retry discovery") } } }
                            val files = value.publicFiles.filter { query.isBlank() || it.displayName.contains(query, true) || it.slug.contains(query, true) }
                            if (files.isEmpty()) item { EmptyCommunity("No public files found", "Public files shared by the community appear here.") }
                            items(files, key = { it.id }) { file ->
                                val url = viewModel.api.getFileDownloadUrl(file.id, slug = file.slug)
                                FileCard(file.copy(isOwner = false, shareAccess = null, access = null, readOnly = true), url, onFileClick = { onOpenFile(file) }, onDownload = { if (file.passwordRequired || file.isOneTime) onOpenFile(file) else NativeActions.download(context, url, file.displayName, file.mimeType) }, onShare = { NativeActions.share(context, communityWebUrl(viewModel.api, Uri.encode(file.slug))) }, onQrCode = { qr = communityWebUrl(viewModel.api, Uri.encode(file.slug)) }, onSaveToggle = viewModel::saveFile, onDelete = {})
                            }
                            if (value.hasMoreFiles) item { TextButton(viewModel::loadMorePublicFiles, Modifier.fillMaxWidth()) { Text("Load more public files") } }
                        } else {
                            val folders = value.publicFolders.filter { folder -> query.isBlank() || folder["name"]?.jsonPrimitive?.contentOrNull?.contains(query, true) == true || folder["ownerPublicSessionNickname"]?.jsonPrimitive?.contentOrNull?.contains(query, true) == true }
                            if (folders.isEmpty()) item { EmptyCommunity("No public folders found", "Try a different search or explore public files.") }
                            value.discoveryError?.let { problem -> item { Text(problem, color = MaterialTheme.colorScheme.error); TextButton(viewModel::retryDiscovery) { Text("Retry discovery") } } }
                            items(folders, key = { it["id"]!!.jsonPrimitive.content }) { folder ->
                                val name = folder["name"]?.jsonPrimitive?.contentOrNull ?: "Shared folder"
                                val token = folder["shortCode"]?.jsonPrimitive?.contentOrNull ?: folder["slug"]?.jsonPrimitive?.contentOrNull ?: folder["id"]!!.jsonPrimitive.content
                                ElevatedCard(onClick = { onOpenFolder?.invoke(token) }, enabled = onOpenFolder != null, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
                                    ListItem(headlineContent = { Text(name) }, supportingContent = { Text(folder["ownerPublicSessionNickname"]?.jsonPrimitive?.contentOrNull ?: "Public collection") }, leadingContent = { Icon(if (folder["folderType"]?.jsonPrimitive?.contentOrNull == "DOCS") Icons.Outlined.MenuBook else Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(if (folder["passwordRequired"]?.jsonPrimitive?.booleanOrNull == true) Icons.Outlined.Lock else Icons.Outlined.ChevronRight, if (folder["passwordRequired"]?.jsonPrimitive?.booleanOrNull == true) "Password protected folder" else "Open folder") })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (composer) PostComposer(editing, attachments, mutating, message, viewModel.api, onDismiss = { composer = false }, onSubmit = { title, content, tags -> scope.launch { if (viewModel.publish(editing, title, content, tags)) composer = false } })
    qr?.let { QrCodeDialog("Share with a scan", it) { qr = null } }
    deleting?.let { post -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this post?") }, text = { Text("Your post and its conversation will no longer be visible.") }, confirmButton = { TextButton({ viewModel.deletePost(post); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun EmptyCommunity(title: String, description: String) { Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.Forum, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleLarge); Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
fun SocialPostCard(post: SocialPost, onLike: () -> Unit, onRepost: () -> Unit, onShare: () -> Unit, onComments: () -> Unit = {}, onDislike: () -> Unit = {}, onEdit: () -> Unit = {}, onDelete: () -> Unit = {}, onTag: (String) -> Unit = {}, onOpenFile: (FileItem) -> Unit = {}, api: OhioApiClient? = null, enabled: Boolean = true, onAuthor: ((String) -> Unit)? = null, onSendToChat: (() -> Unit)? = null) {
    var menu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.heightIn(min = 48.dp).clickable(enabled = onAuthor != null && !post.author?.profileSlug.isNullOrBlank(), onClickLabel = "View ${post.author?.nickname ?: "author"} profile") { post.author?.profileSlug?.let { onAuthor?.invoke(it) } }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(post.author?.nickname ?: "OhioFiles member", style = MaterialTheme.typography.titleSmall); Text(post.createdAt?.take(10).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (post.owner) Box { IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Post options") }; DropdownMenu(menu, { menu = false }) { DropdownMenuItem({ Text("Edit post") }, { menu = false; onEdit() }); DropdownMenuItem({ Text("Delete post") }, { menu = false; onDelete() }) } }
            }
            Column(Modifier.fillMaxWidth().clickable(onClick = onComments)) { Text(post.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); NativeMarkdownContent(post.content.replace(Regex("!\\[\\[ohiofile:[^]]+]]"), "").take(800), api = api, modifier = Modifier.padding(top = 8.dp))
                if (post.content.length > 800) Text("Read full post", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            if (post.tags.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(post.tags, key = { it }) { tag -> SuggestionChip({ onTag(tag) }, label = { Text("#$tag") }) } }
            if (api != null) post.attachments.forEach { SocialAttachmentCard(it, api, onOpenFile) }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onLike, enabled = enabled, contentPadding = PaddingValues(8.dp)) { Icon(if (post.reaction == "LIKE") Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp, "Like post", Modifier.size(18.dp)); Text(" ${post.totalLikes}") }
                TextButton(onDislike, enabled = enabled, contentPadding = PaddingValues(8.dp)) { Icon(if (post.reaction == "DISLIKE") Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown, "Dislike post", Modifier.size(18.dp)); Text(" ${post.totalDislikes}") }
                TextButton(onRepost, enabled = enabled && post.canRepost, contentPadding = PaddingValues(8.dp)) { Icon(Icons.Outlined.Repeat, "Repost", Modifier.size(18.dp), tint = if (post.isReposted) MaterialTheme.colorScheme.primary else LocalContentColor.current); Text(" ${post.totalReposts}") }
                TextButton(onComments, contentPadding = PaddingValues(8.dp)) { Icon(Icons.Outlined.ChatBubbleOutline, "Comments", Modifier.size(18.dp)); Text(" ${post.totalComments}") }
                Box { IconButton({ if (onSendToChat == null) onShare() else sharing = true }) { Icon(Icons.Outlined.Share, "Share post", Modifier.size(20.dp)) }
                    DropdownMenu(sharing, { sharing = false }) { DropdownMenuItem({ Text("Share link") }, { sharing = false; onShare() }); onSendToChat?.let { send -> DropdownMenuItem({ Text("Send in chat") }, { sharing = false; send() }) } }
                }
            }
        }
    }
}

@Composable
internal fun SocialAttachmentCard(attachment: SocialAttachment, api: OhioApiClient, onOpenFile: (FileItem) -> Unit) {
    val file = attachment.asFile()
    OutlinedCard(onClick = { onOpenFile(file) }, modifier = Modifier.fillMaxWidth()) {
        if (file.isImage && !file.passwordRequired) AsyncImage(model = api.getFileDownloadUrl(file.id, slug = file.slug), contentDescription = file.displayName, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 240.dp))
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.AttachFile, null); Text(file.displayName, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge, maxLines = 2) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostComposer(original: SocialPost?, attachments: List<SocialAttachment>, busy: Boolean, error: String?, api: OhioApiClient, onDismiss: () -> Unit, onSubmit: (String, String, List<String>) -> Unit) {
    var title by rememberSaveable(original?.id) { mutableStateOf(original?.title.orEmpty()) }
    var content by rememberSaveable(original?.id) { mutableStateOf(original?.content.orEmpty()) }
    var tags by rememberSaveable(original?.id) { mutableStateOf(original?.tags?.joinToString(", ").orEmpty()) }
    var picking by remember { mutableStateOf(false) }
    var preview by rememberSaveable(original?.id) { mutableStateOf(false) }
    val validTags = tags.split(',').map { it.trim().removePrefix("#") }.filter(String::isNotBlank)
    var discard by remember { mutableStateOf(false) }
    fun close() { if (title != original?.title.orEmpty() || content != original?.content.orEmpty() || tags != original?.tags?.joinToString(", ").orEmpty()) discard = true else onDismiss() }
    Dialog(onDismissRequest = { if (!busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(Modifier.fillMaxSize().imePadding(), topBar = { TopAppBar(title = { Text(if (original == null) "New post" else "Edit post") }, navigationIcon = { IconButton({ close() }, enabled = !busy) { Icon(Icons.Outlined.Close, "Close composer") } }, actions = { TextButton({ onSubmit(title, content, validTags) }, enabled = !busy && title.isNotBlank() && content.isNotBlank() && validTags.size <= 20 && validTags.all { it.length <= 40 }) { Text(if (busy) "Saving…" else if (original == null) "Publish" else "Save") } }) }) { padding ->
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text("Share a thought, a discovery, a little inspiration.", style = MaterialTheme.typography.titleMedium) }
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                item { OutlinedTextField(title, { title = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true, supportingText = { Text("${title.length}/120") }) }
                item { SingleChoiceSegmentedButtonRow { SegmentedButton(!preview, { preview = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Write") }; SegmentedButton(preview, { preview = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Preview") } } }
                if (preview) item { NativeMarkdownContent(content, api = api) }
                else item { OutlinedTextField(content, { content = it.take(2 * 1024 * 1024) }, Modifier.fillMaxWidth(), label = { Text("Your post · Markdown") }, minLines = 8, maxLines = 18) }
                item { OutlinedTextField(tags, { tags = it.take(820) }, Modifier.fillMaxWidth(), label = { Text("Tags, separated by commas") }, isError = validTags.size > 20 || validTags.any { it.length > 40 }, supportingText = { Text("Up to 20 tags, 40 characters each") }) }
                item { FilledTonalButton({ picking = true }) { Icon(Icons.Outlined.AttachFile, null); Text("Attach a public file") }; Text("Choose a reusable public file you own. Upload files from Workspace first.", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (picking) ModalBottomSheet(onDismissRequest = { picking = false }) { LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(20.dp)) { item { Text("Your public files", style = MaterialTheme.typography.titleLarge); if (attachments.isEmpty()) Text("No eligible files. Upload a public file in Workspace first.") }; items(attachments, key = { it.id }) { file -> ListItem(headlineContent = { Text(file.filename.ifBlank { file.slug }) }, supportingContent = { Text(file.mimeType.orEmpty()) }, leadingContent = { Icon(Icons.Outlined.AttachFile, null) }, modifier = Modifier.clickable { val embed = "![[ohiofile:${file.slug}]]"; if (!content.contains(embed)) content += "\n\n$embed"; picking = false }) } } }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") }, confirmButton = { TextButton(onDismiss) { Text("Discard") } }, dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
    }
}
