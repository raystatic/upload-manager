package dev.uploadmanager.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.OnProgressListener
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import dev.uploadmanager.R
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadEvent
import dev.uploadmanager.api.UploadState
import dev.uploadmanager.core.UploadManagerCore
import dev.uploadmanager.db.UploadTaskEntity
import dev.uploadmanager.dedup.DeduplicationEngine
import dev.uploadmanager.internal.ConcurrencyPolicy
import dev.uploadmanager.internal.SourceChecker
import dev.uploadmanager.retry.FailureAction
import dev.uploadmanager.retry.RetryClassifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes a single upload (spec §6, flow per revision doc 04 §5 minus dedup):
 * source check → session validation → resumable transfer with atomic session-URI
 * persistence and throttled progress writes → classification of any failure
 * (revision doc 02). WorkManager owns all retry timing.
 */
internal class FirebaseUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val core = UploadManager.coreOrNull(applicationContext) ?: return Result.failure()
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val task = core.dao.getById(taskId) ?: return Result.success()

        // Only run for active states; PAUSED/terminal/PARKED tasks are not ours to touch.
        if (task.uploadState !in RUNNABLE_STATES) return Result.success()

        val now = System.currentTimeMillis()
        val policy = core.config.retryPolicy

        if (policy.isExpired(task.firstAttemptAt, now)) {
            terminalFail(core, taskId, ERROR_TTL_EXCEEDED, now)
            return Result.failure()
        }
        core.dao.markFirstAttempt(taskId, now)

        // Revision doc 03 §4: validate the source before transferring any byte.
        val current = when (val outcome = validateSource(core, task)) {
            SourceOutcome.Gone -> {
                terminalFail(core, taskId, ERROR_SOURCE_GONE, System.currentTimeMillis())
                return Result.failure()
            }
            is SourceOutcome.Proceed -> outcome.task
        }

        // The Storage path is scoped to the enqueueing uid; wait (parked) if that
        // user is not currently signed in rather than failing or burning retries.
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid != current.uid) {
            return park(core, current, ERROR_AUTH_UNAVAILABLE)
        }

        // Dedup (revision doc 01): if this content is already stored for the user,
        // skip the transfer entirely. Best-effort — a failed check just proceeds.
        val checksum = current.checksum
        if (core.config.dedup.enabled && checksum != null) {
            val dedup = core.dedupEngine.check(current.uid, checksum)
            if (dedup is DeduplicationEngine.Result.Duplicate) {
                // Verify the referenced object actually still exists. A stale index
                // entry (object deleted after upload) must NOT be reported as a hit —
                // otherwise we'd mark success with nothing stored. Fall through to a
                // real upload in that case.
                val existingUrl = runCatching {
                    FirebaseStorage.getInstance().reference.child(dedup.storagePath)
                        .downloadUrl.await().toString()
                }.getOrNull()
                if (existingUrl != null) {
                    return dedupHit(core, current, dedup.storagePath, existingUrl)
                }
            }
        }

        // Adaptive throttling (spec §10): hold large uploads off cellular / low battery / heat.
        core.conditionsMonitor?.let { monitor ->
            val allowed = ConcurrencyPolicy.allowsLargeUpload(
                monitor.conditions(), current.fileSizeBytes, core.config.largeUploadThresholdBytes,
            )
            if (!allowed) return park(core, current, ERROR_DEVICE_BUSY)
        }

        if (current.fileSizeBytes >= core.config.foregroundThresholdBytes) {
            // Best-effort: background-start restrictions (Android 12+) can reject this.
            runCatching { setForeground(getForegroundInfo()) }
        }

        // Resizable concurrency gate (spec §10.2): suspends while over the current limit.
        return core.governor.withSlot { transfer(core, current) }
    }

    private suspend fun dedupHit(
        core: UploadManagerCore,
        task: UploadTaskEntity,
        existingPath: String,
        downloadUrl: String,
    ): Result {
        val now = System.currentTimeMillis()
        core.dao.markDedupHit(task.id, downloadUrl, existingPath, now)
        task.checksum?.let { core.dedupEngine.recordHit(task.uid, it) }
        core.stager.cleanup(task.id)
        val mirrored = task.copy(
            storagePath = existingPath, downloadUrl = downloadUrl, uploadState = UploadState.DEDUP_HIT,
        )
        core.firestoreSync.onCompleted(mirrored, downloadUrl)
        core.firestoreSync.onTaskProjection(mirrored)
        core.config.metrics?.onDedupHit(task.id)
        core.events.emit(UploadEvent.DedupHit(task.id, downloadUrl ?: ""))
        return Result.success()
    }

    private fun recordSuccess(core: UploadManagerCore, task: UploadTaskEntity, downloadUrl: String) {
        val checksum = task.checksum
        if (core.config.dedup.enabled && checksum != null) {
            core.dedupEngine.recordUpload(
                task.uid, checksum, task.storagePath, task.fileSizeBytes, task.mimeType,
            )
        }
        val done = task.copy(downloadUrl = downloadUrl, uploadState = UploadState.COMPLETED)
        core.firestoreSync.onCompleted(done, downloadUrl)
        core.firestoreSync.onTaskProjection(done)
    }

    private sealed class SourceOutcome {
        object Gone : SourceOutcome()
        data class Proceed(val task: UploadTaskEntity) : SourceOutcome()
    }

    /**
     * A staged task uploads an immutable copy (only existence matters). A REFERENCE
     * task is fingerprinted: a deleted source is terminal (never retried), and a
     * changed source restarts from byte 0 with the current bytes (revision doc 03 §4).
     */
    private suspend fun validateSource(core: UploadManagerCore, task: UploadTaskEntity): SourceOutcome {
        val staged = task.stagedPath
        if (staged != null) {
            return if (File(staged).exists()) SourceOutcome.Proceed(task) else SourceOutcome.Gone
        }
        val uri = Uri.parse(task.localUri)
        return when (SourceChecker.check(applicationContext, uri, task.sourceSizeBytes, task.sourceLastModified)) {
            SourceChecker.Result.Gone -> SourceOutcome.Gone
            SourceChecker.Result.Ok -> SourceOutcome.Proceed(task)
            SourceChecker.Result.Changed -> {
                val fp = SourceChecker.fingerprint(applicationContext, uri) ?: return SourceOutcome.Gone
                if (core.config.enableLogging) {
                    Log.i("UploadManager", "sdk.upload.source_changed ${task.id} -> restart from 0")
                }
                core.dao.onSourceChanged(task.id, fp.sizeBytes, fp.lastModified, System.currentTimeMillis())
                SourceOutcome.Proceed(core.dao.getById(task.id) ?: task)
            }
        }
    }

    private suspend fun terminalFail(core: UploadManagerCore, taskId: String, code: String, now: Long) {
        core.dao.updateState(taskId, UploadState.FAILED, code, now)
        core.stager.cleanup(taskId)
        core.config.metrics?.onFailed(taskId, code)
        core.events.emit(UploadEvent.Failed(taskId, code))
    }

    private suspend fun transfer(core: UploadManagerCore, task: UploadTaskEntity): Result {
        val dao = core.dao
        val taskId = task.id
        val storageRef = FirebaseStorage.getInstance().reference.child(task.storagePath)
        // Upload the staged snapshot when present; otherwise the source URI directly.
        val uploadUri = task.stagedPath?.let { Uri.fromFile(File(it)) } ?: Uri.parse(task.localUri)
        val now = System.currentTimeMillis()

        val existing = core.registry.resumable(taskId)
        val sessionValid = task.uploadSessionUri != null && task.sessionCreatedAt != null &&
            now - task.sessionCreatedAt < SESSION_MAX_AGE_MS

        // Revision doc 02 §4: stale sessions are cleared atomically and the upload
        // restarts from byte 0 — an expected transition, not an error.
        if (existing == null && !sessionValid && task.uploadSessionUri != null) {
            dao.clearSession(taskId, now)
        }

        val uploadTask: UploadTask = when {
            // In-process paused/stopped task: resume it instead of opening a new session (§6.2).
            existing != null -> existing.also { it.resume() }
            sessionValid -> storageRef.putFile(
                uploadUri, buildMetadata(task), Uri.parse(task.uploadSessionUri)
            )
            else -> storageRef.putFile(uploadUri, buildMetadata(task))
        }
        core.registry.register(taskId, uploadTask)
        dao.updateState(taskId, UploadState.UPLOADING, null, now)
        core.firestoreSync.onTaskProjection(task.copy(uploadState = UploadState.UPLOADING))
        core.events.emit(UploadEvent.Started(taskId, resuming = existing != null || sessionValid))

        var sessionPersisted = task.uploadSessionUri != null
        var lastPersistedBytes = task.uploadedBytes

        try {
            coroutineScope {
                // Firebase invokes listeners on the main thread; bridge through a
                // conflated channel so no listener ever blocks (review finding on §6.1).
                val snapshots = Channel<UploadTask.TaskSnapshot>(Channel.CONFLATED)
                val listener = OnProgressListener<UploadTask.TaskSnapshot> { snapshots.trySend(it) }
                uploadTask.addOnProgressListener(listener)
                val consumer = launch {
                    for (snap in snapshots) {
                        val sessionUri = snap.uploadSessionUri
                        if (!sessionPersisted && sessionUri != null) {
                            sessionPersisted = true
                            // The critical durability write: URI + timestamp + state, atomically.
                            dao.saveSession(taskId, sessionUri.toString(), System.currentTimeMillis())
                        }
                        if (snap.bytesTransferred - lastPersistedBytes >= core.config.progressIntervalBytes) {
                            lastPersistedBytes = snap.bytesTransferred
                            dao.updateProgress(taskId, snap.bytesTransferred, System.currentTimeMillis())
                        }
                        core.events.emit(
                            UploadEvent.Progress(taskId, snap.bytesTransferred, snap.totalByteCount)
                        )
                    }
                }
                try {
                    val snapshot = uploadTask.await()
                    val downloadUrl = snapshot.storage.downloadUrl.await().toString()
                    dao.markCompleted(taskId, downloadUrl, System.currentTimeMillis())
                    core.registry.remove(taskId)
                    core.stager.cleanup(taskId)
                    recordSuccess(core, task, downloadUrl)
                    core.config.metrics?.onCompleted(
                        taskId, System.currentTimeMillis() - task.createdAt, task.fileSizeBytes,
                    )
                    core.events.emit(UploadEvent.Completed(taskId, downloadUrl))
                } finally {
                    uploadTask.removeOnProgressListener(listener)
                    snapshots.close()
                    consumer.cancel()
                }
            }
            return Result.success()
        } catch (e: CancellationException) {
            // Worker stopped: constraints lost, WorkManager quota, or pause()/cancel()
            // from the API (which cancel the unique work). Hold the transfer in-process
            // and keep the session URI so either path can resume cheaply.
            uploadTask.pause()
            // Don't clobber a state the API just wrote (PAUSED/CANCELLED).
            dao.compareAndSetState(taskId, UploadState.UPLOADING, UploadState.QUEUED, System.currentTimeMillis())
            throw e
        } catch (e: Exception) {
            core.registry.remove(taskId)
            return handleFailure(core, task, e)
        }
    }

    private suspend fun handleFailure(
        core: UploadManagerCore,
        task: UploadTaskEntity,
        e: Exception,
    ): Result {
        val policy = core.config.retryPolicy
        val attemptsInTier = runAttemptCount + 1
        val errorCode = RetryClassifier.errorCode(e)
        val now = System.currentTimeMillis()

        return when (RetryClassifier.classify(e, attemptsInTier, policy)) {
            FailureAction.RETRY_FAST -> {
                core.dao.updateState(task.id, UploadState.RETRYING, errorCode, now)
                core.dao.updateRetryCount(task.id, attemptsInTier, now)
                core.config.metrics?.onRetry(task.id, attemptsInTier, errorCode)
                core.events.emit(UploadEvent.Retrying(task.id, attemptsInTier, errorCode))
                Result.retry()
            }
            FailureAction.REFRESH_AUTH -> {
                runCatching { FirebaseAuth.getInstance().currentUser?.getIdToken(true)?.await() }
                core.dao.updateState(task.id, UploadState.RETRYING, errorCode, now)
                core.config.metrics?.onRetry(task.id, attemptsInTier, errorCode)
                core.events.emit(UploadEvent.Retrying(task.id, attemptsInTier, errorCode))
                Result.retry()
            }
            FailureAction.PARK -> park(core, task, errorCode)
            FailureAction.TERMINAL -> {
                core.dao.updateState(task.id, UploadState.FAILED, errorCode, now)
                core.stager.cleanup(task.id)
                core.config.metrics?.onFailed(task.id, errorCode)
                core.events.emit(UploadEvent.Failed(task.id, errorCode))
                Result.failure()
            }
        }
    }

    private suspend fun park(core: UploadManagerCore, task: UploadTaskEntity, errorCode: String?): Result {
        val policy = core.config.retryPolicy
        val now = System.currentTimeMillis()
        val delay = policy.parkDelayMs(task.parkCount)
        core.dao.park(task.id, task.parkCount + 1, now + delay, errorCode, now)
        core.scheduler.scheduleParkKick(delay)
        core.config.metrics?.onParked(task.id)
        core.events.emit(UploadEvent.Parked(task.id, now + delay))
        // This WorkRequest ends here; the sweep issues a fresh one (fast tier resets).
        return Result.failure()
    }

    private fun buildMetadata(task: UploadTaskEntity): StorageMetadata {
        val builder = StorageMetadata.Builder()
            .setContentType(task.mimeType)
            .setCustomMetadata("originalFilename", task.fileName)
            .setCustomMetadata("uploadedBy", task.uid)
            .setCustomMetadata("sdkVersion", SDK_VERSION)
        task.checksum?.let { builder.setCustomMetadata("checksum", it) }
        task.appMetadata.lineSequence()
            .filter { it.contains('=') }
            .forEach { line ->
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                builder.setCustomMetadata("app_$key", value)
            }
        return builder.build()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = ensureChannel()
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(applicationContext.getString(R.string.upload_manager_notification_title))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(): String {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.upload_manager_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        return CHANNEL_ID
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val SDK_VERSION = "0.1.0"

        const val ERROR_SOURCE_GONE = "SOURCE_GONE"
        const val ERROR_TTL_EXCEEDED = "TTL_EXCEEDED"
        const val ERROR_AUTH_UNAVAILABLE = "AUTH_UNAVAILABLE"
        const val ERROR_DEVICE_BUSY = "DEVICE_BUSY"

        /** Stay safely under GCS's ~7-day resumable-session lifetime (revision doc 02 §4). */
        val SESSION_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(6)

        private val RUNNABLE_STATES = setOf(
            UploadState.PENDING, UploadState.QUEUED, UploadState.RETRYING, UploadState.UPLOADING
        )

        private const val CHANNEL_ID = "dev.uploadmanager.uploads"
        private const val NOTIFICATION_ID = 0x55504C // "UPL"
    }
}
