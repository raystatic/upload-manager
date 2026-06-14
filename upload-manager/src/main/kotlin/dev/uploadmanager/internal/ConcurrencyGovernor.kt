package dev.uploadmanager.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException

/**
 * A resizable, suspending concurrency gate (the review's answer to "WorkManager
 * can't dynamically throttle in-flight workers" — spec §10.2). Workers wrap their
 * transfer in [withSlot]; the battery/thermal monitor calls [setLimit] to widen or
 * narrow how many may run at once. Lowering the limit never preempts in-flight
 * work — it just makes the next acquirers wait until active drops below the limit.
 */
class ConcurrencyGovernor(initialLimit: Int) {

    private val lock = Any()
    private var limit = initialLimit.coerceAtLeast(0)
    private var active = 0
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> withSlot(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire() {
        val waiter = synchronized(lock) {
            if (active < limit) {
                active++
                null
            } else {
                CompletableDeferred<Unit>().also { waiters.addLast(it) }
            }
        } ?: return

        try {
            waiter.await() // resumes only once promote() has reserved a slot for us
        } catch (e: CancellationException) {
            synchronized(lock) {
                if (!waiters.remove(waiter)) {
                    // We were already promoted (a slot was reserved): hand it back.
                    active--
                    promote()
                }
            }
            throw e
        }
    }

    private fun release() {
        synchronized(lock) {
            if (active > 0) active--
            promote()
        }
    }

    fun setLimit(newLimit: Int) {
        synchronized(lock) {
            limit = newLimit.coerceAtLeast(0)
            promote()
        }
    }

    /** (active, limit) snapshot for diagnostics/tests. */
    fun snapshot(): Pair<Int, Int> = synchronized(lock) { active to limit }

    /** Reserve free slots for queued waiters; runs under [lock]. */
    private fun promote() {
        while (active < limit && waiters.isNotEmpty()) {
            active++
            waiters.removeFirst().complete(Unit)
        }
    }
}
