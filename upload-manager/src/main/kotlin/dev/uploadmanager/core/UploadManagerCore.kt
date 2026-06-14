package dev.uploadmanager.core

import android.content.Context
import androidx.work.WorkManager
import com.google.firebase.firestore.FirebaseFirestore
import dev.uploadmanager.api.UploadManagerConfig
import dev.uploadmanager.db.UploadDatabase
import dev.uploadmanager.db.UploadTaskDao
import dev.uploadmanager.dedup.DeduplicationEngine
import dev.uploadmanager.events.UploadEvents
import dev.uploadmanager.internal.FileStager
import dev.uploadmanager.scheduler.UploadScheduler
import dev.uploadmanager.sync.FirestoreSync
import dev.uploadmanager.worker.ActiveTaskRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val stager: FileStager = FileStager(appContext, config.staging.stagingDirMaxBytes)

    /** Outlives any single worker; backs fire-and-forget Firestore writes (revision docs 01/04). */
    val ioScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Firestore is only present once the host app has initialised Firebase. Null is
    // handled gracefully everywhere (dedup → New, sync → no-op).
    private val firestore: FirebaseFirestore? = runCatching { FirebaseFirestore.getInstance() }.getOrNull()

    val dedupEngine: DeduplicationEngine =
        DeduplicationEngine(if (config.dedup.enabled) firestore else null, ioScope)
    val firestoreSync: FirestoreSync = FirestoreSync(firestore, config.syncPolicy, ioScope)

    /**
     * In-process concurrency cap (spec §10.2, M1 static form). Workers acquire a
     * permit before transferring; WorkManager-level parallelism stays untouched.
     */
    val uploadPermits: Semaphore = Semaphore(config.maxConcurrentUploads)
}
