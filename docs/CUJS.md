# Critical User Journeys — one place to test everything

This is the single source of truth for verifying the SDK. It maps every CUJ to
**a button (and config preset) in the sample app**, plus the adb commands and
pass criteria. Many are also automated as instrumented tests (`Auto?` column).

## Setup

```bash
# 1. Firebase emulators (Auth, Firestore, Storage)
cd firebase && firebase emulators:start --project demo-upload-manager
# 2. Sample app
./gradlew :sample:installDebug && adb shell am start -n dev.uploadmanager.sample/.MainActivity
# 3. Watch SDK events
adb logcat -s UploadManager:D
```

The sample's top card shows the active **config preset**. Tap **Change preset**
to switch (Default, Reference/no-staging, Copy always, Dedup off, Firestore sync
FULL, Adaptive off, WiFi only) — it persists the choice and restarts the app. The
in-app **Recent events** card mirrors the structured events for quick feedback.

### Observation toolkit

| Tool | Use |
| --- | --- |
| In-app Recent events / Logcat `UploadManager` | `enqueued, started(resuming), paused, retry, PARKED, completed, DEDUP_HIT, FAILED` |
| App Inspection → **Background Task Inspector** | live WorkManager workers + constraints |
| App Inspection → **Database Inspector** (`upload_manager.db`) | `uploadState, uploadedBytes, uploadSessionUri, checksum, stagedPath, parkCount` |
| Storage Emulator UI (http://127.0.0.1:4000) | objects under `users/{uid}/files/...`; custom metadata `checksum` |
| `adb shell run-as dev.uploadmanager.sample ls -l files/upload_staging` | live staged snapshots |

adb cheatsheet:

```bash
adb shell am force-stop dev.uploadmanager.sample      # process death
adb shell svc wifi disable | enable                    # drop / restore network
adb shell dumpsys battery set level 15                 # low battery
adb shell dumpsys battery unplug | set ac 1            # not charging / charging
adb shell dumpsys battery reset                        # restore
adb reboot                                             # reboot resilience
```

---

## Core upload & durability

| ID | What it proves | How (preset · action) | Pass | Auto? |
| --- | --- | --- | --- | --- |
| C-01 | Happy-path upload | Default · **upload small file** | `COMPLETED`, 100%, object in Storage | ✅ |
| C-02 | Background upload | Default · upload small, press Home | completes with app backgrounded | |
| C-03 | Metadata attached | Default · any upload | object has content-type + `originalFilename`/`uploadedBy`/`sdkVersion`/`checksum` | |
| C-04 | **Resume after process death** | Default · **upload large file**, then `am force-stop`, reopen | resumes (not 0%); Logcat `started(resuming=true)` | partial¹ |
| C-05 | Session URI persisted mid-flight | Default · upload large; Database Inspector | `uploadSessionUri` set + `uploadedBytes` climbing while `UPLOADING` | ✅ |

¹ The session-URI persistence is automated; true process-death resume is manual
(the Storage emulator can't reliably finish a resumed session).

## Lifecycle controls

| ID | What it proves | How | Pass | Auto? |
| --- | --- | --- | --- | --- |
| L-01 | Pause / resume | upload large → **Pause** → **Resume** | halts at `PAUSED`, resumes from offset | ✅² |
| L-02 | Cancel | upload large → **Cancel** | `CANCELLED`, transfer stops, no object | ✅ |
| L-03 | Retry | on a `FAILED`/`CANCELLED` row → **Retry** | re-runs from `PENDING`, counters reset | |
| L-04 | Clear completed | **Clear completed** | only terminal rows removed | |

² Automated up to "resume re-activates the task"; full resume-to-COMPLETED is manual.

## Retry & resilience

| ID | What it proves | How | Pass | Auto? |
| --- | --- | --- | --- | --- |
| R-01 | Transient blip → fast retry | upload large; `svc wifi disable` ~5 s then `enable` | `RETRYING` → recovers automatically | |
| R-02 | Outage → PARKED → recovery | upload large; `svc wifi disable` ~1–2 min | `PARKED`; re-enable network → completes untouched | |
| R-03 | TTL → FAILED | (clock-dependent) set `firstAttemptAt` back in Database Inspector | `FAILED` / `TTL_EXCEEDED` | ✅³ |

³ The TTL/park math is covered by `RetryPolicyTest`.

## Staging & source durability (revision doc 03)

| ID | What it proves | How (preset · action) | Pass | Auto? |
| --- | --- | --- | --- | --- |
| S-01 | Staged by default + hashed | Default · upload small; Database Inspector | `stagedPath` + `checksum` set; file in `files/upload_staging/` | ✅ |
| S-02 | Cleanup on completion | Default · upload small | staged file gone after `COMPLETED` | ✅ |
| S-03 | **Source immutability** | Default · **enqueue then delete source** | completes with original bytes (snapshot) | ✅ |
| S-04 | Deleted source → terminal | **Reference (no staging)** · enqueue then delete source | `FAILED` / `SOURCE_GONE`, no retry loop | |
| S-05 | Changed source → restart | **Reference (no staging)** · upload large, Pause, edit the file, Resume | restarts from 0; Logcat `source_changed` | |
| S-06 | Copy mode stages large files | **Copy always** · upload large | `stagedPath` set despite > 64 MB | |

## Deduplication (revision doc 01)

| ID | What it proves | How | Pass | Auto? |
| --- | --- | --- | --- | --- |
| D-01 | Repeat content is deduped | Default · **dedup** button once → wait `COMPLETED` → tap again | second task → `DEDUP_HIT` with a download URL, zero bytes | ✅ |
| D-02 | Dedup off | **Dedup off** · dedup button twice | both upload normally (`COMPLETED`) | |
| D-03 | Content-addressed path | Default · any staged upload; Storage UI | object at `users/{uid}/files/{checksum}` | ✅ |

## Firestore sync (revision doc 04)

| ID | What it proves | How (preset) | Pass | Auto? |
| --- | --- | --- | --- | --- |
| F-01 | No mirroring by default | Default · upload small | no `users/{uid}/files` or `uploadTasks` docs in Firestore UI | |
| F-02 | Terminal/full mirroring | **Firestore sync FULL** · upload small | `files/{taskId}` written at completion; `uploadTasks/{taskId}` lifecycle; **no** `uploadedBytes` | |

## Adaptive concurrency (spec §10)

| ID | What it proves | How (preset) | Pass | Auto? |
| --- | --- | --- | --- | --- |
| A-01 | Throttle on low battery | Default · queue several + **upload large**; `dumpsys battery set level 15 && unplug` | fewer run at once; large → `PARKED`/`DEVICE_BUSY` | ✅⁴ |
| A-02 | Recovery on charge | then `dumpsys battery set ac 1` | concurrency widens; large resumes | |
| A-03 | Thermal pause | physical device under heavy load → MODERATE+ | all transfers pause, resume on cooldown | ✅⁴ |

⁴ The battery/thermal → concurrency decision is covered by `ConcurrencyPolicyTest`
and the gate by `ConcurrencyGovernorTest`.

## Edge cases

| ID | What it proves | How | Pass |
| --- | --- | --- | --- |
| E-01 | Enqueue without auth rejected | sign out / kill Auth emulator, then a CUJ button | status shows `IllegalStateException` |
| E-02 | Per-uid isolation | sign in as a second user | `observeAll` shows only that user's tasks |
| E-03 | Large-file foreground service | upload large (≥ 50 MB) | ongoing upload notification |
| E-04 | **Reboot resilience** | get a task to `QUEUED`/`PARKED`, `adb reboot`, reopen | picked back up and completes |

---

## Done = verified

The SDK is verified when the ✅ rows pass in CI (they run on every push via the
instrumented job) **and** you have manually confirmed the headline manual CUJs:
**C-04** (resume after death), **R-02** (park → recovery), **S-04/S-05**
(source-gone / restart-on-change), **A-01/A-02** (battery throttling), and
**E-04** (reboot). Everything else is a quick tap in the sample.
