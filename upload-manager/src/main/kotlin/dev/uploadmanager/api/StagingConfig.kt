package dev.uploadmanager.api

/**
 * Source-file staging policy (revision doc 03 §2).
 *
 * [REFERENCE] uploads directly from the host-supplied URI; files smaller than
 * [StagingConfig.autoCopyBelowBytes] are still staged as cheap insurance against
 * the URI going stale. [COPY] always snapshots the file into app-private storage
 * at enqueue, guaranteeing the bytes enqueued are the bytes uploaded.
 */
enum class StagingMode { REFERENCE, COPY }

data class StagingConfig(
    val mode: StagingMode = StagingMode.REFERENCE,
    /** In REFERENCE mode, files at or below this size are copied anyway. */
    val autoCopyBelowBytes: Long = 64L * 1024 * 1024,
    /** Hard cap on the staging directory; over budget, staging is skipped (falls back to REFERENCE). */
    val stagingDirMaxBytes: Long = 1024L * 1024 * 1024,
) {
    init {
        require(autoCopyBelowBytes >= 0) { "autoCopyBelowBytes must be >= 0" }
        require(stagingDirMaxBytes > 0) { "stagingDirMaxBytes must be positive" }
    }

    /** Whether a source of [sizeBytes] should be copied into staging at enqueue. */
    fun shouldStage(sizeBytes: Long): Boolean =
        mode == StagingMode.COPY || (sizeBytes in 0..autoCopyBelowBytes)
}
