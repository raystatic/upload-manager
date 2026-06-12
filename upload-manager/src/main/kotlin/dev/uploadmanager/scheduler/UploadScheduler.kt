package dev.uploadmanager.scheduler

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.uploadmanager.api.NetworkPreference
import dev.uploadmanager.api.UploadManagerConfig
import dev.uploadmanager.db.UploadTaskEntity
import dev.uploadmanager.worker.FirebaseUploadWorker
import java.util.concurrent.TimeUnit

/**
 * Maps tasks to WorkManager requests (spec §5). One unique WorkRequest per task,
 * ExistingWorkPolicy.KEEP, so re-dispatch is always idempotent. WorkManager owns
 * all retry timing (revision doc 02 §2).
 */
class UploadScheduler(
    private val workManager: WorkManager,
    private val config: UploadManagerConfig,
) {

    fun dispatch(task: UploadTaskEntity) {
        workManager.enqueueUniqueWork(
            uniqueName(task.id),
            ExistingWorkPolicy.KEEP,
            buildRequest(task),
        )
    }

    /** Re-dispatch after a park: the old request finished, so KEEP enqueues fresh. */
    fun redispatch(task: UploadTaskEntity) = dispatch(task)

    fun cancel(taskId: String) {
        workManager.cancelUniqueWork(uniqueName(taskId))
    }

    /** Daily sweep + a network-triggered kick whenever tasks are parked (revision 02 §3). */
    fun ensureSweepScheduled() {
        workManager.enqueueUniquePeriodicWork(
            SWEEP_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ParkedSweepWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    }

    fun scheduleParkKick(delayMs: Long) {
        workManager.enqueueUniqueWork(
            SWEEP_KICK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ParkedSweepWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build(),
        )
    }

    internal fun buildRequest(task: UploadTaskEntity): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<FirebaseUploadWorker>()
            .setInputData(workDataOf(FirebaseUploadWorker.KEY_TASK_ID to task.id))
            .addTag(TAG_UPLOAD)
            .addTag(task.id)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                config.retryPolicy.fastTierBackoffBaseMs,
                TimeUnit.MILLISECONDS,
            )
            .setConstraints(constraintsFor(task))

        if (task.priority == 0) {
            // P0: best-effort immediate start; falls back to a regular request when
            // the expedited quota is exhausted (spec §5.2; latency NFR is best-effort).
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return builder.build()
    }

    internal fun constraintsFor(task: UploadTaskEntity): Constraints {
        val needsUnmetered = when {
            config.networkPreference == NetworkPreference.WIFI_ONLY -> true
            task.fileSizeBytes > config.cellularMaxBytes -> true // large files wait for WiFi (§2.2)
            else -> false
        }
        return Constraints.Builder()
            .setRequiredNetworkType(if (needsUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .apply {
                if (task.priority == 4) setRequiresCharging(true) // P4 backfill (§5.2)
                if (task.priority in 1..4) setRequiresBatteryNotLow(true)
            }
            .build()
    }

    companion object {
        const val TAG_UPLOAD = "dev.uploadmanager.UPLOAD"
        private const val SWEEP_PERIODIC_NAME = "dev.uploadmanager.SWEEP"
        private const val SWEEP_KICK_NAME = "dev.uploadmanager.SWEEP_KICK"

        fun uniqueName(taskId: String) = "dev.uploadmanager.upload-$taskId"
    }
}
