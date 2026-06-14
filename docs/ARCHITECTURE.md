# Architecture

A tour of the SDK's internals, package by package. The public entry point is
`dev.uploadmanager.UploadManager`; everything else is internal.

## Data flow

```
enqueue(request)
  → SourceChecker.fingerprint        (readability + size/mtime)
  → FileStager.stage                 (snapshot + SHA-256, if eligible)
  → Room: UploadTaskEntity (PENDING) → QUEUED
  → UploadScheduler.dispatch         (unique WorkManager request per task)

FirebaseUploadWorker.doWork
  → TTL check → SourceChecker.check  (Gone → FAILED; Changed → restart)
  → auth check                       (not signed in → PARKED)
  → DeduplicationEngine.check        (hit → DEDUP_HIT, zero bytes)
  → ConcurrencyPolicy.allowsLargeUpload (no → PARKED / DEVICE_BUSY)
  → ConcurrencyGovernor.withSlot { transfer }
        → resumable putFile, atomic session-URI persistence,
          throttled progress writes
        → success: markCompleted, dedup index + Firestore record (best-effort)
        → failure: RetryClassifier → retry / park / fail
```

## Packages

| Package | Responsibility |
| --- | --- |
| `api` | Public surface: `UploadManagerConfig`, `UploadRequest`, `UploadEvent`, `UploadState`, `UploadTaskState`, `StagingConfig`, `DedupConfig`, `SyncPolicy`, `UploadMetrics`. |
| `db` | Room: `UploadTaskEntity` (the source of truth) + `UploadTaskDao`. |
| `scheduler` | `UploadScheduler` maps priority → WorkManager constraints; `ParkedSweepWorker` re-dispatches parked tasks. |
| `worker` | `FirebaseUploadWorker` (the upload state machine) + `ActiveTaskRegistry` (process-scoped live `UploadTask`s for pause/resume). |
| `retry` | `RetryPolicy` + `RetryClassifier` (pure error → action). |
| `dedup` | `DeduplicationEngine` — per-uid `checksumIndex`, best-effort. |
| `sync` | `FirestoreSync` — opt-in, write-behind file/task projection. |
| `internal` | `FileStager`, `SourceChecker`, `ConcurrencyPolicy` (pure), `ConcurrencyGovernor` (resizable gate), `DeviceConditionsMonitor` (battery/thermal). |
| `events` | `UploadEvents` — in-process Flow bus behind `observe()`. |
| `core` | `UploadManagerCore` — process-scoped wiring of all of the above. |

## Design invariants

- **Room first.** A task is persisted before any network activity; the resumable
  session URI is written atomically with the `UPLOADING` state.
- **Firestore is best-effort.** Dedup checks fall back to "new", sync writes are
  fire-and-forget; Firestore being unreachable never blocks or fails an upload.
- **WorkManager owns retry timing.** `RetryClassifier` only classifies; the worker
  returns `retry()`/`failure()`, and the parked tier re-dispatches over hours/days.
- **Decision logic is pure.** `ConcurrencyPolicy`, `RetryClassifier`,
  `SourceChecker.compare`, `StagingConfig.shouldStage` are side-effect free and
  unit-tested; framework code (thermal, battery, broadcasts) lives at the edges.

See [spec-revisions/](spec-revisions/) for the rationale behind dedup, retry,
staging, and sync, and [VERIFYING.md](VERIFYING.md) / the `*-TEST-PLAN.md` files
for how to exercise each.
