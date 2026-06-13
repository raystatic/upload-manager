package dev.uploadmanager.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.uploadmanager.api.UploadState
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadTaskDao {

    @Upsert
    suspend fun upsert(task: UploadTaskEntity)

    @Query("SELECT * FROM upload_tasks WHERE id = :id")
    suspend fun getById(id: String): UploadTaskEntity?

    @Query("SELECT * FROM upload_tasks WHERE id = :id")
    fun observeById(id: String): Flow<UploadTaskEntity?>

    @Query("SELECT * FROM upload_tasks WHERE uid = :uid ORDER BY priority ASC, createdAt ASC")
    fun observeByUid(uid: String): Flow<List<UploadTaskEntity>>

    @Query(
        "SELECT * FROM upload_tasks WHERE uploadState IN ('PENDING', 'QUEUED', 'RETRYING')"
    )
    suspend fun getDispatchable(): List<UploadTaskEntity>

    @Query(
        "SELECT * FROM upload_tasks WHERE uploadState = 'PARKED' AND parkedUntil <= :now"
    )
    suspend fun getParkedDue(now: Long): List<UploadTaskEntity>

    @Query("SELECT COUNT(*) FROM upload_tasks WHERE uploadState = 'PARKED'")
    suspend fun countParked(): Int

    @Query(
        "UPDATE upload_tasks SET uploadState = :state, errorCode = :errorCode, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateState(id: String, state: UploadState, errorCode: String?, now: Long)

    /**
     * Compare-and-set state transition. Returns the number of rows updated (0 if the
     * task was no longer in [expected]) so callers never clobber a concurrent
     * transition — e.g. a worker's cancellation handler must not overwrite PAUSED.
     */
    @Query(
        "UPDATE upload_tasks SET uploadState = :state, updatedAt = :now WHERE id = :id AND uploadState = :expected"
    )
    suspend fun compareAndSetState(id: String, expected: UploadState, state: UploadState, now: Long): Int

    @Query("UPDATE upload_tasks SET uploadedBytes = :bytes, updatedAt = :now WHERE id = :id")
    suspend fun updateProgress(id: String, bytes: Long, now: Long)

    /**
     * The critical durability write (spec §4.3 note, revision 02 §4): session URI,
     * its creation timestamp, and the UPLOADING state land in one atomic statement.
     */
    @Query(
        "UPDATE upload_tasks SET uploadSessionUri = :uri, sessionCreatedAt = :now, " +
            "uploadState = 'UPLOADING', updatedAt = :now WHERE id = :id"
    )
    suspend fun saveSession(id: String, uri: String, now: Long)

    @Query(
        "UPDATE upload_tasks SET uploadSessionUri = NULL, sessionCreatedAt = NULL, " +
            "uploadedBytes = 0, updatedAt = :now WHERE id = :id"
    )
    suspend fun clearSession(id: String, now: Long)

    @Query(
        "UPDATE upload_tasks SET uploadState = 'PARKED', parkCount = :parkCount, " +
            "parkedUntil = :parkedUntil, errorCode = :errorCode, updatedAt = :now WHERE id = :id"
    )
    suspend fun park(id: String, parkCount: Int, parkedUntil: Long, errorCode: String?, now: Long)

    @Query(
        "UPDATE upload_tasks SET firstAttemptAt = :now WHERE id = :id AND firstAttemptAt = 0"
    )
    suspend fun markFirstAttempt(id: String, now: Long)

    @Query("UPDATE upload_tasks SET retryCount = :retryCount, updatedAt = :now WHERE id = :id")
    suspend fun updateRetryCount(id: String, retryCount: Int, now: Long)

    @Query(
        "UPDATE upload_tasks SET uploadState = 'COMPLETED', downloadUrl = :downloadUrl, " +
            "uploadedBytes = fileSizeBytes, errorCode = NULL, updatedAt = :now WHERE id = :id"
    )
    suspend fun markCompleted(id: String, downloadUrl: String, now: Long)

    /** Reset for UploadManager.retry(): back to PENDING with counters cleared. */
    @Query(
        "UPDATE upload_tasks SET uploadState = 'PENDING', retryCount = 0, parkCount = 0, " +
            "parkedUntil = NULL, firstAttemptAt = 0, errorCode = NULL, updatedAt = :now WHERE id = :id"
    )
    suspend fun resetCounters(id: String, now: Long)

    @Query("DELETE FROM upload_tasks WHERE uploadState = 'COMPLETED'")
    suspend fun clearCompleted(): Int

    /**
     * Source content changed under a REFERENCE task (revision doc 03 §4): record the
     * new fingerprint and clear any session so the upload restarts from byte 0 with
     * the current bytes rather than splicing onto a stale offset.
     */
    @Query(
        "UPDATE upload_tasks SET sourceSizeBytes = :size, sourceLastModified = :mod, fileSizeBytes = :size, " +
            "uploadSessionUri = NULL, sessionCreatedAt = NULL, uploadedBytes = 0, " +
            "checksum = NULL, updatedAt = :now WHERE id = :id"
    )
    suspend fun onSourceChanged(id: String, size: Long, mod: Long, now: Long)

    /** Task ids that may still need their staged file (non-terminal states). */
    @Query(
        "SELECT id FROM upload_tasks WHERE uploadState NOT IN " +
            "('COMPLETED', 'FAILED', 'CANCELLED', 'DEDUP_HIT')"
    )
    suspend fun getActiveIds(): List<String>
}
