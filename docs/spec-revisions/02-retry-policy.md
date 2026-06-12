# Revision 02 — Long-Horizon Retry Policy

> **Replaces:** §9 (Retry Engine) in full.
> **Amends:** §4.2 (UploadState machine — adds `PARKED`, adds expired-session transition), §4.1 (entity fields), §5 (scheduling responsibilities).

## 1. Problems in the v1.0 draft

**Two retry systems fight each other.** The draft layered a custom `RetryEngine` (own backoff timer, own max-retries) on top of WorkManager, which *also* has a retry mechanism (`Result.retry()` + `BackoffPolicy`). Two schedulers with overlapping responsibility is a classic source of duplicate dispatches and retry storms — e.g. the RetryEngine re-enqueues a task while WorkManager is still backing off the same WorkRequest.

**The retry horizon contradicts the reliability NFR.** §9.2 specified 5 retries capped at 32 s: a task on a flaky network exhausts all retries in **under two minutes** and lands in terminal `FAILED`. §2.2 promises *"an enqueued file must eventually reach Firebase Storage even across reboots, Doze mode cycles, and process deaths — 99.9% completion."* Those two statements cannot both be true. Real-world upload reliability is won over **hours and days**, not seconds.

**Resumable session expiry was unhandled.** GCS resumable upload sessions (which back Firebase Storage session URIs) expire after about one week. A P4 backfill task parked behind WiFi+charging constraints can easily exceed that; resuming against a dead session fails permanently unless handled.

## 2. Single scheduling owner

**WorkManager owns all retry timing.** The `RetryEngine` is demoted to a pure, side-effect-free **error classifier**:

```kotlin
enum class FailureAction {
    RETRY_FAST,     // transient: return Result.retry(); WorkManager applies backoff
    REFRESH_AUTH,   // refresh Firebase Auth token in-worker, then one immediate retry
    PARK,           // fast tier exhausted or long-outage error: park the task
    TERMINAL,       // 4xx permanent, quota exceeded, cancelled: stop
}

object RetryClassifier {
    fun classify(e: Exception, attemptsInTier: Int, policy: RetryPolicy): FailureAction = when {
        e is StorageException && e.errorCode == ERROR_QUOTA_EXCEEDED -> FailureAction.TERMINAL
        e is StorageException && e.errorCode == ERROR_NOT_AUTHORIZED -> FailureAction.REFRESH_AUTH
        e is StorageException && e.errorCode == ERROR_CANCELED       -> FailureAction.TERMINAL
        e.isPermanent4xx()                                           -> FailureAction.TERMINAL
        attemptsInTier >= policy.fastTierMaxAttempts                 -> FailureAction.PARK
        else                                                         -> FailureAction.RETRY_FAST
    }
}
```

The worker maps the classification onto WorkManager:

- `RETRY_FAST` → `Result.retry()`. The WorkRequest is built with `setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.SECONDS)`; WorkManager applies the exponential schedule and jitter. The SDK never sleeps or self-schedules a delay.
- `PARK` → write state `PARKED` to Room, return `Result.failure()` for **this WorkRequest only** (the task is not failed — see §3).
- `TERMINAL` → write state `FAILED`/`CANCELLED`, return `Result.failure()`, emit `sdk.upload.failed`.

## 3. Two-tier policy: fast tier → park tier → TTL

```text
            transient error                fast tier exhausted
 UPLOADING ────────────────▶ RETRYING ───────────────────────▶ PARKED
     ▲                          │  Result.retry()                 │
     │                          ▼  (WorkManager backoff,          │ re-dispatch trigger:
     │                       UPLOADING   ≤ fastTierMaxAttempts)   │  • connectivity change
     │                                                            │  • charging begins
     └────────────────────────────────────────────────────────────┘  • daily sweep
                                                                  │
                                              taskTtl exceeded    ▼
                                            ───────────────▶  FAILED (terminal)
```

**Fast tier** — handles radio blips and transient 5xx. In-worker, via WorkManager backoff: ~2 s, 4 s, 8 s, 16 s, 32 s (≤ `fastTierMaxAttempts = 5`).

**Park tier** — handles real outages: airplane mode, days without WiFi, server incidents. A `PARKED` task holds no WorkRequest and consumes zero resources. It is re-dispatched (fresh WorkRequest, fast-tier counter reset) by any of:

| Trigger | Mechanism |
| --- | --- |
| Connectivity restored / network type changed | `NetworkRequest` callback while process alive; otherwise the periodic sweep catches it |
| Charging begins | WorkRequest constraint on the sweep worker |
| Daily sweep | `PeriodicWorkRequest` (24 h, `KEEP`) that re-enqueues all `PARKED` tasks whose `parkedUntil` has elapsed |

Park durations escalate per park event: 15 min → 1 h → 6 h → 24 h (capped), stored as `parkedUntil` so the sweep is a single indexed Room query.

**Task TTL** — the only path to terminal failure for retriable errors. A task whose `firstAttemptAt` is older than `taskTtl` is marked `FAILED` with `errorCode = TTL_EXCEEDED` and surfaced to the host app.

```kotlin
data class RetryPolicy(
    val fastTierMaxAttempts: Int = 5,
    val fastTierBackoffBaseMs: Long = 2_000L,      // fed to setBackoffCriteria
    val parkDelaysMs: List<Long> = listOf(15.min, 1.hr, 6.hr, 24.hr),
    val taskTtl: Duration = 7.days,                // configurable per UploadManagerConfig
)
```

`maxRetries`/`retryCount` from the v1.0 entity are reinterpreted as the fast-tier counter; new entity fields: `firstAttemptAt: Long`, `parkedUntil: Long?`, `parkCount: Int`.

## 4. Session URI expiry

New entity field: `sessionCreatedAt: Long?`, written in the same Room transaction that persists `uploadSessionUri` (the §4.3 atomicity note applies to all three fields: state, URI, timestamp).

Before resuming, the worker validates the session:

```kotlin
val sessionUsable = task.uploadSessionUri != null &&
    (now() - task.sessionCreatedAt!!) < SESSION_MAX_AGE_MS   // 6 days, under GCS's ~7-day limit

val uploadTask = if (sessionUsable) {
    storageRef.putFile(localUri, metadata, Uri.parse(task.uploadSessionUri))
} else {
    dao.clearSession(task.id, now())   // uploadSessionUri = null, uploadedBytes = 0
    storageRef.putFile(localUri, metadata)
}
```

If the server rejects a resume that passed the age check (session invalidated early), the worker treats it identically: clear the session atomically, restart from byte 0, classify as `RETRY_FAST`. **Restart-from-zero is an explicit, expected transition** (`UPLOADING → UPLOADING` with session cleared), not an error state, and emits `sdk.upload.started` with `resuming = false` so the observability pipeline (§13) can track session-loss rate.

## 5. Updated state machine (amends §4.2)

| State | Change |
| --- | --- |
| `RETRYING` | Now means "WorkManager backoff in progress, fast tier". No SDK-owned timers. |
| `PARKED` | **New.** Long-horizon wait. No active WorkRequest. Re-dispatched by triggers in §3. Host-visible via `observe()` so UIs can show "waiting for network". |
| `FAILED` | Reachable only via: permanent 4xx, quota exceeded, or `taskTtl` exceeded. Never via fast-tier exhaustion alone. |

All other states are unchanged. Worth restating from the review: with these semantics, the §2.2 reliability NFR ("eventually reaches Storage across reboots and Doze") is actually enforceable, because every reboot, Doze maintenance window, and connectivity change funnels parked tasks back into dispatch.
