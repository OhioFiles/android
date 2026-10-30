package cloud.ohiofiles.app.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import kotlinx.serialization.json.*

/** Fastify exposes fields parsed before the first file stream; metadata must be first. */
internal fun uploadMultipart(
    sessionKey: String,
    filename: String,
    file: RequestBody,
    isPublic: Boolean = false,
    isOneTime: Boolean = false,
    customSlug: String? = null,
    customFilename: String? = null,
    folderId: String? = null,
    password: String? = null
): MultipartBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
    addFormDataPart("sessionId", sessionKey)
    addFormDataPart("isPublic", isPublic.toString())
    addFormDataPart("isOneTime", isOneTime.toString())
    customSlug?.takeIf(String::isNotBlank)?.let { addFormDataPart("customSlug", it.trim()) }
    customFilename?.takeIf(String::isNotBlank)?.let { addFormDataPart("customFilename", it.trim()) }
    folderId?.takeIf(String::isNotBlank)?.let { addFormDataPart("folderId", it.trim()) }
    password?.takeIf(String::isNotBlank)?.let { addFormDataPart("password", it) }
    addFormDataPart("file", filename, file)
}.build()

internal fun apiErrorMessage(payload: String, fallback: String): String {
    val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return fallback
    val error = root["error"]
    fun JsonElement?.message(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf(String::isNotBlank)
        is JsonObject -> (get("message") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
        else -> null
    }
    return error.message() ?: root["message"].message() ?: fallback
}
