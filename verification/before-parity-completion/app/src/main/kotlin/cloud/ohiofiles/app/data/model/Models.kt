package cloud.ohiofiles.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class FileItem(
    val id: String,
    val slug: String,
    val filename: String = "",
    val originalName: String? = null,
    val size: Long = 0L,
    val mimeType: String? = null,
    val uploadedAt: String? = null,
    val isPublic: Boolean = false,
    val isOneTime: Boolean = false,
    val oneTimeConsumed: Boolean = false,
    val passwordRequired: Boolean = false,
    val passwordUnlocked: Boolean = false,
    val downloads: Int = 0,
    val folderId: String? = null,
    val folderName: String? = null,
    val folderPath: String? = null,
    val isOwner: Boolean = false,
    val isShared: Boolean = false,
    val shareAccess: String? = null,
    val access: String? = null,
    val readOnly: Boolean = false,
    val isSaved: Boolean = false,
    val publicSessionNickname: String? = null,
    val publicSessionProfileSlug: String? = null,
    val publicSessionAvatarUrl: String? = null,
    val url: String? = null
) {
    val canEdit: Boolean get() = isOwner || shareAccess == "EDIT" || access == "EDIT"
    val displayName: String
        get() = originalName?.ifBlank { filename } ?: filename.ifBlank { slug }

    val formattedSize: String
        get() {
            if (size <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 4)
            val value = size / Math.pow(1024.0, digitGroups.toDouble())
            return String.format("%.1f %s", value, units[digitGroups])
        }

    val isImage: Boolean
        get() = mimeType?.startsWith("image/") == true ||
                displayName.endsWith(".png", true) ||
                displayName.endsWith(".jpg", true) ||
                displayName.endsWith(".jpeg", true) ||
                displayName.endsWith(".webp", true) ||
                displayName.endsWith(".gif", true) ||
                displayName.endsWith(".svg", true)

    val isVideo: Boolean
        get() = mimeType?.startsWith("video/") == true ||
                displayName.endsWith(".mp4", true) ||
                displayName.endsWith(".webm", true) ||
                displayName.endsWith(".mkv", true) ||
                displayName.endsWith(".mov", true)

    val isAudio: Boolean
        get() = mimeType?.startsWith("audio/") == true ||
                displayName.endsWith(".mp3", true) ||
                displayName.endsWith(".wav", true) ||
                displayName.endsWith(".ogg", true) ||
                displayName.endsWith(".m4a", true) ||
                displayName.endsWith(".flac", true)

    val isPdf: Boolean
        get() = mimeType == "application/pdf" || displayName.endsWith(".pdf", true)

    val isTextOrCode: Boolean
        get() = mimeType?.startsWith("text/") == true ||
                displayName.endsWith(".txt", true) ||
                displayName.endsWith(".md", true) ||
                displayName.endsWith(".json", true) ||
                displayName.endsWith(".kt", true) ||
                displayName.endsWith(".java", true) ||
                displayName.endsWith(".py", true) ||
                displayName.endsWith(".js", true) ||
                displayName.endsWith(".ts", true) ||
                displayName.endsWith(".html", true) ||
                displayName.endsWith(".css", true) ||
                displayName.endsWith(".xml", true) ||
                displayName.endsWith(".sh", true)
}

@Serializable
data class FileListResponse(
    val success: Boolean = true,
    val files: List<FileItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val limit: Int = 20,
    val totalPages: Int = 1,
    val hasMore: Boolean = false
)

@Serializable
data class FolderStats(
    val childCount: Int = 0,
    val fileCount: Int = 0
)

@Serializable
data class FolderItem(
    val id: String,
    val name: String,
    val slug: String? = null,
    val shortCode: String? = null,
    val isPublic: Boolean = false,
    val hasPassword: Boolean = false,
    val passwordRequired: Boolean = false,
    val parentId: String? = null,
    val createdAt: String? = null,
    val isShared: Boolean = false,
    val shareAccess: String? = null,
    val stats: FolderStats? = null
) {
    val childCount: Int get() = stats?.childCount ?: 0
    val fileCount: Int get() = stats?.fileCount ?: 0
}

@Serializable
data class FolderCreateResponse(
    val success: Boolean = true,
    val folder: FolderItem? = null
)

@Serializable
data class FolderListResponse(
    val success: Boolean = true,
    val path: List<FolderPathItem>? = null,
    val currentFolder: FolderItem? = null,
    val folders: List<FolderItem> = emptyList()
)

@Serializable
data class FolderPathItem(
    val id: String,
    val name: String? = null
)

@Serializable
data class PublicSession(
    val id: String,
    val key: String,
    val nickname: String? = null,
    val profileSlug: String? = null,
    val createdAt: String? = null,
    val lastActive: String? = null,
    val expiresAt: String? = null,
    val fileCount: Int = 0,
    val avatarUrl: String? = null,
    val mood: String? = null,
    val statusText: String? = null
)

@Serializable
data class PublicSessionResponse(
    val success: Boolean = true,
    val session: PublicSession
)

@Serializable
data class UploadedFileItem(
    val id: String? = null,
    val slug: String? = null,
    val filename: String? = null,
    val size: Long = 0L,
    val uploadedAt: String? = null,
    val isPublic: Boolean = false,
    val isOneTime: Boolean = false,
    val url: String? = null
)

@Serializable
data class UploadResponse(
    val id: String? = null,
    val slug: String? = null,
    val originalName: String? = null,
    val filename: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L,
    val isPublic: Boolean = false,
    val isOneTime: Boolean = false,
    val url: String? = null,
    val error: String? = null,
    val message: String? = null,
    val file: UploadedFileItem? = null
) {
    val finalId: String? get() = file?.id ?: id
    val finalSlug: String? get() = file?.slug ?: slug
    val finalFilename: String? get() = file?.filename ?: filename ?: originalName
    val finalUrl: String? get() = file?.url ?: url
}

@Serializable
data class SocialReactions(
    val likes: Int = 0,
    val dislikes: Int = 0
)

@Serializable
data class SocialPost(
    val id: String,
    val slug: String,
    val title: String,
    val content: String,
    val format: String = "MARKDOWN",
    val owner: Boolean = false,
    val canRepost: Boolean = true,
    val author: SocialAuthor? = null,
    val tags: List<String> = emptyList(),
    val reactions: SocialReactions? = null,
    val likesCount: Int = 0,
    val dislikesCount: Int = 0,
    val commentCount: Int = 0,
    val commentsCount: Int = 0,
    val repostCount: Int = 0,
    val repostsCount: Int = 0,
    val viewerReaction: String? = null,
    val userReaction: String? = null,
    val viewerReposted: Boolean = false,
    val userReposted: Boolean = false,
    val createdAt: String? = null,
    val attachments: List<SocialAttachment> = emptyList()
) {
    val totalLikes: Int get() = reactions?.likes ?: likesCount
    val totalDislikes: Int get() = reactions?.dislikes ?: dislikesCount
    val totalComments: Int get() = if (commentCount > 0) commentCount else commentsCount
    val totalReposts: Int get() = if (repostCount > 0) repostCount else repostsCount
    val reaction: String? get() = viewerReaction ?: userReaction
    val isReposted: Boolean get() = viewerReposted || userReposted
}

@Serializable
data class SocialPostsResponse(
    val posts: List<SocialPost> = emptyList(),
    val nextCursor: String? = null
)

@Serializable
data class CreatePostResponse(
    val post: SocialPost
)

@Serializable
data class SocialAuthor(
    val id: String,
    val nickname: String? = null,
    val profileSlug: String? = null,
    val avatarUrl: String? = null
)

@Serializable
data class SocialAttachment(
    val id: String,
    val slug: String,
    val filename: String = "",
    val size: Long = 0L,
    val isPublic: Boolean = true,
    val passwordRequired: Boolean = false,
    val originalName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L
)

@Serializable
data class SocialComment(
    val id: String,
    val body: String,
    val author: SocialAuthor? = null,
    val createdAt: String? = null,
    val likesCount: Int = 0
)

@Serializable
data class GenericSuccessResponse(
    val success: Boolean = true,
    val message: String? = null,
    val error: String? = null
)
