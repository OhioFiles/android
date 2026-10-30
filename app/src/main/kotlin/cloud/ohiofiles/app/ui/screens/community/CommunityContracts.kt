package cloud.ohiofiles.app.ui.screens.community

import cloud.ohiofiles.app.data.api.OhioApiClient
import cloud.ohiofiles.app.data.model.FileListResponse
import cloud.ohiofiles.app.data.model.SocialPost
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Public discovery deliberately excludes account credentials: the server scopes credentialed requests to owned files. */
internal fun communityPublicFileQuery(page: Int): Map<String, String?> = mapOf("public" to "true", "page" to page.coerceAtLeast(1).toString(), "limit" to "100")
internal fun communityHasMoreFiles(page: FileListResponse) = page.hasMore || page.page < page.totalPages || page.page.toLong() * page.limit < page.total
internal fun fullCommunityPost(api: OhioApiClient, payload: JsonElement): SocialPost? = payload.jsonObject["post"]?.let { api.json.decodeFromJsonElement<SocialPost>(it) }
