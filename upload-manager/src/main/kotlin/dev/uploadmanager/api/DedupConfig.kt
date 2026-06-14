package dev.uploadmanager.api

/**
 * Deduplication policy (revision doc 01). Dedup is keyed on the SHA-256 of file
 * content and scoped per-uid: the index lives at
 * `users/{uid}/checksumIndex/{checksum}` in Firestore and is enforced by
 * owner-only security rules — no Cloud Functions required.
 *
 * Dedup applies to files that have a checksum by the time they upload. Staged
 * files (the default for sizes up to [StagingConfig.autoCopyBelowBytes]) are
 * hashed for free during staging; larger un-staged files skip dedup in this
 * version. All Firestore access degrades gracefully — a failed check or index
 * write never blocks or fails an upload.
 */
data class DedupConfig(
    val enabled: Boolean = true,
)
