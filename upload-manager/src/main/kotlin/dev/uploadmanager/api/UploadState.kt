package dev.uploadmanager.api

/**
 * Lifecycle states of an upload task. Persisted in Room; spec §4.2 as amended by
 * revision docs 02 (adds [PARKED]) and 04 (removes the metadata-sync states).
 */
enum class UploadState {
    /** Task created and persisted. Not yet dispatched to WorkManager. */
    PENDING,

    /** WorkManager job enqueued. Awaiting execution window. */
    QUEUED,

    /** Transfer in progress. Session URI persisted once available. */
    UPLOADING,

    /** Paused by the host app. Held until [dev.uploadmanager.UploadManager.resume]. */
    PAUSED,

    /** Transient failure; WorkManager backoff in progress (fast tier). */
    RETRYING,

    /**
     * Fast retry tier exhausted. No active WorkRequest; re-dispatched on
     * connectivity change, charging, or the daily sweep until the task TTL expires.
     */
    PARKED,

    /** Upload succeeded; downloadUrl recorded. Terminal. */
    COMPLETED,

    /** Permanent error or task TTL exceeded. Terminal. */
    FAILED,

    /** Cancelled by the host app. Terminal. */
    CANCELLED,

    /** Content already present on the server; no bytes transferred. Terminal. (M2) */
    DEDUP_HIT;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == DEDUP_HIT
}
