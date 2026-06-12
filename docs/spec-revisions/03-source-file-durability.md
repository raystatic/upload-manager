# Revision 03 — Source File Durability & Staging

> **New section** (no v1.0 counterpart — this gap was itself the finding).
> **Amends:** §4.1 (UploadTask entity fields), §6.1 (pre-upload/pre-resume validation), §11.1 (config surface).

## 1. The gap

The v1.0 draft persisted `localUri: String` in Room and assumed it stays readable and byte-stable from enqueue until upload completion — potentially days later for P4 backfill tasks, across process death and reboots. On real devices that assumption fails in three independent ways:

| Failure | Cause | Consequence in v1.0 design |
| --- | --- | --- |
| **URI grant expires** | URIs from `ACTION_GET_CONTENT`, share intents, and most third-party providers carry transient permission grants that die with the process or the grant window | `SecurityException` on open. The task retries forever against a URI that will never be readable again. |
| **File deleted or moved** | User clears camera roll, a cleaner app prunes storage, MediaStore reorganises | `FileNotFoundException`. Same infinite-retry trap. |
| **File modified in place** | User edits a photo, app rewrites a document | Worst case: resuming a Storage session against changed bytes produces a **silently corrupt object** — first N bytes from the old file, the rest from the new one. Also poisons the dedup index: the enqueue-time hash now maps to content that was never uploaded. |

For a spec that promises "no data loss" and a content-hash dedup index, source stability cannot be assumed; it must be either **secured** (persistable permission), **snapshotted** (staged copy), or **verified** (fingerprint check) — and the failure mode must be a first-class, host-visible error rather than corruption or an infinite retry loop.

## 2. Staging modes

```kotlin
enum class StagingMode {
    REFERENCE,   // default — upload directly from the source URI, with validation (§4)
    COPY,        // snapshot to app-private storage at enqueue; upload from the copy
}

data class StagingConfig(
    val mode: StagingMode = StagingMode.REFERENCE,
    val autoCopyBelowBytes: Long = 64L * 1024 * 1024, // REFERENCE mode auto-stages small files
    val stagingDirMaxBytes: Long = 1024L * 1024 * 1024, // 1 GB budget for the staging dir
)
```

**`REFERENCE`** (default): the SDK uploads from the host-supplied URI.

- At enqueue, the SDK calls `takePersistableUriPermission()` when the URI supports it (SAF / `ACTION_OPEN_DOCUMENT` URIs); the success/failure of that call is recorded so observability can correlate later failures with non-persistable grants.
- Files smaller than `autoCopyBelowBytes` are staged anyway (cheap insurance — copying 5 MB is faster than diagnosing a stale-URI failure).
- The integration guide must state plainly: with non-persistable URIs and large files, durability is best-effort, and `COPY` is the recommended mode for "must not lose this" use cases.

**`COPY`**: at enqueue, the file is streamed to `context.filesDir/upload_staging/{taskId}` and the task's effective source becomes that path. This trades disk for a hard guarantee: the bytes that were enqueued are the bytes that get uploaded, regardless of what happens to the original.

- The copy and the Room insert are ordered copy-first, so a task row never references a staging path that doesn't exist.
- If the staging directory would exceed `stagingDirMaxBytes`, `enqueue()` falls back to `REFERENCE` for the overflow file and reports it in the returned handle (never throws for this).
- SHA-256 is computed **during** the staging copy in the same stream pass — staging makes the Revision 01 hashing cost effectively free for staged files.

## 3. Entity fields (amends §4.1)

```kotlin
// Added to UploadTask:
val sourceSizeBytes: Long,        // fingerprint, captured at enqueue
val sourceLastModified: Long,     // fingerprint, captured at enqueue (0 if provider doesn't expose it)
val stagedPath: String?,          // non-null when staged; upload reads this, not localUri
val persistablePermission: Boolean,
```

## 4. Validation — before every upload start and every resume

The worker validates the source before transferring any byte, and again before resuming a session:

```kotlin
sealed class SourceCheck {
    object Ok : SourceCheck()
    object Gone : SourceCheck()      // unreadable: deleted, moved, or grant expired
    object Changed : SourceCheck()   // readable, but size/lastModified differ from enqueue
}
```

| Result | On fresh upload | On resume (session URI exists) |
| --- | --- | --- |
| `Ok` | proceed | proceed |
| `Gone` | task → `FAILED`, `errorCode = SOURCE_GONE` | same |
| `Changed` | re-fingerprint + re-hash, then proceed (the *current* content is uploaded and indexed — fingerprint update keeps the dedup index honest) | **never resume** — clear session URI atomically (Revision 02 §4), reset `uploadedBytes`, restart from byte 0 with re-hash |

`SOURCE_GONE` and `SOURCE_CHANGED` are emitted as structured events (`sdk.upload.failed` / `sdk.upload.source_changed`) and surfaced through `UploadEvent.Failed(reason)` so host apps can prompt the user instead of showing a generic failure. Neither is retried — retrying cannot fix a deleted file, and this closes the infinite-retry trap.

The fingerprint (size + lastModified) is deliberately cheap — it runs on every attempt. A full re-hash happens only on `Changed` or when Revision 01 requires the authoritative hash anyway. A byte-identical rewrite that changes only `lastModified` costs one redundant re-hash; a content change that preserves both size and mtime defeats the fingerprint, which is why `COPY` mode exists for correctness-critical uploads.

## 5. Staged-copy cleanup

- Staged files are deleted when their task reaches any terminal state (`COMPLETED`, `FAILED`, `CANCELLED`, `DEDUP_HIT`) and on `clearCompleted()`.
- A startup reconciliation pass deletes orphaned staging files (present on disk, no matching task row) — protects against crashes between file-delete and row-update.
- `sdk.staging.bytes_used` is reported as a gauge metric (§13) so adopters can see the disk cost of `COPY` mode in the field.
