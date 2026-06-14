package dev.uploadmanager.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadState

/**
 * Long-horizon re-dispatch (revision doc 02 §3): re-enqueues PARKED tasks whose
 * parkedUntil has elapsed, fails tasks past their TTL, and re-dispatches any
 * dispatchable task that lost its WorkRequest (e.g. cleared app data edge cases).
 */
internal class ParkedSweepWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val core = UploadManager.coreOrNull(applicationContext) ?: return Result.success()
        val now = System.currentTimeMillis()
        val policy = core.config.retryPolicy

        for (task in core.dao.getParkedDue(now)) {
            if (policy.isExpired(task.firstAttemptAt, now)) {
                core.dao.updateState(task.id, UploadState.FAILED, "TTL_EXCEEDED", now)
                core.events.emit(
                    dev.uploadmanager.api.UploadEvent.Failed(task.id, "TTL_EXCEEDED")
                )
            } else {
                core.dao.updateState(task.id, UploadState.QUEUED, task.errorCode, now)
                core.scheduler.redispatch(task)
            }
        }

        // Belt-and-braces: anything dispatchable should always have a WorkRequest
        // (KEEP makes this idempotent if it already does).
        for (task in core.dao.getDispatchable()) {
            core.scheduler.dispatch(task)
        }

        // Keep kicking while anything remains parked.
        if (core.dao.countParked() > 0) {
            core.scheduler.scheduleParkKick(policy.parkDelaysMs.first())
        }
        return Result.success()
    }
}
