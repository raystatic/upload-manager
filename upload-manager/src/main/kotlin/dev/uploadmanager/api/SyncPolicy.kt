package dev.uploadmanager.api

/**
 * Controls whether (and how much) task state is mirrored to Firestore
 * (revision doc 04). Room is always the source of truth; Firestore is a
 * write-behind, fire-and-forget projection that never blocks uploads.
 * `uploadedBytes` is never mirrored under any policy.
 *
 * The deduplication index ([DedupConfig]) is functional, not observational,
 * and is NOT governed by this policy.
 */
enum class SyncPolicy {
    /** Default. No Firestore task or file documents are written. */
    NONE,

    /** One `users/{uid}/files/{taskId}` record per task at completion / dedup-hit. */
    TERMINAL_ONLY,

    /** Terminal file record plus a `users/{uid}/uploadTasks/{taskId}` lifecycle projection. */
    FULL,
}
