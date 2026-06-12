package dev.uploadmanager.retry

import java.util.concurrent.TimeUnit

/**
 * Two-tier retry policy per revision doc 02 §3.
 *
 * Fast tier: WorkManager exponential backoff, at most [fastTierMaxAttempts] runs.
 * Park tier: task held with no WorkRequest, re-dispatched by sweep/connectivity
 * triggers after an escalating delay, until [taskTtlMs] since the first attempt.
 */
data class RetryPolicy(
    val fastTierMaxAttempts: Int = 5,
    val fastTierBackoffBaseMs: Long = 2_000L,
    val parkDelaysMs: List<Long> = listOf(
        TimeUnit.MINUTES.toMillis(15),
        TimeUnit.HOURS.toMillis(1),
        TimeUnit.HOURS.toMillis(6),
        TimeUnit.HOURS.toMillis(24),
    ),
    val taskTtlMs: Long = TimeUnit.DAYS.toMillis(7),
) {
    init {
        require(fastTierMaxAttempts >= 1) { "fastTierMaxAttempts must be >= 1" }
        require(parkDelaysMs.isNotEmpty()) { "parkDelaysMs must not be empty" }
        require(taskTtlMs > 0) { "taskTtlMs must be positive" }
    }

    /** Delay before the given park event re-dispatches; escalates and caps at the last entry. */
    fun parkDelayMs(parkCount: Int): Long =
        parkDelaysMs[parkCount.coerceIn(0, parkDelaysMs.size - 1)]

    fun isExpired(firstAttemptAt: Long, now: Long): Boolean =
        firstAttemptAt > 0 && now - firstAttemptAt > taskTtlMs
}
