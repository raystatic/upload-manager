# Revision 01 — Per-uid Deduplication

> **Replaces:** §8 (Deduplication Engine) in full; the `checksumIndex` collection in §7.1; the "Separate checksumIndex collection" decision in §7.2; the `checksumIndex` rules in §7.3.
> **Amends:** §6.1 Step 1 (Storage path scheme), FR-08 (§2.1), OQ-01 and OQ-03 (§15.1 — both resolved by this revision).

## 1. Why the global `checksumIndex` is removed

The v1.0 draft specified a single, global `checksumIndex/{checksum}` collection readable by all authenticated users and writable only by Cloud Functions. Four problems stack on top of each other:

| # | Problem | Consequence |
| --- | --- | --- |
| 1 | **File-existence oracle.** Any authenticated user can hash a suspected file and probe `checksumIndex/{hash}`. | Confirms whether *any* user on the platform possesses a given file. Privacy leak with no mitigation inside the security-rules model. |
| 2 | **Cross-user content access.** A dedup hit returns the `downloadUrl` of an object owned by another uid. Firebase download URLs are bearer tokens that **bypass Storage security rules** entirely. | Defeats the §7.4 rule that only the owner may read `users/{uid}/files/*`. Actual data exfiltration via hash. |
| 3 | **No ownership lifecycle.** The binary lives under the *first* uploader's path. Owner deletes the file / deletes their account / GDPR erasure → every dedup reference across the platform dangles. | Requires cross-user reference counting and garbage collection — unspecified, and genuinely hard to build correctly on Firestore + Storage. |
| 4 | **Breaks plug-and-play.** Index writes require the Admin SDK, so every adopter must deploy and operate our Cloud Functions on their own Firebase project. | The SDK is no longer a client library; it is a backend deployment. The function was also never specified. |
| 5 | **TOCTOU race.** Check-then-upload is not atomic: two devices uploading the same new file both see `New`, both upload, then race to write the index. | Duplicate objects; the dedup guarantee silently fails exactly when it is needed. |

**Resolution of OQ-03:** dedup is **per-uid** in v1 (same user, any of their devices). Global dedup is demoted to opt-in future work (§6 below).
**Resolution of OQ-01:** with a per-uid index, client-side writes are safe under pure security rules. **No Cloud Functions are required.** Plug-and-play is preserved.

## 2. Content-addressed storage paths

The single highest-leverage change: the Storage object path is derived from the content hash, not the task id.

```kotlin
// Before (v1.0 draft):  users/{uid}/files/{taskId}
// After:                users/{uid}/files/{checksum}
val storageRef = FirebaseStorage.getInstance()
    .reference
    .child("users/$uid/files/${task.checksum}")
```

This makes deduplication **idempotent by construction** and eliminates the TOCTOU race without transactions:

- Two devices of the same user uploading the same content concurrently target the *same object path*. Whoever finishes second overwrites the first with byte-identical content. One object exists either way; both tasks complete.
- The index write (§4) is a plain upsert — last-writer-wins is correct because all writers describe the same immutable content.
- A missed or failed index write degrades gracefully: the next upload of the same content re-uploads (wasting bandwidth, not correctness) and repairs the index.

Task identity (`taskId`) remains the key for the local queue and for `uploadTasks` metadata documents; multiple tasks may point at one storage object.

## 3. Hashing strategy

The v1.0 draft hashed every file before any byte was uploaded. SHA-256 over a multi-GB video is tens of seconds of sustained I/O + CPU on a mid-range device, which directly contradicts the §2.2 latency NFR (*"P0 uploads begin within 2 seconds"*) and the battery budget.

| File size | Strategy |
| --- | --- |
| ≤ `hashInlineThresholdBytes` (default **32 MB**) | Hash synchronously in the worker before upload. Dedup check runs before any byte is transferred (full bandwidth saving). |
| > threshold | **Start the upload immediately** at a provisional path `users/{uid}/staging/{taskId}`; compute SHA-256 in parallel on a background dispatcher. When the hash completes: if the index already contains it, cancel/discard the in-flight upload (dedup hit, partial bandwidth saving); otherwise, on upload completion, the worker finalises by writing the index entry and recording the content-addressed path. |

```kotlin
data class DedupConfig(
    val enabled: Boolean = true,
    val hashInlineThresholdBytes: Long = 32L * 1024 * 1024,
)
```

Notes:

- Hash is computed via streaming `DigestInputStream` (constant memory), never by loading the file into memory.
- The hash recorded at enqueue time is **advisory only**; the authoritative hash is computed (or re-verified via the source fingerprint — see Revision 03) in the worker at upload time. This prevents a file edited between enqueue and upload from poisoning the index with a wrong content→hash mapping.
- P0 tasks skip the inline dedup check entirely if no hash is available yet — latency wins over bandwidth for the highest priority tier.

## 4. Firestore index schema

The index moves inside the user namespace:

```text
firestore/
  users/{uid}/
    uploadTasks/{taskId}/        <- unchanged (see Revision 04 for sync policy)
    checksumIndex/{checksum}/
      checksum:    String        // SHA-256, hex
      storagePath: String        // users/{uid}/files/{checksum}
      sizeBytes:   Long
      mimeType:    String
      refCount:    Int           // number of uploadTasks referencing this object
      createdAt:   Timestamp
      updatedAt:   Timestamp
```

- `refCount` is best-effort bookkeeping for the SDK's `delete(taskId)` path: decrement on task deletion; delete the Storage object and index doc when it reaches 0. Because everything is inside one uid's namespace, a wrong refCount harms only that user's own storage bill, never another user's data.
- `downloadUrl` is **not** stored in the index. Dedup hits resolve the URL on demand via `storageRef.downloadUrl` so that revocation works.

### Dedup flow (replaces §8.2)

```kotlin
class DeduplicationEngine(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
) {
    suspend fun check(checksum: String): DedupResult {
        val uid = auth.currentUser?.uid ?: return DedupResult.New
        val doc = firestore
            .collection("users").document(uid)
            .collection("checksumIndex").document(checksum)
            .get()
            .await()
        return if (doc.exists()) {
            DedupResult.Duplicate(storagePath = doc.getString("storagePath")!!)
        } else {
            DedupResult.New
        }
    }
}
```

On `Duplicate`, the task transitions to `DEDUP_HIT`, `refCount` is incremented, and zero bytes are transferred — unchanged from v1.0 in spirit, but now both the index read and the referenced object belong to the requesting user, so no rules exception is needed.

## 5. Security rules (replaces §7.3 `checksumIndex` block and §7.4)

```text
// firestore.rules
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid}/uploadTasks/{taskId} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
    // Per-uid dedup index — owner read/write only. No Cloud Functions.
    match /users/{uid}/checksumIndex/{checksum} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
    }
  }
}
```

```text
// storage.rules
rules_version = '2';
service firebase.storage {
  match /b/{bucket}/o {
    // Content-addressed objects and the large-file staging area:
    // owner-only, both directions. No cross-uid read path exists anywhere.
    match /users/{uid}/{area}/{objectId} {
      allow read: if request.auth != null && request.auth.uid == uid;
      allow write: if request.auth != null
                    && request.auth.uid == uid
                    && request.resource.size < 5 * 1024 * 1024 * 1024; // 5 GB
    }
  }
}
```

These rule files ship in the SDK repository under `firebase/` as copy-paste templates, with a documented `firebase deploy --only firestore:rules,storage` step in the integration guide. This is the *only* backend setup an adopter performs.

## 6. Future work: opt-in global dedup

Cross-user dedup remains valuable for public/shared-content apps and can be layered on later **without changing the client contract**: a companion Cloud Functions package maintains a global index keyed by checksum, and the SDK consults it only when `DedupConfig.scope = GLOBAL`. Adopters who enable it accept the documented trade-offs (file-existence oracle, shared-object lifecycle, function deployment and cost). It is explicitly out of scope for v1.
