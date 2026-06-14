package dev.uploadmanager.api

sealed class UploadEvent {
    abstract val taskId: String

    data class Enqueued(override val taskId: String, val fileSizeBytes: Long) : UploadEvent()

    data class Started(override val taskId: String, val resuming: Boolean) : UploadEvent()

    data class Progress(
        override val taskId: String,
        val bytesTransferred: Long,
        val totalBytes: Long,
    ) : UploadEvent() {
        val pct: Int get() = if (totalBytes > 0) ((bytesTransferred * 100) / totalBytes).toInt() else 0
    }

    data class Paused(override val taskId: String) : UploadEvent()

    data class Resumed(override val taskId: String) : UploadEvent()

    data class Retrying(override val taskId: String, val retryCount: Int, val errorCode: String?) : UploadEvent()

    data class Parked(override val taskId: String, val parkedUntil: Long) : UploadEvent()

    data class Completed(override val taskId: String, val downloadUrl: String) : UploadEvent()

    /** Content already present for this user; no bytes transferred (revision doc 01). */
    data class DedupHit(override val taskId: String, val downloadUrl: String) : UploadEvent()

    data class Failed(override val taskId: String, val reason: String) : UploadEvent()

    data class Cancelled(override val taskId: String) : UploadEvent()
}
