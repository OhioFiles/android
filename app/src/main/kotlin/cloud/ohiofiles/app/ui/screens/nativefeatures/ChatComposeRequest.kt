package cloud.ohiofiles.app.ui.screens.nativefeatures

/** A native share link opens a preview. It never sends until the person taps Send. */
data class ChatComposeRequest(val recipientNickname: String? = null, val recipientId: String? = null, val groupId: String? = null, val postSlug: String? = null, val fileIdOrSlug: String? = null, val draft: String? = null)
internal val blobatarMoods = listOf("happy", "love", "smug", "wink", "surprised", "thinking", "shy", "sleepy", "unsure", "sad", "scared", "mad", "sick", "idle")
internal fun blobatarMood(body: String): String? {
    if (!body.startsWith("[sticker:blobatar:") || !body.endsWith("]")) return null
    return body.removePrefix("[sticker:blobatar:").removeSuffix("]").trim().lowercase().takeIf { it in blobatarMoods }
}
internal fun blobatarMessage(mood: String): String { require(mood in blobatarMoods); return "[sticker:blobatar:$mood]" }
