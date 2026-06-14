package dev.uploadmanager.api

import dev.uploadmanager.retry.RetryPolicy

enum class NetworkPreference {
    /** All uploads wait for an unmetered network. */
    WIFI_ONLY,

    /** Any connected network; files larger than [UploadManagerConfig.cellularMaxBytes] still wait for WiFi. */
    ALLOW_CELLULAR,
}

data class UploadManagerConfig(
    val networkPreference: NetworkPreference = NetworkPreference.ALLOW_CELLULAR,
    /** Files larger than this require an unmetered network even with ALLOW_CELLULAR (spec §2.2). */
    val cellularMaxBytes: Long = 10L * 1024 * 1024,
    val maxConcurrentUploads: Int = 3,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    /** Persist progress to Room at most once per this many bytes (spec FR-11; revision 04 §5). */
    val progressIntervalBytes: Long = 512 * 1024L,
    /** Escalate to a foreground service for files larger than this. */
    val foregroundThresholdBytes: Long = 50L * 1024 * 1024,
    /** Source-file staging policy (revision doc 03). */
    val staging: StagingConfig = StagingConfig(),
    /** Content-hash deduplication policy (revision doc 01). */
    val dedup: DedupConfig = DedupConfig(),
    /** Firestore mirroring policy (revision doc 04). Default off. */
    val syncPolicy: SyncPolicy = SyncPolicy.NONE,
    /** Adapt concurrency to battery/thermal/network and pause in the heat (spec §10). */
    val adaptiveConcurrency: Boolean = true,
    /** Above this size, an upload is treated as "large" and held off cellular / low battery / heat. */
    val largeUploadThresholdBytes: Long = 50L * 1024 * 1024,
    /** Optional metrics sink (spec §13.2). */
    val metrics: UploadMetrics? = null,
    val enableLogging: Boolean = false,
) {
    init {
        require(maxConcurrentUploads >= 1) { "maxConcurrentUploads must be >= 1" }
        require(progressIntervalBytes > 0) { "progressIntervalBytes must be positive" }
    }
}
