package cloud.ohiofiles.app.data.upload

enum class UploadPhase { QUEUED, PREPARING, TRANSFERRING, FINALIZING, RETRYING }

internal fun uploadPhase(workState: String, reportedPhase: String?, attempts: Int, fraction: Float): UploadPhase {
    if (workState == "ENQUEUED" || workState == "BLOCKED") return if (attempts > 0) UploadPhase.RETRYING else UploadPhase.QUEUED
    if (reportedPhase == UploadPhase.FINALIZING.name || fraction >= 1f) return UploadPhase.FINALIZING
    return UploadPhase.entries.firstOrNull { it.name == reportedPhase } ?: UploadPhase.TRANSFERRING
}
