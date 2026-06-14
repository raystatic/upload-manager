# Understanding the SDK — a guided walkthrough

This is the "learn by running it" companion. By the end you'll be able to run
every use case **and explain to someone else why it works**. It pairs with
[CUJS.md](CUJS.md) (the exhaustive checklist) and
[ARCHITECTURE.md](ARCHITECTURE.md) (the code map).

---

## Part 0 — Get it running (≈10 minutes)

You need: **Android Studio** (installs the Android SDK + an emulator) and the
**Firebase CLI** (`npm install -g firebase-tools`). Full toolchain notes are in
[VERIFYING.md](VERIFYING.md).

```bash
# Terminal 1 — the fake backend (Auth + Firestore + Storage), no real Firebase needed
cd firebase && firebase emulators:start --project demo-upload-manager

# Terminal 2 — install the sample on a running emulator
./gradlew :sample:installDebug
adb shell am start -n dev.uploadmanager.sample/.MainActivity

# Terminal 3 — watch what the SDK is doing
adb logcat -s UploadManager:D
```

The sample is a **CUJ runner**: a config-preset card at the top, a column of
one-tap CUJ buttons (each makes its own test file), a live **Recent events** log,
and the list of uploads with pause/resume/cancel/retry. You never need to hunt
for a file — just tap a button.

---

## Part 1 — The mental model (explain this first)

Everything in the SDK is three moving parts plus a bridge. If you understand
these, every use case becomes "obvious."

1. **Room (a local database) is the source of truth.** Every upload is a *row* on
   disk: the file, its state, bytes done so far, and a resume token. Because it's
   on disk, it survives the app being killed or the phone rebooting.
2. **WorkManager is the engine.** It's Android's system for background jobs that
   *survive app-close and reboot* and respect OS rules (network, battery,
   charging). Each upload is one WorkManager job. WorkManager decides **when** to
   run it.
3. **Firebase Storage is the destination**, and crucially it does **resumable**
   uploads: when an upload starts, the server gives back a *session URI* (a resume
   token) that lets you continue from the last received byte instead of starting
   over.
4. **The worker is the bridge** ([`FirebaseUploadWorker`](../upload-manager/src/main/kotlin/dev/uploadmanager/worker/FirebaseUploadWorker.kt)):
   it reads the row, talks to Firebase, and writes progress back to the row.

> **The one-sentence pitch:** *"We write the upload down on disk before we touch
> the network, run it through Android's background-job engine, and use Firebase's
> resume token — so it survives anything and continues where it left off."*

Lifecycle of one upload:

```
enqueue() ──► Room row (PENDING) ──► WorkManager job ──► worker runs
   worker: snapshot/hash the file ─► check for duplicate ─► upload (resumable)
        ──► on each chunk: write bytes + (once) the resume token into the row
        ──► success: mark COMPLETED, save download URL
        ──► failure: classify → retry (soon) or park (later)
```

---

## Part 2 — The use cases (run each, then explain it)

Each one below: **the real problem → do this in the app → what you'll see → why
it works → the one line to say out loud.** Use the **Default** preset unless told
otherwise (top card → "Change preset").

### 1. A file uploads at all

- **Problem:** the baseline — get bytes to the cloud.
- **Do:** tap **CUJ: upload small file**.
- **See:** a row goes `QUEUED → UPLOADING → COMPLETED`, progress fills, and the
  object appears in the Storage Emulator UI (http://127.0.0.1:4000) under
  `users/{uid}/files/...`.
- **Why:** `enqueue()` writes a Room row (`PENDING`) *before any network*, then
  the scheduler hands WorkManager a job; the worker streams the file to Firebase
  and writes progress back to the row; on success it records `COMPLETED` + the
  download URL.
- **Say it:** *"Enqueue durably records the job; WorkManager runs it in the
  background; the worker does the transfer and reports progress."*

### 2. Resume after the app is killed (the headline feature)

- **Problem:** the user swipes the app away (or Android kills it) mid-upload —
  naive code restarts from 0%.
- **Do:** tap **upload large file**; while it's at, say, 30%, run
  `adb shell am force-stop dev.uploadmanager.sample`, then reopen the app.
- **See:** it **continues from ~30%, not 0%**; Logcat shows
  `started(resuming=true)`.
- **Why:** when the upload begins, Firebase issues a *session URI* (resume token).
  The worker writes that token — together with the byte offset — into the Room
  row **atomically, the instant it's issued**
  ([`saveSession`](../upload-manager/src/main/kotlin/dev/uploadmanager/db/UploadTaskDao.kt)).
  The row is on disk, so it survives the kill. On relaunch the SDK re-dispatches
  the job; the new worker finds the saved token and tells Firebase *"continue this
  session,"* so only the remaining bytes are sent.
- **Say it:** *"Because the resume token is saved to disk the moment the server
  issues it, a brand-new worker after a crash picks up exactly where the dead one
  left off."*
- **Peek inside:** open Android Studio → App Inspection → **Database Inspector** →
  `upload_tasks` while it uploads: you'll see `uploadSessionUri` populated and
  `uploadedBytes` climbing. *That row is the magic.*

### 3. Pause, resume, cancel

- **Do:** on an in-progress large upload, tap **Pause**, then **Resume**, then on
  a fresh one tap **Cancel**.
- **See:** `PAUSED` halts progress; `Resume` continues from the same offset;
  `Cancel` stops it and marks `CANCELLED`.
- **Why:** the SDK keeps the live Firebase upload objects in a small in-memory
  registry; pause/resume/cancel call Firebase's own `pause()/resume()/cancel()`
  and flip the row's state to match. If the process died in between, resume falls
  back to the session URI (same mechanism as #2).
- **Say it:** *"User controls map straight onto Firebase's built-in
  pause/resume/cancel, with the database row kept in sync so the UI and the queue
  always agree."*

### 4. Survive a flaky network (retry, then "park")

- **Do:** start **upload large file**, then `adb shell svc wifi disable`. Wait. Then
  `adb shell svc wifi enable`.
- **See:** the row shows `RETRYING`; if the outage is long it becomes `PARKED`
  (no progress, no battery use); when the network returns it **completes on its
  own**.
- **Why:** on failure, a pure classifier
  ([`RetryClassifier`](../upload-manager/src/main/kotlin/dev/uploadmanager/retry/RetryClassifier.kt))
  decides: *transient* → ask WorkManager to retry with exponential backoff
  (WorkManager owns the timing). After a few fast attempts it **parks** the task —
  just a row marked `PARKED`, no running job — re-woken later by a connectivity
  change, charging, or a once-a-day sweep, up to a 7-day deadline. *Permanent*
  errors (quota exceeded, file deleted) fail immediately instead of looping.
- **Say it:** *"Short hiccups get quick backoff; long outages get parked and
  retried over hours and days, so the upload eventually finishes with zero user
  effort — while genuinely hopeless errors fail fast."*

### 5. The user edits or deletes the file after queuing it

- **Do (Default):** tap **enqueue then delete source** → it still **completes**.
- **Do (Reference/no-staging preset):** same button → it **fails with
  `SOURCE_GONE`**.
- **Why:** by default the SDK **copies the file into its own private folder the
  moment you enqueue** (computing the SHA-256 in the same pass) and uploads that
  *snapshot* — so deleting or editing the original can't touch the upload. For
  files too large to copy, it instead records the file's size + timestamp and
  re-checks before uploading: *gone* → fail fast; *changed* → restart from zero
  (so it never splices old + new bytes into a corrupt object).
  ([`FileStager`](../upload-manager/src/main/kotlin/dev/uploadmanager/internal/FileStager.kt),
  [`SourceChecker`](../upload-manager/src/main/kotlin/dev/uploadmanager/internal/SourceChecker.kt))
- **Say it:** *"We upload a snapshot, not the live file, so the user can delete or
  edit the original without breaking — or silently corrupting — the upload."*

### 6. The same file twice doesn't upload twice (dedup)

- **Do:** tap **CUJ: dedup**, wait for `COMPLETED`, then tap it **again**.
- **See:** the second task is `DEDUP_HIT` — instant, **zero bytes transferred**.
- **Why:** the content hash *is* the address — objects live at
  `users/{uid}/files/{sha256}`. Before uploading, the worker checks a small
  **per-user** Firestore index keyed by that hash
  ([`DeduplicationEngine`](../upload-manager/src/main/kotlin/dev/uploadmanager/dedup/DeduplicationEngine.kt));
  if it's already there, it skips the upload and points at the existing object.
  It's *per-user* (no cross-user data leak) and *best-effort* (if Firestore is
  down it just uploads normally).
- **Say it:** *"Same bytes mean the same address; we check a per-user index first
  and skip re-uploading content the user already has — without ever leaking one
  user's files to another."*

### 7. Optional cross-device visibility (Firestore sync)

- **Do:** switch to the **Firestore sync FULL** preset, upload a file, watch the
  Firestore Emulator UI.
- **See (Default):** nothing is written to Firestore. **(FULL):** a `files/{id}`
  record appears **at completion**, plus a lifecycle projection.
- **Why:** Room is always the truth; Firestore is an *optional, best-effort*
  mirror that never blocks an upload. The file record is written **only when the
  upload actually finishes** (so there are no "phantom" records for uploads that
  never completed), and per-chunk progress is never mirrored (that would cost a
  database write per chunk).
- **Say it:** *"Cross-device visibility is opt-in, written only on success so it's
  cheap and never lies about what's stored."*

### 8. Don't cook the battery (adaptive concurrency)

- **Do:** queue a couple of uploads, then
  `adb shell dumpsys battery set level 15 && adb shell dumpsys battery unplug`.
  Recover with `adb shell dumpsys battery set ac 1` (and `reset` after).
- **See:** fewer uploads run at once; a large one moves to `PARKED` /
  `DEVICE_BUSY`; on charge, concurrency widens and it resumes.
- **Why:** a **governor**
  ([`ConcurrencyGovernor`](../upload-manager/src/main/kotlin/dev/uploadmanager/internal/ConcurrencyGovernor.kt))
  caps how many transfers run at once, and that cap is recomputed from battery %,
  charging, network type, and thermal state
  ([`ConcurrencyPolicy`](../upload-manager/src/main/kotlin/dev/uploadmanager/internal/DeviceConditions.kt)).
  On a hot device it drops to zero — all transfers pause — and resumes on
  cooldown.
- **Say it:** *"A governor turns the concurrency dial down when the device is low
  or hot, and back up when it recovers — so big backups never drain or overheat
  the phone."*

### 9. Survive a reboot

- **Do:** get a task to `QUEUED`/`PARKED` (e.g. enqueue while offline), then
  `adb reboot`, and reopen the app.
- **See:** the task is picked back up and finishes — nothing lost.
- **Why:** WorkManager persists its jobs across reboot **by design**, the Room
  rows are on disk, and on startup the SDK reconciles (re-dispatches anything
  still pending). Two independent "survive reboot" guarantees stacked together.
- **Say it:** *"Both the queue (Room) and the scheduler (WorkManager) are built to
  survive reboot, and the SDK re-checks on startup, so a reboot is a non-event."*

---

## Part 3 — How to "look inside" while testing

These four windows turn the SDK from a black box into something you can narrate:

| Window | Open it via | What it tells you |
| --- | --- | --- |
| **Recent events** (in-app) / **Logcat** `UploadManager` | the app / `adb logcat -s UploadManager:D` | the live story: enqueued → started(resuming) → progress → completed / DEDUP_HIT / PARKED / FAILED |
| **Database Inspector** | Android Studio → App Inspection → `upload_manager.db` → `upload_tasks` | the source of truth: `uploadState`, `uploadedBytes`, `uploadSessionUri`, `checksum`, `stagedPath`, `parkCount` |
| **Background Task Inspector** | Android Studio → App Inspection | the WorkManager jobs and the constraints they're waiting on (network, charging) |
| **Storage / Firestore Emulator UI** | http://127.0.0.1:4000 | the actual stored objects (`users/{uid}/files/...`, content-addressed) and any Firestore records |

**A great way to teach it:** run use case #2 (resume after death) with the
Database Inspector open. Point at the `uploadSessionUri` and `uploadedBytes`
fields filling in, force-stop the app, and show that the row is still there when
you reopen — then watch the upload continue from that exact offset. That single
demo makes the whole "Room is the truth, the resume token is the magic" model
click for anyone.
