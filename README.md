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


https://github.com/user-attachments/assets/c49ca34d-8810-4d80-a187-e8418a7e1fce


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

This is the **real-app path**: your own Firebase project, real users, real
uploads — *not* the emulator the sample uses for local testing. The SDK never
touches Firebase initialisation; your app owns that (exactly as below), and the
SDK uses whatever `FirebaseApp` you've set up.

### Step 1 — Create a Firebase project and add your app

1. In the [Firebase console](https://console.firebase.google.com), create a
   project and **add an Android app** with your real `applicationId`.
2. Download the generated **`google-services.json`** and put it in your **app
   module** (`app/google-services.json`).
3. Enable **Authentication** (any sign-in method) and **Cloud Storage**. Enable
   **Cloud Firestore** too if you'll use deduplication (on by default) or
   `SyncPolicy`.

### Step 2 — Apply the Google Services Gradle plugin

```kotlin
// settings/root build.gradle.kts (plugins block)
plugins { id("com.google.gms.google-services") version "4.4.2" apply false }

// app/build.gradle.kts (top of plugins block)
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")   // reads google-services.json
}
```

This is what wires `google-services.json` into your build so Firebase
auto-initialises from it — no hard-coded keys in code.

### Step 3 — Add the dependencies

Until it's on Maven Central, publish the SDK to your local/internal repo:

```bash
./gradlew :upload-manager:publishToMavenLocal
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("dev.uploadmanager:upload-manager:0.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth")     // required
    implementation("com.google.firebase:firebase-storage")  // required
    implementation("com.google.firebase:firebase-firestore") // only if dedup/sync
}
```

Consumer R8/ProGuard rules ship with the library — nothing to add for release
builds.

### Step 4 — Deploy the security rules (the only backend setup)

Copy [`firebase/storage.rules`](firebase/storage.rules) (and
[`firebase/firestore.rules`](firebase/firestore.rules) if using dedup/sync) into
your project and deploy:

```bash
firebase deploy --only storage
firebase deploy --only firestore:rules   # if dedup or SyncPolicy != NONE
```

These scope every object and index entry to `users/{uid}/` and reject all
cross-user access. **Do not skip this** — uploads to an unprotected bucket are a
security hole.

### Step 5 — Initialise Firebase and sign the user in (your app's job)

In production you do **not** call `useEmulator` and you do **not** build fake
`FirebaseOptions`. The google-services plugin handles config; you just sign in a
real user before uploading:

```kotlin
// Application.onCreate()
FirebaseApp.initializeApp(this)            // reads google-services.json
// Recommended: install App Check (Play Integrity) to reject non-genuine builds
// FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
//     PlayIntegrityAppCheckProviderFactory.getInstance())

// Somewhere before enqueuing — enqueue() REQUIRES a signed-in user:
FirebaseAuth.getInstance().signInWithEmailAndPassword(/* … */)  // or your provider
```

### Step 6 — Initialise the SDK once

```kotlin
// Application.onCreate(), after Firebase is set up
UploadManager.initialise(
    context = this,
    config = UploadManagerConfig(
        networkPreference = NetworkPreference.ALLOW_CELLULAR, // >10 MB still waits for WiFi
        maxConcurrentUploads = 3,
        // staging / dedup / syncPolicy / adaptiveConcurrency have production-ready defaults
    ),
)
```

### Step 7 — Enqueue

```kotlin
val taskId = UploadManager.enqueue(
    UploadRequest(
        localUri = uri,                  // prefer ACTION_OPEN_DOCUMENT URIs (persistable grants)
        mimeType = "image/jpeg",
        fileName = "photo.jpg",
        priority = UploadPriority.P0,    // P0 (now) … P4 (backfill)
        metadata = mapOf("album" to "trip"),
    )
)
```

### Step 8 — Observe and control

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

### Step 9 — Production checklist

- [ ] `google-services.json` in the app module; google-services plugin applied.
- [ ] Storage (and Firestore, if used) **rules deployed**.
- [ ] A user is **signed in before** the first `enqueue`.
- [ ] **`POST_NOTIFICATIONS`** runtime permission requested on Android 13+ if you
      want the foreground-upload notification visible (uploads still run without
      it). The SDK already declares `INTERNET`, `FOREGROUND_SERVICE`, and
      `FOREGROUND_SERVICE_DATA_SYNC` for you via manifest merge.
- [ ] (Recommended) **App Check** installed.
- [ ] (Optional) an [`UploadMetrics`](upload-manager/src/main/kotlin/dev/uploadmanager/api/UploadMetrics.kt)
      sink wired into the config for analytics.

### Demo vs production — and how the sample switches

The [sample app](#the-sample-app--verifying-locally) ships in **demo mode** (no
`google-services.json`) so it talks to the local Firebase Emulator Suite. To run
the *same* sample against a **real** Firebase project, just drop your
`google-services.json` into the `sample/` module and rebuild — the build detects
it, applies the google-services plugin, and
[`SampleApp`](sample/src/main/kotlin/dev/uploadmanager/sample/SampleApp.kt)
initialises real Firebase instead of the emulator (see its `BuildConfig.USE_EMULATOR`
branch). That file is the canonical example of the demo-vs-production split.

> **Screenshots:** the sample's UI (preset bar, CUJ buttons, live task list, event
> log) is the fastest way to see the API in action; captured images can live under
> `docs/images/`.

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
[docs/CUJS.md](docs/CUJS.md).** New to the codebase? Start with
[docs/WALKTHROUGH.md](docs/WALKTHROUGH.md) — it explains *why* each use case works
(in plain language, ready to re-explain to a teammate). The automated subset runs
on an emulator in CI on every push; the headline manual ones
(resume-after-death, park→recovery, source-gone/restart, battery throttling,
reboot) are tap-or-adb from the sample.

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
