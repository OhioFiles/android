package cloud.ohiofiles.app.ui.screens.community

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileItem
import cloud.ohiofiles.app.data.model.SocialPost
import cloud.ohiofiles.app.data.repository.SessionRepository
import cloud.ohiofiles.app.ui.document.rememberNativeMarkdownDocument
import cloud.ohiofiles.app.ui.document.nativeMarkdownItems
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialDetailScreen(post: SocialPost, api: OhioApiClient, sessions: SessionRepository, onOpenFile: (FileItem) -> Unit = {}, onOpenProfile: ((String) -> Unit)? = null, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var fullPost by remember(post.id) { mutableStateOf(post) }
    val markdown = rememberNativeMarkdownDocument(fullPost.content.replace(Regex("!\\[\\[ohiofile:[^]]+]]"), ""))
    var sessionKey by remember { mutableStateOf<String?>(null) }
    var comments by remember(post.id) { mutableStateOf(emptyList<JsonObject>()) }
    var draft by rememberSaveable(post.id) { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    suspend fun load() {
        sessionKey = sessions.getSessionKey()
        api.request("social/posts/${post.slug}", query = mapOf("sessionKey" to sessionKey)).onSuccess { response -> fullCommunityPost(api, response)?.let { fullPost = it }; comments = response.jsonObject["comments"]?.jsonArray?.map { item -> item.jsonObject }.orEmpty() }.onFailure { error = it.message }
        loading = false
    }
    fun action(comment: JsonObject, reaction: String) { scope.launch {
        if (sending) return@launch
        sending = true
        try {
            api.request("social/posts/${post.id}/comments/${comment.text("id")}/reaction", "PUT", api.body("sessionKey" to sessions.getSessionKey(), "reaction" to if (comment.text("viewerReaction") == reaction) null else reaction)).onSuccess { load() }.onFailure { error = it.message }
        } finally { sending = false }
    } }
    LaunchedEffect(post.id) { load() }
    BackHandler(onBack = onBack)
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = { TopAppBar(title = { Text("Conversation") }, navigationIcon = { IconButton(onBack) { Icon(Icons.Outlined.ArrowBack, "Back") } }, actions = { IconButton({ scope.launch { loading = true; load() } }) { Icon(Icons.Outlined.Refresh, "Refresh conversation") } }) }, bottomBar = {
        Surface(tonalElevation = 3.dp) { Column(Modifier.imePadding().padding(12.dp)) {
            replyTo?.let { parent -> Row(Modifier.fillMaxWidth()) { Text("Replying to " + (parent["author"] as? JsonObject)?.text("nickname").orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); IconButton({ replyTo = null }, Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "Cancel reply", Modifier.size(18.dp)) } } }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(draft, { draft = it.take(1600) }, placeholder = { Text("Add to the conversation") }, modifier = Modifier.weight(1f), maxLines = 4, enabled = !sending)
                FilledIconButton(enabled = draft.isNotBlank() && !sending, onClick = { scope.launch {
                    sending = true
                    try {
                        val payload = api.body("sessionKey" to sessions.getSessionKey(), "body" to draft.trim()).toMutableMap()
                        replyTo?.text("id")?.let { payload["parentId"] = JsonPrimitive(it) }
                        api.request("social/posts/${post.id}/comments", "POST", JsonObject(payload)).onSuccess { draft = ""; replyTo = null; load() }.onFailure { error = it.message }
                    } finally { sending = false }
                } }) { Icon(Icons.Outlined.Send, "Send comment") }
            }
        } }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(fullPost.title, style = MaterialTheme.typography.headlineMedium)
                if (onOpenProfile != null && !post.author?.profileSlug.isNullOrBlank()) TextButton({ post.author?.profileSlug?.let(onOpenProfile) }) { Icon(Icons.Outlined.Person, null, Modifier.size(18.dp)); Text(" ${post.author?.nickname ?: "View author"}") }
            }
            markdown?.let { nativeMarkdownItems(it, api, sessionKey, onOpenFile, keyPrefix = "post-${post.id}") }
                ?: item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            items(fullPost.attachments, key = { "attachment-${it.id}" }) { SocialAttachmentCard(it, api, onOpenFile) }
            item { Text("${comments.size} comments", style = MaterialTheme.typography.titleMedium); if (loading) LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!loading && comments.isEmpty()) item { Text("Start a conversation.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val ids = comments.map { it.text("id") }.toSet()
            val roots = comments.filter { it.text("parentId").isBlank() || it.text("parentId") !in ids }
            roots.forEach { root ->
                val thread = listOf(root) + comments.filter { it.text("parentId") == root.text("id") }
                items(thread, key = { "comment-${it.text("id")}" }) { comment ->
                    val isReply = comment !== root
                    Card(Modifier.padding(start = if (isReply) 22.dp else 0.dp), colors = CardDefaults.cardColors(containerColor = if (isReply) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.padding(14.dp).fillMaxWidth()) {
                        Text((comment["author"] as? JsonObject)?.text("nickname") ?: "OhioFiles member", style = MaterialTheme.typography.labelLarge)
                        Text(comment.text("body"), Modifier.padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton({ action(comment, "LIKE") }, enabled = !sending, contentPadding = PaddingValues(6.dp)) { Icon(Icons.Outlined.ThumbUp, "Like comment", Modifier.size(17.dp)); Text(" ${(comment["reactions"] as? JsonObject)?.text("likes") ?: "0"}") }
                            TextButton({ action(comment, "DISLIKE") }, enabled = !sending, contentPadding = PaddingValues(6.dp)) { Icon(Icons.Outlined.ThumbDown, "Dislike comment", Modifier.size(17.dp)); Text(" ${(comment["reactions"] as? JsonObject)?.text("dislikes") ?: "0"}") }
                            TextButton({ replyTo = comment }, contentPadding = PaddingValues(6.dp)) { Text("Reply") }
                            if (comment["owner"]?.jsonPrimitive?.booleanOrNull == true || post.owner) IconButton({ deleting = comment }, enabled = !sending) { Icon(Icons.Outlined.Delete, "Delete comment", Modifier.size(18.dp)) }
                        }
                    } }
                }
            }
        }
    }
    deleting?.let { comment -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete comment?") }, text = { Text("Replies to this comment will also be removed.") }, confirmButton = { TextButton({ deleting = null; scope.launch { sending = true; try { api.request("social/posts/${post.id}/comments/${comment.text("id")}", "DELETE", api.body("sessionKey" to sessions.getSessionKey())).onSuccess { load() }.onFailure { error = it.message } } finally { sending = false } } }) { Text("Delete") } }, dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } }) }
}
