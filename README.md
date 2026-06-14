# Upload Manager SDK for Android

![CI](https://github.com/raystatic/upload-manager/actions/workflows/ci.yml/badge.svg)
![Instrumented](https://github.com/raystatic/upload-manager/actions/workflows/instrumented.yml/badge.svg)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

A plug-and-play, open-source **upload manager** for Android apps, backed by the
developer's **own Firebase project**. Enqueue a file and the SDK handles
persistence across process death and reboots, resumable Storage sessions,
OS-friendly background scheduling, deduplication, and battery-aware throttling —
so you never write upload plumbing again.

> **Status: v0.1.0** — feature-complete, pre-1.0 (API may still change).
> Both the unit and the on-emulator instrumented test suites are green on every
> push.

## Table of contents

- [What is this](#what-is-this)
- [Why it's needed](#why-its-needed)
- [Core problems it solves (and how)](#core-problems-it-solves-and-how)
- [Production integration — step by step](#production-integration--step-by-step)
- [Configuration reference](#configuration-reference)
- [The sample app & verifying locally](#the-sample-app--verifying-locally)
- [Architecture](#architecture)
- [Project layout, contributing, security, license](#project-layout)

## What is this

A reusable Android library ([`upload-manager/`](upload-manager)) that turns
"upload this file to my backend" into a single call:

```kotlin
val taskId = UploadManager.enqueue(UploadRequest(uri, mimeType, fileName))
```

Everything after that — surviving the user closing the app, flaky networks, a
reboot mid-upload, duplicate files, a hot or low device — is handled for you. It
uses **Firebase** (Storage for bytes, Auth for per-user scoping, Firestore for
the optional dedup index/metadata) running on *your* Firebase project, so there's
no third-party server in the loop and no per-upload cost to anyone but you.

The public surface is intentionally tiny — just
[`UploadManager`](upload-manager/src/main/kotlin/dev/uploadmanager/UploadManager.kt)
and the [`api/`](upload-manager/src/main/kotlin/dev/uploadmanager/api) package.

## Why it's needed

"Upload a file" sounds trivial and almost never is in production. A naive
`storageRef.putFile(uri)` breaks the moment reality intrudes:

- The user **swipes the app away** or the OS kills it → the upload dies and
  starts over from 0% next time.
- The **network drops** for a minute, or for a day → either you give up, or you
  hand-roll retry/backoff and a queue.
- The file is a **2 GB video** → you must respect WiFi-only, battery, and thermal
  limits, or you drain the battery and get throttled.
- The user **uploads the same photo twice** → you pay to store and transfer it
  twice unless you dedupe by content.
- The user **edits or deletes the file** after queuing it → a resumed upload
  silently corrupts the object.
- You need all of this to **survive a reboot** and respect Doze and OEM battery
  managers.

Each of these is a subtle, well-known footgun. This SDK encodes the correct
answers once, behind a clean API, with the design rationale written down in
[`docs/spec-revisions/`](docs/spec-revisions).

## Core problems it solves (and how)

| Problem | How it's solved | Code |
| --- | --- | --- |
| **Uploads lost on process death / reboot** | Every task is persisted to Room *before* any network activity; the resumable Storage session URI is written atomically with the `UPLOADING` state, so a killed upload resumes from the last confirmed byte. | [`UploadTaskDao.saveSession`](upload-manager/src/main/kotlin/dev/uploadmanager/db/UploadTaskDao.kt), [`FirebaseUploadWorker`](upload-manager/src/main/kotlin/dev/uploadmanager/worker/FirebaseUploadWorker.kt) |
| **Background execution under OS limits** | One unique WorkManager request per task with priority-aware constraints (expedited P0 → charging-gated P4); survives Doze, reboots, OEM battery managers. | [`UploadScheduler`](upload-manager/src/main/kotlin/dev/uploadmanager/scheduler/UploadScheduler.kt) |
| **Flaky networks / transient failures** | Two-tier retry: WorkManager-owned exponential backoff, then a **parked** tier re-dispatched on connectivity/charging/daily-sweep, up to a 7-day TTL. WorkManager owns timing; the SDK only classifies errors. | [`RetryClassifier`](upload-manager/src/main/kotlin/dev/uploadmanager/retry/RetryClassifier.kt), [`RetryPolicy`](upload-manager/src/main/kotlin/dev/uploadmanager/retry/RetryPolicy.kt), [`ParkedSweepWorker`](upload-manager/src/main/kotlin/dev/uploadmanager/scheduler/ParkedSweepWorker.kt) |
| **Source file edited/deleted after enqueue** | Files are snapshotted into app-private storage at enqueue (with a free SHA-256); non-staged files are fingerprinted and **restart from zero** if changed, or fail fast (`SOURCE_GONE`) if deleted — never a corrupt object. | [`FileStager`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/FileStager.kt), [`SourceChecker`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/SourceChecker.kt) |
| **Duplicate uploads waste storage/bandwidth** | Per-uid content-hash dedup: identical content uploads zero bytes (`DEDUP_HIT`) and points at the existing content-addressed object. Best-effort — Firestore being down never blocks an upload. | [`DeduplicationEngine`](upload-manager/src/main/kotlin/dev/uploadmanager/dedup/DeduplicationEngine.kt) |
| **Battery / heat** | Concurrency scales with battery/charge/network through a resizable gate, and all transfers pause on thermal MODERATE+. | [`ConcurrencyPolicy`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/DeviceConditions.kt), [`ConcurrencyGovernor`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/ConcurrencyGovernor.kt), [`DeviceConditionsMonitor`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/DeviceConditionsMonitor.kt) |
| **Cross-user data leaks** | Everything is scoped to `users/{uid}/`; dedup is per-uid (no cross-user index). Enforced by the bundled security rules. | [`firebase/storage.rules`](firebase/storage.rules), [`firebase/firestore.rules`](firebase/firestore.rules) |
| **Observability & control** | `Flow` of progress + lifecycle events, an optional metrics sink, and `pause`/`resume`/`cancel`/`retry`. | [`UploadManager`](upload-manager/src/main/kotlin/dev/uploadmanager/UploadManager.kt), [`UploadMetrics`](upload-manager/src/main/kotlin/dev/uploadmanager/api/UploadMetrics.kt) |

## Production integration — step by step

### 1. Set up your Firebase project

Create a Firebase project, add your Android app, and drop `google-services.json`
into your app module. Enable **Authentication** (any provider) and **Cloud
Storage**; enable **Firestore** too if you'll use dedup (on by default) or
`SyncPolicy`. Apply the Google Services plugin per the Firebase docs.

### 2. Add the dependency

Until it's on Maven Central, publish locally (or to your internal repo):

```bash
./gradlew :upload-manager:publishToMavenLocal
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.uploadmanager:upload-manager:0.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    // Firebase Storage + Auth are required; Firestore only if you use dedup/sync.
}
```

Consumer R8/ProGuard rules ship with the library — nothing to add.

### 3. Deploy the security rules (the only backend setup)

Copy [`firebase/storage.rules`](firebase/storage.rules) (and
[`firebase/firestore.rules`](firebase/firestore.rules) if using dedup/sync) and:

```bash
firebase deploy --only storage
firebase deploy --only firestore:rules   # if dedup or SyncPolicy != NONE
```

These scope every object and index entry to `users/{uid}/` and reject any
cross-user access.

### 4. Initialise once

```kotlin
// Application.onCreate()
UploadManager.initialise(
    context = this,
    config = UploadManagerConfig(
        networkPreference = NetworkPreference.ALLOW_CELLULAR, // >10 MB still waits for WiFi
        maxConcurrentUploads = 3,
        // dedup / syncPolicy / staging / adaptiveConcurrency have sensible defaults
    ),
)
```

The host app owns Firebase init and sign-in; `enqueue` requires a signed-in
`FirebaseAuth` user.

### 5. Enqueue

```kotlin
val taskId = UploadManager.enqueue(
    UploadRequest(
        localUri = uri,                  // prefer ACTION_OPEN_DOCUMENT URIs
        mimeType = "image/jpeg",
        fileName = "photo.jpg",
        priority = UploadPriority.P0,    // P0 (now) … P4 (backfill)
        metadata = mapOf("album" to "trip"),
    )
)
```

### 6. Observe and control

```kotlin
UploadManager.observeAll().collect { tasks -> render(tasks) }      // List<UploadTaskState>
UploadManager.observe(taskId).collect { event ->                  // per-task events
    when (event) {
        is UploadEvent.Progress  -> bar.progress = event.pct
        is UploadEvent.Completed -> show(event.downloadUrl)
        is UploadEvent.DedupHit  -> show(event.downloadUrl)        // instant, zero bytes
        is UploadEvent.Failed    -> showError(event.reason)
        else -> Unit
    }
}

UploadManager.pause(taskId); UploadManager.resume(taskId)
UploadManager.cancel(taskId); UploadManager.retry(taskId)
```

### 7. (Optional) metrics & App Check

Plug an [`UploadMetrics`](upload-manager/src/main/kotlin/dev/uploadmanager/api/UploadMetrics.kt)
into the config to feed your analytics, and install Firebase App Check (Play
Integrity) in `Application.onCreate()` if you want to reject non-genuine builds.

> **Screenshots:** the sample app's UI (preset bar, CUJ buttons, live task list
> and event log) is the quickest way to see the API in action — run it per
> [the sample section](#the-sample-app--verifying-locally). Captured screenshots
> can live under `docs/images/`.

## Configuration reference

All knobs live on [`UploadManagerConfig`](upload-manager/src/main/kotlin/dev/uploadmanager/api/UploadManagerConfig.kt):

| Field | Default | Purpose |
| --- | --- | --- |
| `networkPreference` | `ALLOW_CELLULAR` | `WIFI_ONLY` forces unmetered for everything. |
| `cellularMaxBytes` | 10 MB | Files larger than this wait for WiFi even on cellular. |
| `maxConcurrentUploads` | 3 | Upper bound on concurrent transfers. |
| `retryPolicy` | 5 fast attempts → park → 7-day TTL | See [revision doc 02](docs/spec-revisions/02-retry-policy.md). |
| `progressIntervalBytes` | 512 KB | Throttles progress persistence. |
| `foregroundThresholdBytes` | 50 MB | Above this, runs as a foreground service. |
| `staging` | `REFERENCE`, auto-copy ≤ 64 MB, 1 GB budget | Snapshot policy ([doc 03](docs/spec-revisions/03-source-file-durability.md)). |
| `dedup` | enabled | Per-uid content-hash dedup ([doc 01](docs/spec-revisions/01-deduplication.md)). |
| `syncPolicy` | `NONE` | Firestore mirroring ([doc 04](docs/spec-revisions/04-firestore-sync.md)). |
| `adaptiveConcurrency` | true | Battery/thermal/network-aware concurrency. |
| `largeUploadThresholdBytes` | 50 MB | "Large" uploads held off cellular / low battery / heat. |
| `metrics` | null | Optional `UploadMetrics` sink. |

## The sample app & verifying locally

The [`sample/`](sample) app is a Compose **CUJ runner** that exercises every
behavior against the Firebase Emulator Suite — no `google-services.json` needed.

```bash
cd firebase && firebase emulators:start --project demo-upload-manager   # terminal 1
./gradlew :sample:installDebug                                           # terminal 2
```

It has a **config-preset selector** (Default, Reference/no-staging, Copy, Dedup
off, Firestore sync FULL, Adaptive off, WiFi only), **one-tap CUJ buttons** that
generate their own test files (small, large, duplicate, enqueue-then-delete), a
live **event log**, and the task list with pause/resume/cancel/retry.

**Every CUJ, how to run it, and its pass criteria are in one place:
[docs/CUJS.md](docs/CUJS.md).** The automated subset runs on an emulator in CI on
every push; the headline manual ones (resume-after-death, park→recovery,
source-gone/restart, battery throttling, reboot) are tap-or-adb from the sample.

```bash
./gradlew :upload-manager:test                       # unit tests (no device)
./gradlew :upload-manager:connectedDebugAndroidTest  # instrumented, emulators running
```

## Architecture

Room is the source of truth; WorkManager drives execution; Firestore is a
best-effort projection. A package tour and the design invariants are in
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), and the rationale for the
non-obvious decisions (per-uid dedup, long-horizon retry, staging, opt-in sync)
in [docs/spec-revisions/](docs/spec-revisions).

```
enqueue → fingerprint/stage → Room(PENDING) → WorkManager
        → worker: source-check → dedup → governed transfer (resumable)
        → success: dedup index + Firestore record (best-effort)
        → failure: classify → retry / park / fail
```

## Project layout

```
upload-manager/   the SDK (Android library; public API = UploadManager + api/)
sample/           Compose CUJ-runner app (Firebase Emulator Suite)
firebase/         security-rules templates + emulator config
docs/             ARCHITECTURE, CUJS, VERIFYING, spec + revision docs
```

- **Contributing:** [CONTRIBUTING.md](CONTRIBUTING.md) · **Conduct:** [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
- **Security model & reporting:** [SECURITY.md](SECURITY.md)
- **Changes:** [CHANGELOG.md](CHANGELOG.md)
- **License:** [Apache-2.0](LICENSE)

### Requirements

- minSdk 26, Java/Kotlin toolchain 17
- Firebase Storage + Auth (host app initialises Firebase); Firestore is used by
  dedup and `SyncPolicy` and degrades gracefully if absent.
