# Upload Manager SDK for Android

![CI](https://github.com/raystatic/upload-manager/actions/workflows/ci.yml/badge.svg)
![Instrumented](https://github.com/raystatic/upload-manager/actions/workflows/instrumented.yml/badge.svg)
[![JitPack](https://jitpack.io/v/raystatic/upload-manager.svg)](https://jitpack.io/#raystatic/upload-manager)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

A plug-and-play, open-source **upload manager** for Android apps, backed by the
developer's **own Firebase project**. Enqueue a file and the SDK handles
persistence across process death and reboots, resumable Storage sessions,
OS-friendly background scheduling, deduplication, and battery-aware throttling —
so you never write upload plumbing again.

> **Status: v0.1.1** — feature-complete, pre-1.0 (API may still change).
> Both the unit and the on-emulator instrumented test suites are green on every
> push.

## Demo

https://github.com/user-attachments/assets/9581b1aa-650a-414b-b409-218356e960c4

## Table of contents

- [Demo](#demo)
- [Quick start](#quick-start)
- [What is this](#what-is-this)
- [Why it's needed](#why-its-needed)
- [Core problems it solves (and how)](#core-problems-it-solves-and-how)
- [Production integration — step by step](#production-integration--step-by-step)
- [Configuration reference](#configuration-reference)
- [Optional features & their trade-offs](#optional-features--their-trade-offs)
- [Troubleshooting](#troubleshooting)
- [The sample app & verifying locally](#the-sample-app--verifying-locally)
- [Architecture](#architecture)
- [Project layout, contributing, security, license](#project-layout)

## Quick start

The leanest setup — resumable uploads + retry + persistence across process death,
on **Storage + Auth only** (no Firestore). For the full feature path (dedup,
cross-device sync) see [Production integration](#production-integration--step-by-step).

```kotlin
// settings.gradle.kts → dependencyResolutionManagement { repositories { … } }
maven("https://jitpack.io")
```

```kotlin
// app/build.gradle.kts → dependencies { … }
implementation("com.github.raystatic.upload-manager:upload-manager:v0.1.1")
implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
implementation("com.google.firebase:firebase-auth")
implementation("com.google.firebase:firebase-storage")
```

```kotlin
// Application.onCreate() — your app owns Firebase init + sign-in
FirebaseApp.initializeApp(this)
UploadManager.initialise(this, UploadManagerConfig(dedup = DedupConfig(enabled = false)))

// Once a user is signed in (any FirebaseAuth provider, incl. anonymous):
val taskId = UploadManager.enqueue(UploadRequest(uri, "image/jpeg", "photo.jpg"))
UploadManager.observe(taskId).collect { event -> /* Progress / Completed / Failed */ }
```

**Two backend steps are required:** enable **Auth + Cloud Storage** in your Firebase
project, and **deploy the Storage rules** (Step 4) — until the rules are published,
every upload fails with `PERMISSION_DENIED`. That's the whole setup for the lean path.

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
| **Duplicate uploads waste storage/bandwidth** | Per-uid content-hash dedup (optional, Firestore-backed — [details & trade-offs](#deduplication-on-by-default)): identical content uploads zero bytes (`DEDUP_HIT`) and points at the existing content-addressed object. Best-effort — Firestore being down never blocks an upload. | [`DeduplicationEngine`](upload-manager/src/main/kotlin/dev/uploadmanager/dedup/DeduplicationEngine.kt) |
| **Battery / heat** | Concurrency scales with battery/charge/network through a resizable gate, and all transfers pause on thermal MODERATE+. | [`ConcurrencyPolicy`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/DeviceConditions.kt), [`ConcurrencyGovernor`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/ConcurrencyGovernor.kt), [`DeviceConditionsMonitor`](upload-manager/src/main/kotlin/dev/uploadmanager/internal/DeviceConditionsMonitor.kt) |
| **Cross-user data leaks** | Everything is scoped to `users/{uid}/`; dedup is per-uid (no cross-user index). Enforced by the bundled security rules. | [`firebase/storage.rules`](firebase/storage.rules), [`firebase/firestore.rules`](firebase/firestore.rules) |
| **Observability & control** | `Flow` of progress + lifecycle events, an optional metrics sink, and `pause`/`resume`/`cancel`/`retry`. | [`UploadManager`](upload-manager/src/main/kotlin/dev/uploadmanager/UploadManager.kt), [`UploadMetrics`](upload-manager/src/main/kotlin/dev/uploadmanager/api/UploadMetrics.kt) |

## Production integration — step by step

This is the **real-app path**: your own Firebase project, real users, real
uploads — *not* the emulator the sample uses for local testing. The SDK never
touches Firebase initialisation; your app owns that (exactly as below), and the
SDK uses whatever `FirebaseApp` you've set up.

### Prerequisites

| Need | Detail |
| --- | --- |
| Android | `minSdk` **26**+, JDK/Kotlin toolchain **17**, AndroidX. |
| A Firebase project | With **Authentication** + **Cloud Storage** enabled. Add **Cloud Firestore** only if you keep dedup on (default) or use `SyncPolicy`. |
| `google-services.json` | From that Firebase project, in your app module. |
| A signed-in user | `enqueue` requires a signed-in `FirebaseAuth` user (any provider, incl. anonymous). |
| Deployed security rules | The bundled Storage (and Firestore) rules — Step 4. |

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

The library is published publicly via **JitPack** (no extra account needed). Add
the JitPack repository, then the dependency:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")   // ← add this
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    // Latest release tag — note the leading `v` (see the JitPack badge for newer ones)
    implementation("com.github.raystatic.upload-manager:upload-manager:v0.1.1")

    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth")      // required
    implementation("com.google.firebase:firebase-storage")   // required
    implementation("com.google.firebase:firebase-firestore") // only if dedup/sync
}
```

> Prefer a local build? `./gradlew :upload-manager:publishToMavenLocal` then use
> `implementation("dev.uploadmanager:upload-manager:0.1.1")` with `mavenLocal()`.

Consumer R8/ProGuard rules ship with the library — nothing to add for release
builds.

### Step 4 — Deploy the security rules (the only backend setup)

**Why this step exists.** Security Rules are the lock on your data's front door:
Firebase checks them on *every* read/write and asks "is this caller allowed?" When
you create Storage or Firestore in **production mode**, the default answer is **"deny
everyone"** — the door is locked shut. So until you publish rules that allow it,
**every upload fails with `PERMISSION_DENIED`.** The bundled rules replace the
deny-all with a precise, safe policy: *a signed-in user may read/write only their
own files under `users/{their-uid}/…`, and nobody else's.* That's exactly what the
SDK needs. **Do not skip this** — and never use "test mode," which opens your bucket
to the whole internet.

You only need the Firestore rules if **dedup (on by default) or `SyncPolicy`** is
enabled; otherwise Storage rules alone are enough.

There are two ways to deploy — pick one.

**Option A — Firebase Console (no command line, easiest):**

1. [Firebase console](https://console.firebase.google.com) → your project.
2. **Build → Storage → Rules** tab. Select all the existing text, delete it, paste
   the contents of [`firebase/storage.rules`](firebase/storage.rules), click **Publish**.
3. (If using dedup/sync) **Build → Firestore Database → Rules** tab. Select all,
   delete, paste [`firebase/firestore.rules`](firebase/firestore.rules), click **Publish**.

Changes go live in a few seconds. (A default line like `allow read, write: if false;`
is the "deny everyone" you're replacing — deleting it is correct.)

**Option B — Firebase CLI (deploys straight from the repo files):**

```bash
npm install -g firebase-tools          # once
firebase login                         # opens a browser
cd firebase                            # where firebase.json + the .rules files live
firebase use --add                     # pick your project (creates .firebaserc)
firebase deploy --only storage,firestore:rules
```

To deploy them separately: `firebase deploy --only storage` and
`firebase deploy --only firestore:rules`.

Either way, the rules scope every object and index entry to `users/{uid}/` and reject
all cross-user access. After publishing, upload a file and confirm it lands under
`users/<uid>/files/…` in your bucket; a `PERMISSION_DENIED` in
`adb logcat -s UploadManager:D` almost always means a rule wasn't published.

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

> **Screenshots:** the sample's UI (a multi-file upload list with per-row progress and
> pause/resume/cancel, mirrored across devices) is the fastest way to see the API in
> action; captured images can live under `docs/images/`.

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
| `dedup` | enabled | Per-uid content-hash dedup — **needs Firestore + rules**; [trade-offs](#deduplication-on-by-default) ([doc 01](docs/spec-revisions/01-deduplication.md)). |
| `syncPolicy` | `NONE` | Firestore mirroring ([doc 04](docs/spec-revisions/04-firestore-sync.md)). |
| `adaptiveConcurrency` | true | Battery/thermal/network-aware concurrency. |
| `largeUploadThresholdBytes` | 50 MB | "Large" uploads held off cellular / low battery / heat. |
| `metrics` | null | Optional `UploadMetrics` sink. |

## Optional features & their trade-offs

A few capabilities are **on by default and do non-obvious things to your Firebase
project**. They're worth understanding (and you can turn any of them off).

### Deduplication (on by default)

**What it does:** before uploading, the SDK computes the file's **SHA-256** and
checks a small per-user index in **Cloud Firestore** at
`users/{uid}/checksumIndex/{checksum}`. If that content was already uploaded by
the same user, it **skips the transfer entirely** (a `DEDUP_HIT`, zero bytes) and
points the task at the existing object. On a successful upload it records the
index entry.

**Things to know — because they affect *your* project:**
- It **requires Cloud Firestore** and the [Firestore rules](firebase/firestore.rules)
  deployed. Without them, dedup degrades to a best-effort no-op (it never blocks
  an upload, but you also get no dedup).
- It **changes your Storage layout**: deduped objects are **content-addressed** at
  `users/{uid}/files/{checksum}` (a hash), not `…/files/{taskId}`. If you browse
  your bucket you'll see hashes — that's expected.
- It is **per-user, by design** — there is no cross-user index. This is the
  privacy-safe choice (a global index would let anyone test whether *some* user
  has a given file). The flip side: it only dedupes a user re-uploading their *own*
  identical file, and (today) only files small enough to be staged (≤ 64 MB).
- **Cost:** one Firestore read per upload, plus index storage. For apps where users
  rarely re-upload identical content, that cost may not be worth it.

**Turn it off** (then you need only Storage + Auth — no Firestore, no extra rules):

```kotlin
UploadManagerConfig(dedup = DedupConfig(enabled = false))
```

### Firestore mirroring — `syncPolicy` (off by default)

Optional cross-device visibility. `NONE` (default) writes nothing to Firestore;
`TERMINAL_ONLY` writes one file record **at completion**; `FULL` also mirrors
lifecycle. Progress bytes are never mirrored. Needs Firestore + rules when enabled.

### Adaptive concurrency (on by default)

Scales concurrent transfers with battery/charge/network and pauses everything in
the heat. No backend dependency; set `adaptiveConcurrency = false` for a fixed cap.

### Staging (on by default)

Snapshots files ≤ 64 MB into app-private storage at enqueue (with a free hash), so
editing/deleting the original can't corrupt the upload. Costs a copy per small
file; `StagingConfig(mode = StagingMode.REFERENCE, autoCopyBelowBytes = 0)` disables it.

> **TL;DR for the leanest setup:** `UploadManagerConfig(dedup = DedupConfig(enabled = false))`
> gives you the reliable core (resumable upload + retry + persistence) needing
> **only Firebase Storage + Auth**, with no Firestore, no extra rules, and
> task-addressed storage paths.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `PERMISSION_DENIED` on every upload | Security rules not deployed — Storage/Firestore start in "deny everyone" production mode | Deploy the bundled rules — [Step 4](#step-4--deploy-the-security-rules-the-only-backend-setup). |
| `IllegalStateException: enqueue requires a signed-in FirebaseAuth user` | No user signed in before the first `enqueue` | Sign a user in first (any provider, including anonymous). |
| `W … No AppCheckProvider installed … using placeholder token` | App Check isn't installed (it's optional) | Harmless unless you enforce App Check. Install a provider (Play Integrity in production) to silence it. |
| Sign-in fails: "auth credential is incorrect/expired" on first login | Email Enumeration Protection returns one generic error for "no account" and "wrong password" | Create the account first (or use a flow that creates-on-miss), and enable the **Email/Password** provider in the console. |
| JitPack dependency won't resolve | The tag's first build hasn't been triggered, or the coordinate is wrong | Open the JitPack badge once to build the tag; use `…:upload-manager:v0.1.1` (keep the leading `v`). |
| Upload stuck in `QUEUED`, never starts | A constraint isn't met (WiFi-only, battery-not-low, or charging for P4) | Check `networkPreference`/priority and device state; files over `cellularMaxBytes` wait for WiFi. |
| `SOURCE_GONE` failure | The source file was deleted before a non-staged upload finished | Use staging (default for ≤ 64 MB), or keep the source until the upload completes. |

## The sample app & verifying locally

The [`sample/`](sample) app is an **upload manager** that shows the SDK doing its job
in a realistic product. You sign in with email + password, tap **Select files** to
pick **multiple files at once**, and each one uploads in the background with its own
**file size, live progress, and pause/resume/cancel controls**. Because the SDK
mirrors task state to Firestore (`SyncPolicy.FULL`), **a second device signed in to the
same account sees every upload appear live** — that's why sign-in is email/password,
not anonymous (anonymous auth gives each device a different `uid`, so it couldn't share
a list).

```bash
cd firebase && firebase emulators:start --project demo-upload-manager   # terminal 1
./gradlew :sample:installDebug                                           # terminal 2
```

> **Cross-device demo** needs a real project (two devices can't share one local
> emulator): drop in your `google-services.json`, enable the **Email/Password** auth
> provider, deploy the rules (Step 4), then sign in with the same credentials on both
> devices.

The app is built from a few small files worth reading as integration examples:
[`UploadsViewModel`](sample/src/main/kotlin/dev/uploadmanager/sample/UploadsViewModel.kt)
(enqueue + merge `observeAll()` with a live Firestore view of the account's uploads),
[`UploadListScreen`](sample/src/main/kotlin/dev/uploadmanager/sample/UploadListScreen.kt)
(multi-select picker, per-row size/progress/controls), and
[`SignInScreen`](sample/src/main/kotlin/dev/uploadmanager/sample/SignInScreen.kt).
Because the upload is just `UploadManager.enqueue(...)`, killing the app mid-upload and
reopening it resumes the transfer — the headline CUJ, visible in the list.

**Every CUJ, how to run it, and its pass criteria are in
[docs/CUJS.md](docs/CUJS.md).** The automated subset runs
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
sample/           Compose multi-file upload-manager demo (cross-device via Firestore)
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
