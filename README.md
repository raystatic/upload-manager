# Upload Manager SDK for Android

A plug-and-play upload manager for Android apps backed by the developer's **own
Firebase project**. Reliable, resumable, battery-aware background uploads:
enqueue a file and the SDK takes care of persistence across process death and
reboots, resumable Firebase Storage sessions, OS-friendly scheduling via
WorkManager, and long-horizon retries.

> **Status: Milestone 1 (core pipeline) — pre-release.**
> Implemented: enqueue → Room queue → WorkManager dispatch → resumable Storage
> upload → fast/parked retry tiers → pause/resume/cancel → progress observation.
> Coming next (M2/M3): content-hash deduplication, copy-staging for source
> durability, opt-in Firestore sync, battery/thermal adaptive concurrency.
> See [docs/spec-revisions/](docs/spec-revisions/) for the design.

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
firebase deploy --only storage   # uses firebase/storage.rules
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

## Requirements

- minSdk 26, Java/Kotlin toolchain 17
- Firebase Storage + Firebase Auth (the host app initialises Firebase)
