package dev.uploadmanager.api

/** Public snapshot of a task, derived from the Room row. */
data class UploadTaskState(
    val taskId: String,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val uploadedBytes: Long,
    val state: UploadState,
    val priority: UploadPriority,
    val downloadUrl: String?,
    val errorCode: String?,
    val retryCount: Int,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val progressPct: Int
        get() = if (fileSizeBytes > 0) ((uploadedBytes * 100) / fileSizeBytes).toInt() else 0
}
