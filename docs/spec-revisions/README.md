# Upload Manager SDK — Spec Revisions

This directory contains revision documents for the [Upload Manager SDK Tech Spec v1.0](https://docs.google.com/document/d/1UGtjRdnMg9fZjUq3ARZBV3ajY2kAXYgWRWKmFYFcRJA/edit), produced from a scalability and real-world-reliability review of the draft (June 2026).

The local pipeline in the original spec — Room DB as source of truth → WorkManager scheduling → Firebase Storage resumable uploads — is sound and is retained unchanged. The review found four design-level weak links that do not survive scale scrutiny, each of which is addressed by a revision document below. Each document is self-contained and states at the top which sections of the original spec it replaces or amends, so it can be merged back into the spec directly.

## Revision Index

| Doc | Replaces / Amends | Problem it fixes | Severity |
| --- | --- | --- | --- |
| [01-deduplication.md](01-deduplication.md) | Replaces §8; replaces `checksumIndex` parts of §7.1–§7.4 | Global cross-user dedup index leaks file existence and download URLs across users, has no ownership/GC story, races on concurrent uploads, and silently requires every adopter to deploy Cloud Functions | Design-breaking |
| [02-retry-policy.md](02-retry-policy.md) | Replaces §9; amends §5 and §4.2 | Two competing retry layers; retry horizon of ~2 minutes contradicts the 99.9% eventual-completion NFR; resumable session expiry (~7 days) unhandled | Design-breaking |
| [03-source-file-durability.md](03-source-file-durability.md) | New section; amends §4.1 and §6 | Persisted content URIs go stale across process death and time; file edits between enqueue and upload corrupt resumed uploads and poison the dedup index | Design-breaking |
| [04-firestore-sync.md](04-firestore-sync.md) | Replaces §7.2; amends FR-09 (§2.1) and §3.2 | Default Firestore mirroring costs adopters 5–10+ billed writes per upload for a weak benefit; metadata-before-binary ordering leaves orphaned phantom records | Cost / correctness |

## Cross-cutting changes

The revision docs introduce the following identifiers, used consistently across all four:

- New `UploadState`: `PARKED` (doc 02)
- New error surface: `SOURCE_CHANGED` (doc 03)
- New config types: `StagingMode` (doc 03), `SyncPolicy` (doc 04), revised `RetryPolicy` (doc 02)
- New `UploadTask` entity fields: `sessionCreatedAt` (doc 02), `sourceSizeBytes`, `sourceLastModified`, `stagedPath` (doc 03), `parkedUntil`/`firstAttemptAt` (doc 02)
- Storage object paths become content-addressed: `users/{uid}/files/{checksum}` (doc 01)

Code snippets reproduced from the original spec have also been corrected where they had incidental defects (blocking calls inside Firebase callbacks, unthrottled Room writes per progress event, deprecated `-ktx` Firebase artifacts).
