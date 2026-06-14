package dev.uploadmanager.api

/**
 * Optional sink for the key SDK metrics (spec §13.2). Plug an implementation into
 * [UploadManagerConfig.metrics] to feed your analytics/monitoring. All callbacks
 * have no-op defaults, are invoked off the main thread, and must not block.
 */
interface UploadMetrics {
    fun onCompleted(taskId: String, durationMs: Long, bytesTransferred: Long) {}
    fun onDedupHit(taskId: String) {}
    fun onRetry(taskId: String, retryCount: Int, errorCode: String?) {}
    fun onParked(taskId: String) {}
    fun onFailed(taskId: String, errorCode: String?) {}
}
