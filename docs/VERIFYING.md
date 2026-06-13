# Verifying Milestone 1

This guide proves the M1 core pipeline actually works: enqueue → Room queue →
WorkManager dispatch → resumable Firebase Storage upload → resume after process
death → pause/resume/cancel → long-horizon retry.

There are three tiers, fastest first. Tier 1 runs anywhere; Tiers 2–3 need an
Android emulator (or device) and the Firebase Emulator Suite. The real proof of
the SDK's headline guarantee — resuming an interrupted upload from the last
byte — is the **force-stop test in Tier 3**.

## Prerequisites

| Tool | Needed for | Install |
| --- | --- | --- |
| JDK 17+ | everything | Temurin 17/21 |
| Android SDK (cmdline-tools or Android Studio) | Tiers 1–3 | Android Studio, then accept SDK licenses |
| Firebase CLI | Tiers 2–3 | `npm install -g firebase-tools` |
| Android emulator or USB device (API 26+) | Tiers 2–3 | Android Studio AVD Manager |

Create a `local.properties` in the repo root pointing at your SDK (Android
Studio does this automatically when you open the project):

```
sdk.dir=/Users/<you>/Library/Android/sdk    # macOS
# sdk.dir=/home/<you>/Android/Sdk           # Linux
```

## Tier 1 — Build + unit tests (no device, ~2 min)

```bash
./gradlew :upload-manager:test :upload-manager:assembleRelease :sample:assembleDebug
```

**Pass criteria:** `BUILD SUCCESSFUL`, 26 unit tests green. This is exactly what
CI runs on every push (currently green on the latest commit). It verifies:

- retry classification per error type and the park-delay/TTL math (`RetryClassifierTest`, `RetryPolicyTest`)
- Room state transitions, atomic session-URI persistence, compare-and-set guards (`UploadTaskDaoTest`)
- WorkManager constraint mapping per priority — P0 expedited, P4 charging-gated, large files forced to unmetered (`UploadSchedulerTest`)

HTML report if anything fails: `upload-manager/build/reports/tests/testReleaseUnitTest/index.html`.

## Tier 2 — Emulator end-to-end (~10 min)

These tests upload real bytes to the Storage emulator and assert the durability
guarantee (session URI persisted mid-flight).

**Terminal A** — start the emulators (Auth on 9099, Storage on 9199):

```bash
cd firebase
firebase emulators:start --project demo-upload-manager
```

**Terminal B** — with an emulator/device running (`adb devices` shows one):

```bash
./gradlew :upload-manager:connectedDebugAndroidTest
```

**Pass criteria:** both tests in `UploadE2eTest` pass:

- `smallUploadCompletesEndToEnd` — task reaches `COMPLETED` with a non-null download URL and 100% progress.
- `sessionUriIsPersistedDuringLargeUpload` — the resumable session URI lands in Room **while** a 24 MB upload is in flight (this is what makes resume-after-death possible).

You can also watch the uploaded objects appear under `users/{uid}/files/...`
in the Emulator UI (printed in Terminal A, usually http://127.0.0.1:4000).

> Host note: `10.0.2.2` is the emulator's alias for your host machine and only
> works on an **Android emulator**. On a physical device, start the emulators
> with `firebase emulators:start --host 0.0.0.0` and change `EMULATOR_HOST` in
> `SampleApp.kt` / `UploadE2eTest.kt` to your computer's LAN IP.

## Tier 3 — Manual sample app (the real-world proof)

```bash
# emulators still running from Tier 2
./gradlew :sample:installDebug
adb shell am start -n dev.uploadmanager.sample/.MainActivity
adb logcat -s UploadManager:D            # watch SDK events in another terminal
```

Run these scenarios:

**A. Happy path.** Tap **Pick & upload**, choose any file. The list row shows
a live progress bar to 100% and state `COMPLETED`. Logcat shows
`sdk.upload.enqueued → started → progress… → completed`.

**B. Resume after process death — the headline guarantee.** Pick a large file
(≥ 50 MB so it doesn't finish instantly). While it's mid-upload:

```bash
adb shell am force-stop dev.uploadmanager.sample
```

Reopen the app (`adb shell am start ...`). **Pass criteria:** the upload
continues from roughly where it stopped — *not* from 0% — and completes.
Confirm in Logcat that the restart logged `sdk.upload.started` with
`resuming=true`. This proves the session URI + offset survived process death in
Room.

**C. Network loss → park → recovery.** Start an upload, then toggle airplane
mode on. The row moves to `RETRYING`, then `PARKED` (no progress, low power).
Turn networking back on — the parked task is re-dispatched and completes
without you touching it.

**D. Pause / resume / cancel.** Use the per-row buttons. Pause halts progress
and shows `PAUSED`; Resume continues from the same offset; Cancel moves it to
`CANCELLED` and stops the transfer. **Clear completed** removes finished rows.

## Quick "is it actually durable?" inspection

While an upload is in flight or paused, dump the on-device queue to confirm the
session URI and byte offset are persisted (this is the source of truth the SDK
resumes from):

```bash
adb shell "run-as dev.uploadmanager.sample sqlite3 \
  /data/data/dev.uploadmanager.sample/databases/upload_manager.db \
  'SELECT id, uploadState, uploadedBytes, uploadSessionUri IS NOT NULL FROM upload_tasks;'"
```

A row in `UPLOADING`/`PAUSED` with a non-null session URI and a growing
`uploadedBytes` is exactly what enables resume-from-offset.

## Troubleshooting

- **`SDK location not found`** → add `local.properties` (see Prerequisites).
- **`connectedAndroidTest` finds no devices** → `adb devices` must list one; start an AVD or plug in a device with USB debugging.
- **Uploads stay `QUEUED` and never start** → the constraints aren't met. Default config waits for an unmetered network for files > 10 MB; an emulator reports WiFi, a device on cellular will hold large files until WiFi.
- **Auth/Storage connection refused** → emulators not running, or you're on a physical device using `10.0.2.2` (see the host note in Tier 2).
