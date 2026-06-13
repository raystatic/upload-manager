# M2 Staging — Test Plan (revision doc 03)

CUJs for source-file staging. The pure logic (config thresholds, copy + SHA-256,
budget, cleanup, reconcile, hex) is already covered by `StagingConfigTest` and
`FileStagerTest` in CI. The cases below verify the **integration** on a device /
emulator with the sample app and the Firebase Emulator Suite running.

Legend: ✅ pass, 👁 observe, ⚙️ setup.

## Observation toolkit (staging-specific)

| Tool | Use |
| --- | --- |
| **Database Inspector** → `upload_tasks` | `stagedPath` (non-null = staged), `checksum`, `sourceSizeBytes`, `sourceLastModified` |
| **Staging dir** via adb | `adb shell run-as dev.uploadmanager.sample ls -l files/upload_staging` — shows live staged files (named `{taskId}`) |
| **Storage Emulator UI** | a completed object's custom metadata includes `checksum` |
| **Logcat** `UploadManager` | `sdk.upload.source_changed` when a non-staged source changed |

## Changing config for the non-default cases

Some CUJs need a non-default `StagingConfig`. Edit the sample's
`SampleApp.kt` and rebuild:

```kotlin
import dev.uploadmanager.api.StagingConfig
import dev.uploadmanager.api.StagingMode

UploadManager.initialise(
    this,
    UploadManagerConfig(
        enableLogging = true,
        // pick the variant under test:
        staging = StagingConfig(autoCopyBelowBytes = 0),        // S7/S8/S9: never stage (REFERENCE path)
        // staging = StagingConfig(mode = StagingMode.COPY),    // S10: always stage
        // staging = StagingConfig(stagingDirMaxBytes = 1024),  // S6: tiny budget → fallback
    ),
)
```

---

## Default behavior (no config change)

**S1 — Small file is staged.** Enqueue a ~5 MB file.
✅ Before/while uploading, the row has `stagedPath` non-null and `checksum` set,
and `run-as … ls files/upload_staging` shows a file named with the task id.
👁 Database Inspector + the adb `ls`.

**S2 — Upload uses the snapshot + checksum metadata.** Let S1 complete.
✅ Upload succeeds; in the Storage UI the object's custom metadata `checksum`
matches the row's `checksum`.

**S3 — Cleanup on completion.** After S1 completes, `run-as … ls
files/upload_staging`.
✅ The staged file is gone (deleted on `COMPLETED`).

**S4 — Source immutability (the core guarantee).** Enqueue a ~5 MB file **while
offline** (`⚙️ adb shell svc wifi disable`) so it queues. Now **delete or edit the
original file** on the device. Re-enable the network (`svc wifi enable`).
✅ The upload completes with the **original** bytes — deleting/editing the source
after enqueue has no effect, because the staged snapshot is what's uploaded.

**S5 — Cleanup on cancel.** Start an upload, tap **Cancel**, then `run-as … ls`.
✅ The staged file is removed.

**S10 — Resume-after-death with a staged file.** Pick a larger-but-still-staged
file (e.g. 30–60 MB, under the 64 MB auto-copy threshold). Mid-upload `⚙️ adb
shell am force-stop …`, then reopen.
✅ Resumes from the offset (Logcat `started(resuming=true)`) — the staged copy
survived process death, so resume reads it.

## Orphan reconciliation

**S6 — Startup sweep deletes orphans.** Plant a stray staged file:
```
adb shell run-as dev.uploadmanager.sample sh -c 'mkdir -p files/upload_staging; echo x > files/upload_staging/orphan'
```
Force-stop and relaunch the app.
✅ After launch, `run-as … ls files/upload_staging` no longer lists `orphan`
(it matches no active task, so `reconcile` removed it).

## Budget fallback

**S7 — Over-budget falls back to REFERENCE.** ⚙️ Set
`staging = StagingConfig(stagingDirMaxBytes = 1024)` and enqueue a file > 1 KB.
✅ `stagedPath` is **null** (staging skipped), yet the upload still completes —
it streamed directly from the source URI.

## Non-staged (REFERENCE) source validation

Use `⚙️ staging = StagingConfig(autoCopyBelowBytes = 0)` so even small files are
**not** staged (lets you exercise the reference path without a >64 MB file).

**S8 — Deleted source → terminal, no retry loop.** Enqueue a file while offline
(so it queues), delete the original, then reconnect.
✅ Task → `FAILED` with `errorCode = SOURCE_GONE`; it does **not** retry forever.

**S9 — Changed source → restart from zero.** Start an upload, **Pause**, modify the
original file (change its size), then **Resume**.
✅ Upload restarts from 0 (`uploadedBytes` resets, `sourceSizeBytes` updates),
Logcat logs `sdk.upload.source_changed`, and it completes with the new content —
no spliced/corrupt object.

## COPY mode

**S11 — COPY always stages, even large files.** ⚙️ Set
`staging = StagingConfig(mode = StagingMode.COPY)` and enqueue a file **above**
64 MB.
✅ `stagedPath` is non-null despite exceeding the auto-copy threshold; the upload
reads the snapshot.

---

## Done = staging verified

Staging is working when **S1–S5, S6, S7, S8, S9, S10** pass. S11 is optional
(only matters if you ship `COPY` mode). S8/S9 require the `autoCopyBelowBytes = 0`
config so small files take the reference path.
