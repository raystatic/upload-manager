# Upload Manager SDK for Android

A plug-and-play upload manager for Android apps backed by the developer's **own
Firebase project**. Reliable, resumable, battery-aware background uploads:
enqueue a file and the SDK takes care of persistence across process death and
reboots, resumable Firebase Storage sessions, OS-friendly scheduling via
WorkManager, and long-horizon retries.

> **Status: v0.1.0 — feature-complete, pre-1.0.**

## Features

- **Durable, resumable uploads** — persisted to Room before any network activity;
  resumes from the last confirmed byte across process death and reboots.
- **OS-friendly scheduling** — one WorkManager request per task; expedited P0
  through charging-gated P4 backfill; survives Doze and OEM battery managers.
- **Long-horizon retry** — WorkManager-owned fast backoff → a parked tier
  re-dispatched on connectivity/charging/daily sweep → a 7-day TTL.
- **Pause / resume / cancel / retry** and a `Flow` of progress + lifecycle events.
- **Source-file staging** — snapshots the file (with a free SHA-256) so edits or
  deletes after enqueue can't corrupt the upload; restart-on-change for the rest.
- **Per-uid content-hash dedup** — content-addressed paths; a repeat upload is a
  zero-byte `DEDUP_HIT`. Best-effort: Firestore being down never blocks an upload.
- **Opt-in Firestore mirroring** — `SyncPolicy` (off by default); progress bytes
  are never mirrored.
- **Adaptive concurrency** — scales with battery/charge/network and pauses all
  transfers in the heat (thermal MODERATE+).
- **Observability** — structured events plus an optional `UploadMetrics` sink.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for internals and
[docs/spec-revisions/](docs/spec-revisions/) for the design rationale.

## Quick start

**1. Initialise** (the host app owns Firebase setup and authentication):

```kotlin
// Application.onCreate()
UploadManager.initialise(
    context = this,
    config = UploadManagerConfig(
        networkPreference = NetworkPreference.ALLOW_CELLULAR, // >10 MB still waits for WiFi
        maxConcurrentUploads = 3,
    ),
)
```

**2. Enqueue** (requires a signed-in `FirebaseAuth` user):

```kotlin
val taskId = UploadManager.enqueue(
    UploadRequest(
        localUri = uri,                       // prefer ACTION_OPEN_DOCUMENT URIs
        mimeType = "image/jpeg",
        fileName = "photo.jpg",
        priority = UploadPriority.P0,         // P0 (now) … P4 (backfill)
    )
)
```

**3. Observe and control:**

```kotlin
UploadManager.observeAll().collect { tasks -> render(tasks) }
UploadManager.observe(taskId).collect { event -> /* Progress, Completed, Failed… */ }

UploadManager.pause(taskId)
UploadManager.resume(taskId)
UploadManager.cancel(taskId)
UploadManager.retry(taskId)
```

**4. Deploy the security rules** to your Firebase project (the only backend setup):

```
firebase deploy --only storage              # uses firebase/storage.rules
firebase deploy --only firestore:rules      # only if dedup or SyncPolicy != NONE
```

Uploads land at `users/{uid}/files/{taskId}`, readable and writable only by
their owner.

## How it works

| Stage | Mechanism |
| --- | --- |
| Durability | Every task is persisted to Room **before** any network activity; the Storage session URI is persisted atomically the moment the server issues it, so an upload interrupted by process death resumes from the last confirmed byte. |
| Scheduling | One unique WorkManager request per task. P0 runs expedited; P1–P3 respect network/battery constraints; P4 waits for charging + WiFi. |
| Retry | Two tiers ([design](docs/spec-revisions/02-retry-policy.md)): transient errors get WorkManager exponential backoff (≤5 attempts), then the task is **parked** and re-dispatched on connectivity/charging/daily-sweep triggers, until a 7-day TTL. Permanent errors (quota, bad path, deleted source file) fail immediately. |
| Sessions | Resumable session URIs older than ~6 days are discarded and the upload restarts from zero — an expected transition, not an error. |

## Repository layout

```
upload-manager/   the SDK (Android library)
sample/           demo app (runs entirely against the Firebase Emulator Suite)
firebase/         security-rules templates + emulator config
docs/             tech-spec revision documents
```

## Running the sample against the Firebase Emulator Suite

No `google-services.json` needed — the sample uses a demo project.

```bash
cd firebase
firebase emulators:start --project demo-upload-manager
# in another terminal:
./gradlew :sample:installDebug
```

## Tests

```bash
./gradlew :upload-manager:test                          # JVM unit tests
# with the emulator suite running (see above) and an Android emulator attached:
./gradlew :upload-manager:connectedDebugAndroidTest     # end-to-end upload + resumability
```

## Configuration reference

All knobs live on `UploadManagerConfig` (sensible defaults shown):

| Field | Default | Purpose |
| --- | --- | --- |
| `networkPreference` | `ALLOW_CELLULAR` | `WIFI_ONLY` forces unmetered for everything. |
| `cellularMaxBytes` | 10 MB | Files larger than this wait for WiFi even on cellular. |
| `maxConcurrentUploads` | 3 | Upper bound on concurrent transfers. |
| `retryPolicy` | 5 fast attempts → park → 7-day TTL | See revision doc 02. |
| `progressIntervalBytes` | 512 KB | Throttles progress persistence. |
| `foregroundThresholdBytes` | 50 MB | Above this, runs as a foreground service. |
| `staging` | `REFERENCE`, auto-copy ≤ 64 MB, 1 GB budget | Snapshot policy (revision doc 03). |
| `dedup` | enabled | Per-uid content-hash dedup (revision doc 01). |
| `syncPolicy` | `NONE` | Firestore mirroring (revision doc 04). |
| `adaptiveConcurrency` | true | Battery/thermal/network-aware concurrency (spec §10). |
| `largeUploadThresholdBytes` | 50 MB | "Large" uploads held off cellular / low battery / heat. |
| `metrics` | null | Optional `UploadMetrics` sink (spec §13.2). |

## Consuming the library

Published with the `maven-publish` plugin (group `dev.uploadmanager`, artifact
`upload-manager`). For local development:

```bash
./gradlew :upload-manager:publishToMavenLocal
# then in the consumer: implementation("dev.uploadmanager:upload-manager:0.1.0")
```

## Optional: App Check & foreground limits

- **App Check** is the host app's Firebase concern — install your provider (e.g.
  Play Integrity) in `Application.onCreate()` before enqueuing if you require it.
- **Android 14 foreground services** cap `dataSync` at ~6 h/day. Single-file
  uploads won't hit this; very large backfills should rely on the parked tier and
  resumable sessions to span multiple windows rather than one long foreground run.

## Requirements

- minSdk 26, Java/Kotlin toolchain 17
- Firebase Storage + Firebase Auth (the host app initialises Firebase); Firestore
  is used by dedup and `SyncPolicy` and degrades gracefully if absent.
