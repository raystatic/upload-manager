package dev.uploadmanager.retry

import com.google.firebase.storage.StorageException
import java.io.IOException
import java.util.concurrent.CancellationException

/**
 * Pure error classifier per revision doc 02 §2. WorkManager owns all retry timing;
 * this only decides what kind of failure occurred.
 */
enum class FailureAction {
    /** Transient: return Result.retry(); WorkManager applies exponential backoff. */
    RETRY_FAST,

    /** Auth token likely expired mid-upload: refresh in-worker, then one immediate retry. */
    REFRESH_AUTH,

    /** Fast tier exhausted or long-outage error: park the task (revision doc 02 §3). */
    PARK,

    /** Permanent: mark FAILED/CANCELLED and stop. */
    TERMINAL,
}

object RetryClassifier {

    /**
     * @param attemptsInTier WorkManager runAttemptCount for the current WorkRequest
     *   (resets when a parked task is re-dispatched as a fresh request).
     */
    fun classify(e: Throwable, attemptsInTier: Int, policy: RetryPolicy): FailureAction {
        if (e is CancellationException) return FailureAction.TERMINAL

        val terminalOrNull = classifyPermanent(e)
        if (terminalOrNull != null) return terminalOrNull

        return if (attemptsInTier >= policy.fastTierMaxAttempts) {
            FailureAction.PARK
        } else {
            FailureAction.RETRY_FAST
        }
    }

    private fun classifyPermanent(e: Throwable): FailureAction? {
        // No StorageException anywhere in the chain (e.g. plain IOException): transient.
        val storage = e.findCause<StorageException>() ?: return null
        return when (storage.errorCode) {
            StorageException.ERROR_QUOTA_EXCEEDED -> FailureAction.TERMINAL
            StorageException.ERROR_NOT_AUTHENTICATED,
            StorageException.ERROR_NOT_AUTHORIZED -> FailureAction.REFRESH_AUTH
            StorageException.ERROR_CANCELED -> FailureAction.TERMINAL
            StorageException.ERROR_BUCKET_NOT_FOUND,
            StorageException.ERROR_PROJECT_NOT_FOUND -> FailureAction.TERMINAL
            // Firebase's own retry window exceeded: re-initiate the UploadTask (fast tier).
            StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> null
            StorageException.ERROR_INVALID_CHECKSUM -> null
            else -> {
                val http = storage.httpResultCode
                // 408 (timeout) and 429 (rate limit) are retriable; other 4xx are permanent.
                if (http in 400..499 && http != 408 && http != 429) FailureAction.TERMINAL else null
            }
        }
    }

    /** Human-readable error code persisted for observability (§13). */
    fun errorCode(e: Throwable): String {
        val storage = e.findCause<StorageException>()
        return when {
            storage != null -> "STORAGE_${storage.errorCode}_HTTP_${storage.httpResultCode}"
            e.findCause<IOException>() != null -> "IO_${e.javaClass.simpleName}"
            else -> e.javaClass.simpleName
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var cur: Throwable? = this
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur is T) return cur
            cur = cur.cause
            depth++
        }
        return null
    }
}
