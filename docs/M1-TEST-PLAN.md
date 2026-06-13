# M1 Critical User Journeys — Test Plan

Manual test matrix to confirm the Milestone 1 core pipeline works end to end.
Work through these on an emulator (or device) with the sample app. Most CUJs
need the **Firebase Emulator Suite running** (`cd firebase && firebase
emulators:start --project demo-upload-manager`).

Legend: ✅ = pass criteria, 👁 = where to observe, ⚙️ = setup/simulation command.

## In scope for M1 vs. deferred

**M1 covers:** enqueue, background upload, resume-after-death, pause/resume/
cancel/retry, automatic retry (fast tier → parked → TTL), network preference,
priority + constraints, progress observation, source-gone handling, reboot
resilience, per-uid queue isolation.

**Deferred — do NOT test these yet (no code in M1):** content-hash dedup
(`DEDUP_HIT`), `COPY` staging + `SOURCE_CHANGED` on resume, Firestore sync /
`SyncPolicy`, thermal + adaptive concurrency + batching, App Check,
content-addressed storage paths.

## Observation toolkit

| Tool | Use |
| --- | --- |
| **Logcat**, filter `UploadManager` | structured events: `enqueued, started(resuming=…), progress, paused, resumed, retrying, parked, completed, failed, cancelled` |
| **Android Studio → App Inspection → Background Task Inspector** | live WorkManager workers, their state, constraints, retry count |
| **Android Studio → App Inspection → Database Inspector** | open `upload_manager.db` → `upload_tasks` to see `uploadState`, `uploadedBytes`, `uploadSessionUri`, `retryCount`, `parkCount` live |
| **Storage Emulator UI** (http://127.0.0.1:4000) | confirm objects land at `users/{uid}/files/{taskId}` |

adb cheatsheet for simulating conditions:

```bash
adb shell am force-stop dev.uploadmanager.sample      # kill app (process death)
adb shell am start -n dev.uploadmanager.sample/.MainActivity
adb shell svc wifi disable   # drop network (emulator network is Wi-Fi)
adb shell svc wifi enable
adb shell dumpsys battery unplug       # appear unplugged (P4 charging gate)
adb shell dumpsys battery set ac 1     # appear charging
adb shell dumpsys battery set level 15 # low battery (P1–P4 battery-not-low gate)
adb shell dumpsys battery reset        # restore real state
adb reboot                              # reboot resilience
```

---

## A. Setup & sanity

**A1 — Init + sign-in + empty state.**
Launch the app fresh.
✅ Anonymous sign-in succeeds; the list shows the empty-state text; no crash.
👁 Logcat: no `Sign-in failed`. (If it fails, the Auth emulator isn't running.)

## B. Core upload (FR-01, FR-02)

**B1 — Small-file happy path (P0).** Pick a small file (~1–5 MB).
✅ Row goes `QUEUED → UPLOADING → COMPLETED`, progress bar hits 100%, a
download URL is recorded.
👁 Logcat `enqueued → started(resuming=false) → progress… → completed`; object
visible under `users/{uid}/files/{taskId}` in the Storage UI.

**B2 — Upload continues in background.** Start a medium upload (~20–50 MB), then
press Home / switch apps mid-upload.
✅ Upload keeps progressing and completes without the app in foreground.
👁 Background Task Inspector shows the worker running; Logcat keeps emitting
`progress`/`completed`.

**B3 — Metadata attached.** After any completed upload, inspect the object in the
Storage UI.
✅ Content-type matches the file; custom metadata includes `originalFilename`,
`uploadedBy` (= uid), `sdkVersion`, and any `app_*` keys you passed.

## C. Resumability & durability — the headline guarantee (FR-03)

**C1 — Resume after process death.** Pick a large file (≥ 50 MB). Mid-upload:
`⚙️ adb shell am force-stop dev.uploadmanager.sample`, then reopen the app.
✅ Upload **continues from where it stopped, not 0%**, and completes.
👁 Logcat after relaunch: `started(resuming=true)`. Database Inspector before the
kill shows `uploadSessionUri` non-null and `uploadedBytes` > 0.

**C2 — Session URI persisted mid-flight.** Start a large upload; open Database
Inspector while `UPLOADING`.
✅ `uploadSessionUri` becomes non-null early and `uploadedBytes` climbs in steps
(throttled ~every 512 KB). This is what makes C1 possible.

**C3 (advanced, optional) — Stale session restarts from zero.** In Database
Inspector, while a task is `PAUSED`/parked, set its `sessionCreatedAt` to a value
> 6 days ago, then resume it.
✅ The worker discards the session, logs `started(resuming=false)`, and
re-uploads from 0 (an expected transition, not an error).

## D. Lifecycle controls (FR-05, FR-12)

**D1 — Pause / Resume.** Start a large upload → tap **Pause**.
✅ State → `PAUSED`, progress halts. Tap **Resume** → continues from the same
offset to `COMPLETED`.
👁 Logcat `paused` then `resumed`; `uploadedBytes` does not reset.

**D2 — Cancel.** Start an upload → tap **Cancel**.
✅ State → `CANCELLED`, transfer stops, no further `progress`; no completed object
appears. The Cancel button is disabled once a task is terminal.
👁 Logcat `cancelled`.

**D3 — Retry a failed/cancelled task.** On a `CANCELLED` (or `FAILED`) row, tap
**Retry**.
✅ Task re-enqueues from `PENDING`, `retryCount`/`parkCount` reset to 0, and it
runs to `COMPLETED`.

**D4 — Clear completed.** With some `COMPLETED` rows present, tap **Clear
completed**.
✅ Only completed rows disappear from the list and the DB; active/paused rows
remain.

## E. Automatic retry & resilience (FR-04)

**E1 — Transient blip → fast-tier retry.** Start an upload; briefly drop the
network (`⚙️ svc wifi disable`, wait ~5 s, `svc wifi enable`).
✅ Row shows `RETRYING`, then recovers and completes on its own (no user action).
👁 Logcat `retrying(retryCount=…)` then `completed`; Background Task Inspector
shows the worker rescheduled with backoff.

**E2 — Sustained outage → PARKED → auto-recovery.** Start an upload, then keep
the network off long enough to exhaust the fast tier (`svc wifi disable`, leave
off ~1–2 min).
✅ Task transitions to `PARKED` (no active worker, no progress). Re-enable the
network (`svc wifi enable`) → it is re-dispatched and completes **without you
touching it**.
👁 Logcat `parked` then later `started`/`completed`; `parkCount` increments in DB.

**E3 (advanced, optional) — TTL exhaustion → FAILED.** Waiting 7 days is
impractical; instead set a parked task's `firstAttemptAt` to > 7 days ago in
Database Inspector, then trigger a dispatch (toggle network).
✅ Task → `FAILED` with `errorCode = TTL_EXCEEDED`. (Also covered by
`RetryPolicyTest` in unit tests.)

## F. Prioritization & constraints (FR-06, FR-07)

**F1 — Network preference / large-file-on-cellular.** This path is most
meaningful on a **real device on cellular** (an emulator reports Wi-Fi /
unmetered, so large files just run). On a device with `ALLOW_CELLULAR`: a small
file uploads on cellular; a > 10 MB file stays `QUEUED` until WiFi.
✅ Small file runs on cellular; large file waits for unmetered, then starts on
WiFi.
👁 Background Task Inspector shows the worker's unmet network constraint.

**F2 — Priority ordering.** Enqueue a large P4/P3 file, then immediately a P0
file (the sample enqueues P0 by default — temporarily change one call to a lower
priority to compare, or enqueue two quickly).
✅ The P0 task starts before the lower-priority one.
👁 List is ordered priority-first; Logcat `started` fires for P0 first.

**F3 — P4 backfill requires charging.** `⚙️ adb shell dumpsys battery unplug`,
then enqueue a P4 task.
✅ It stays `QUEUED` while "unplugged". `⚙️ dumpsys battery set ac 1` → it starts.
`⚙️ dumpsys battery reset` afterward.
👁 Background Task Inspector shows the charging constraint unmet → met.

## G. Error handling & edge cases

**G1 — Source file gone → terminal, no retry loop (revision 03).** Enqueue a file
while offline (`svc wifi disable`) so it parks/queues, delete the underlying file,
then re-enable the network.
✅ When the worker runs it fails fast to `FAILED` with `errorCode = SOURCE_GONE`
and does **not** retry forever.
👁 Logcat `failed` with `SOURCE_GONE`.

**G2 — Enqueue without auth is rejected (developer contract).** Force a
not-signed-in state (kill the Auth emulator before launch, or sign out) and try
to enqueue.
✅ `enqueue` throws `IllegalStateException` ("requires a signed-in FirebaseAuth
user"); the sample shows the error toast rather than silently dropping the file.

**G3 — Large-file foreground service.** Enqueue a file above the foreground
threshold (≥ 50 MB) and grant the notification permission.
✅ An ongoing upload notification appears while it runs; the upload survives
backgrounding the app.
👁 Visible notification; Background Task Inspector shows the worker as foreground.

**G4 — Per-uid queue isolation (FR-10).** Note user A's uid (Logcat/DB), enqueue a
task. Sign out and sign in as a different anonymous user B (clear app data or add
a sign-out path).
✅ `observeAll()` for B shows only B's tasks; A's tasks are not visible. Storage
objects are under each uid's own path.

## H. Persistence across reboot (NFR: reliability)

**H1 — Reboot resilience.** Get a task into `QUEUED`/`PARKED` (e.g. enqueue while
offline), then `⚙️ adb reboot`. After boot, relaunch the app.
✅ The task is picked back up and completes once conditions are met — nothing is
lost. (WorkManager persists across reboot; `UploadManager.initialise` reconciles
dispatchable tasks on start.)

---

## Done = M1 verified

M1 is working as expected when **A1, B1–B3, C1–C2, D1–D4, E1–E2, F2–F3, G1–G3,
H1** all pass. C3/E3 are advanced/optional (clock-dependent), and F1/G4 are best
on real devices / multi-account setups.
