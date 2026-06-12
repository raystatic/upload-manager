package dev.uploadmanager.core

import android.content.Context
import androidx.work.WorkManager
import dev.uploadmanager.api.UploadManagerConfig
import dev.uploadmanager.db.UploadDatabase
import dev.uploadmanager.db.UploadTaskDao
import dev.uploadmanager.events.UploadEvents
import dev.uploadmanager.scheduler.UploadScheduler
import dev.uploadmanager.worker.ActiveTaskRegistry
import kotlinx.coroutines.sync.Semaphore

/**
 * Process-scoped wiring for all SDK components. Created by UploadManager.initialise();
 * workers reach it through UploadManager.coreOrNull() since WorkManager constructs
 * them reflectively.
 */
class UploadManagerCore(
    context: Context,
    val config: UploadManagerConfig,
) {
    val appContext: Context = context.applicationContext
    val dao: UploadTaskDao = UploadDatabase.get(appContext).uploadTaskDao()
    val events: UploadEvents = UploadEvents(config.enableLogging)
    val registry: ActiveTaskRegistry = ActiveTaskRegistry()
    val scheduler: UploadScheduler = UploadScheduler(WorkManager.getInstance(appContext), config)

    /**
     * In-process concurrency cap (spec §10.2, M1 static form). Workers acquire a
     * permit before transferring; WorkManager-level parallelism stays untouched.
     */
    val uploadPermits: Semaphore = Semaphore(config.maxConcurrentUploads)
}
