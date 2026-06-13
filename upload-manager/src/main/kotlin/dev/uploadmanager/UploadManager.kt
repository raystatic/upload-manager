package dev.uploadmanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import dev.uploadmanager.api.UploadEvent
import dev.uploadmanager.api.UploadManagerConfig
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadState
import dev.uploadmanager.api.UploadTaskState
import dev.uploadmanager.core.UploadManagerCore
import dev.uploadmanager.db.UploadTaskEntity
import dev.uploadmanager.internal.SourceChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Public entry point (spec §11). Initialise once in Application.onCreate():
 *
 * ```
 * UploadManager.initialise(context, UploadManagerConfig(...))
 * ```
 *
 * The host app owns Firebase initialisation and authentication; a signed-in
 * FirebaseAuth user is required before [enqueue].
 */
object UploadManager {

    @Volatile
    private var core: UploadManagerCore? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialise(context: Context, config: UploadManagerConfig = UploadManagerConfig()) {
        synchronized(this) {
            if (core != null) return
            core = UploadManagerCore(context, config)
        }
        val c = requireCore()
        c.scheduler.ensureSweepScheduled()
        // Reconcile after process death/reboot: every dispatchable task gets its
        // WorkRequest back (KEEP makes this idempotent).
        scope.launch {
            c.dao.getDispatchable().forEach { c.scheduler.dispatch(it) }
            // Delete staged files orphaned by a crash between terminal-state and cleanup.
            c.stager.reconcile(c.dao.getActiveIds().toSet())
        }
    }

    /**
     * Persists the task and schedules it (spec §3.2 steps 1–4). Returns the task id.
     * @throws IllegalStateException if not initialised or no Firebase user is signed in.
     * @throws IllegalArgumentException if the source URI is not readable.
     */
    suspend fun enqueue(request: UploadRequest): String {
        val c = requireCore()
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: throw IllegalStateException("UploadManager.enqueue requires a signed-in FirebaseAuth user")

        val persistable = takePersistablePermission(c.appContext, request.localUri)
        val fingerprint = withContext(Dispatchers.IO) {
            SourceChecker.fingerprint(c.appContext, request.localUri)
        } ?: throw IllegalArgumentException("Source URI is not readable: ${request.localUri}")

        val id = UUID.randomUUID().toString()

        // Staging (revision doc 03): snapshot the source into app-private storage so
        // the upload reads immutable bytes. Falls back to REFERENCE if over budget.
        val staged = if (c.config.staging.shouldStage(fingerprint.sizeBytes)) {
            withContext(Dispatchers.IO) { c.stager.stage(id, request.localUri) }
        } else {
            null
        }

        val now = System.currentTimeMillis()
        val task = UploadTaskEntity(
            id = id,
            uid = uid,
            localUri = request.localUri.toString(),
            // M1: taskId-addressed; switches to content-addressed paths with dedup (M2).
            storagePath = "users/$uid/files/$id",
            fileName = request.fileName,
            mimeType = request.mimeType,
            fileSizeBytes = staged?.sizeBytes ?: fingerprint.sizeBytes,
            uploadState = UploadState.PENDING,
            priority = request.priority.value,
            appMetadata = request.metadata.entries.joinToString("\n") { "${it.key}=${it.value}" },
            checksum = staged?.checksum,
            stagedPath = staged?.path,
            sourceSizeBytes = fingerprint.sizeBytes,
            sourceLastModified = fingerprint.lastModified,
            persistablePermission = persistable,
            createdAt = now,
            updatedAt = now,
        )
        c.dao.upsert(task)
        c.events.emit(UploadEvent.Enqueued(id, fingerprint.sizeBytes))
        c.dao.updateState(id, UploadState.QUEUED, null, System.currentTimeMillis())
        c.scheduler.dispatch(task)
        return id
    }

    /** Pause: holds the in-process transfer and releases the WorkManager slot (§6.2). */
    fun pause(taskId: String) {
        val c = requireCore()
        scope.launch {
            c.registry.get(taskId)?.pause()
            val changed = c.dao.compareAndSetState(
                taskId, UploadState.UPLOADING, UploadState.PAUSED, System.currentTimeMillis()
            ) + c.dao.compareAndSetState(
                taskId, UploadState.QUEUED, UploadState.PAUSED, System.currentTimeMillis()
            ) + c.dao.compareAndSetState(
                taskId, UploadState.RETRYING, UploadState.PAUSED, System.currentTimeMillis()
            )
            if (changed > 0) {
                c.scheduler.cancel(taskId)
                c.events.emit(UploadEvent.Paused(taskId))
            }
        }
    }

    fun resume(taskId: String) {
        val c = requireCore()
        scope.launch {
            val changed = c.dao.compareAndSetState(
                taskId, UploadState.PAUSED, UploadState.QUEUED, System.currentTimeMillis()
            )
            if (changed > 0) {
                c.dao.getById(taskId)?.let { c.scheduler.dispatch(it) }
                c.events.emit(UploadEvent.Resumed(taskId))
            }
        }
    }

    fun cancel(taskId: String) {
        val c = requireCore()
        scope.launch {
            c.registry.remove(taskId)?.cancel()
            c.scheduler.cancel(taskId)
            val task = c.dao.getById(taskId)
            if (task != null && !task.uploadState.isTerminal) {
                c.dao.updateState(taskId, UploadState.CANCELLED, null, System.currentTimeMillis())
                c.dao.clearSession(taskId, System.currentTimeMillis())
                c.stager.cleanup(taskId)
                c.events.emit(UploadEvent.Cancelled(taskId))
            }
        }
    }

    /** Re-enqueue a FAILED/CANCELLED task with counters reset (§11.4). */
    fun retry(taskId: String) {
        val c = requireCore()
        scope.launch {
            c.dao.resetCounters(taskId, System.currentTimeMillis())
            c.dao.clearSession(taskId, System.currentTimeMillis())
            c.dao.getById(taskId)?.let { c.scheduler.dispatch(it) }
        }
    }

    suspend fun getStatus(taskId: String): UploadTaskState? =
        requireCore().dao.getById(taskId)?.toState()

    fun observe(taskId: String): Flow<UploadEvent> = requireCore().events.forTask(taskId)

    fun observeEvents(): Flow<UploadEvent> = requireCore().events.all

    /** All tasks for the currently signed-in user, ordered by priority then age. */
    fun observeAll(): Flow<List<UploadTaskState>> {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyFlow()
        return requireCore().dao.observeByUid(uid).map { list -> list.map { it.toState() } }
    }

    suspend fun clearCompleted(): Int = requireCore().dao.clearCompleted()

    // ---- internal ----

    internal fun requireCore(): UploadManagerCore =
        core ?: throw IllegalStateException("UploadManager.initialise() has not been called")

    /** Workers may run before the host app calls initialise (e.g. after reboot). */
    internal fun coreOrNull(context: Context): UploadManagerCore? {
        core?.let { return it }
        synchronized(this) {
            if (core == null) {
                // Initialise with defaults; the host's later initialise() call is a no-op
                // for wiring but its config applies to the next process.
                core = UploadManagerCore(context, UploadManagerConfig())
            }
        }
        return core
    }

    private fun takePersistablePermission(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        true
    }.getOrDefault(false)

    private fun UploadTaskEntity.toState() = UploadTaskState(
        taskId = id,
        fileName = fileName,
        mimeType = mimeType,
        fileSizeBytes = fileSizeBytes,
        uploadedBytes = uploadedBytes,
        state = uploadState,
        priority = UploadPriority.entries.first { it.value == priority },
        downloadUrl = downloadUrl,
        errorCode = errorCode,
        retryCount = retryCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
