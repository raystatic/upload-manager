# Revision 04 — Opt-in Firestore Sync

> **Replaces:** the "users/{uid}/uploadTasks mirrors Room DB state" design in §7.1–§7.2.
> **Amends:** FR-09 (§2.1 — metadata ordering is reversed), §3.2 steps 5–6 (MetadataSync no longer precedes the binary), §5.2 (MetadataSyncWorker), §4.2 (`METADATA_SYNCING`/`METADATA_SYNCED` states removed).

## 1. The cost problem

The v1.0 draft mirrored every task's full lifecycle to Firestore: a pre-upload metadata write, a state write per transition (≥ 5 per happy-path upload), plus `uploadedBytes` updates. Firestore bills **per document write**, and the bill lands on the adopting developer:

| Design | Billed writes per upload (happy path) | 10k DAU × 50 uploads/day |
| --- | --- | --- |
| v1.0 (full mirror incl. progress) | ~8–20+ (unbounded with progress) | 4–10 M+ writes/day |
| `SyncPolicy.FULL` (this revision, no progress) | ~4 | 2 M writes/day |
| `SyncPolicy.TERMINAL_ONLY` | 1 | 500 k writes/day |
| `SyncPolicy.NONE` (default) | **0** | 0 |

For the flagship use case — camera-roll-style backup — the v1.0 default would be the single largest line item on an adopter's Firebase bill, paying for a benefit ("cross-device visibility" of the queue) that is mostly illusory: the queue rows reference **device-local content URIs** that no other device can read, act on, or resume. A second device can *observe* that a task exists; it can do nothing with it.

This revision makes Room the *only* mandatory store and turns Firestore sync into a tiered opt-in.

## 2. `SyncPolicy`

```kotlin
enum class SyncPolicy {
    NONE,           // default. No uploadTasks documents are ever written.
    TERMINAL_ONLY,  // one write per task, at COMPLETED / DEDUP_HIT / FAILED / CANCELLED
    FULL,           // terminal write + transition writes (PENDING→UPLOADING→…). Never progress bytes.
}

// UploadManagerConfig gains:
val syncPolicy: SyncPolicy = SyncPolicy.NONE
```

Invariants, regardless of policy:

- **`uploadedBytes` is never mirrored.** Progress belongs to the local `observe()` Flow (§11.3); putting a per-progress-event write behind a billed, rate-limited API (1 sustained write/sec/document) is a category error.
- Sync is **fire-and-forget and write-behind**: a Firestore outage or quota exhaustion never blocks, fails, or delays an upload. Failed sync writes ride Firestore's offline-persistence queue; the SDK does not retry them itself.
- The Room row remains the source of truth; Firestore documents are a projection.

The `checksumIndex` writes defined in Revision 01 are **not** governed by `SyncPolicy` — they are functional (dedup correctness), not observational, and amount to one write per unique content.

## 3. FR-09 reversed: metadata at completion

v1.0's FR-09 ("metadata is uploaded to Firestore separately from **and before** the binary") existed to support the global dedup index and cross-device visibility. With Revision 01 making dedup per-uid and index writes self-contained, the pre-write has no remaining consumer — but it still has a real cost: every failed, cancelled, TTL-expired, or source-gone task leaves an **orphaned metadata record** that any Firestore consumer (admin dashboards, Cloud Function triggers, the host app's own queries) sees as a phantom file that never arrived. At a 99.9% success target, that is 1 phantom per 1 000 uploads, forever, with no specified cleanup.

Revised requirement:

> **FR-09 (revised):** The file metadata record is written to Firestore **upon successful upload completion** (or dedup hit), in the same logical step that records the `downloadUrl`. A Firestore record implies the binary exists in Storage.

Consequences:

- `METADATA_SYNCING` / `METADATA_SYNCED` are **removed from the state machine** (§4.2); `MetadataSyncWorker` (§5.2) is deleted. The completion handler performs the single metadata write.
- The "record implies binary exists" invariant is what makes downstream consumers trivial: a Cloud Function `onCreate` trigger for thumbnails (§15.2) no longer needs to distinguish pending from complete.
- Host apps that genuinely need *pending* uploads visible remotely opt into `SyncPolicy.FULL` and accept the documented residue: the integration guide ships a reference cleanup query (delete non-terminal `uploadTasks` docs older than `taskTtl`) that adopters can run as a scheduled function or admin script. The SDK itself does not require it.

## 4. Resulting Firestore schema

```text
firestore/
  users/{uid}/
    files/{taskId}/              <- written at completion only (SyncPolicy ≥ TERMINAL_ONLY)
      storagePath, checksum, sizeBytes, mimeType, originalFilename,
      downloadUrl, uploadedAt: Timestamp, appMetadata: Map<String,String>
    uploadTasks/{taskId}/        <- SyncPolicy.FULL only; lifecycle projection, no progress
    checksumIndex/{checksum}/    <- Revision 01; functional, always on when dedup enabled
```

(The completed-file record moves to a `files` collection to make the common query — "what has this user uploaded" — a single collection read that is not polluted by lifecycle rows.)

Security rules: all three collections sit under `users/{uid}/` and use the same owner-only rule from Revision 01 §5 — no new rule surface.

## 5. Data flow (§3.2, revised steps 5–10)

1–4. *(unchanged: enqueue → Room persist → scheduler → worker dispatch)*
5. Worker validates the source (Revision 03) and resolves the content hash per Revision 01.
6. DeduplicationEngine check — on hit: state → `DEDUP_HIT`, write `files/{taskId}` record (policy permitting), increment index `refCount`, done.
7. `putFile()` to the content-addressed path; session URI + `sessionCreatedAt` persisted atomically on first snapshot.
8. Progress → Room (throttled — e.g. every 512 KB, not every snapshot) and the local `observe()` Flow. **Nothing to Firestore.**
9. On success: Room state → `COMPLETED`; write `files/{taskId}` + `checksumIndex/{checksum}`; emit events.
10. On failure: RetryClassifier per Revision 02.
