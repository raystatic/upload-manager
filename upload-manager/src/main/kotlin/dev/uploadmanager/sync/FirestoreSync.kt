package dev.uploadmanager.sync

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dev.uploadmanager.api.SyncPolicy
import dev.uploadmanager.db.UploadTaskEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Write-behind, fire-and-forget Firestore projection (revision doc 04). Never
 * blocks an upload; `uploadedBytes` is never mirrored. The completed-file record
 * is written at completion/dedup-hit (TERMINAL_ONLY and above); the lifecycle
 * projection only under FULL.
 */
class FirestoreSync(
    private val firestore: FirebaseFirestore?,
    private val policy: SyncPolicy,
    private val scope: CoroutineScope,
) {
    /** A file record implies the binary exists in Storage (revised FR-09). */
    fun onCompleted(task: UploadTaskEntity, downloadUrl: String?) {
        if (policy == SyncPolicy.NONE) return
        val fs = firestore ?: return
        scope.launch {
            runCatching {
                fs.collection("users").document(task.uid)
                    .collection("files").document(task.id)
                    .set(fileRecord(task, downloadUrl)).await()
            }
        }
    }

    fun onTaskProjection(task: UploadTaskEntity) {
        if (policy != SyncPolicy.FULL) return
        val fs = firestore ?: return
        scope.launch {
            runCatching {
                fs.collection("users").document(task.uid)
                    .collection("uploadTasks").document(task.id)
                    .set(taskProjection(task), SetOptions.merge()).await()
            }
        }
    }

    private fun fileRecord(task: UploadTaskEntity, downloadUrl: String?): Map<String, Any> = buildMap {
        put("taskId", task.id)
        put("storagePath", task.storagePath)
        put("sizeBytes", task.fileSizeBytes)
        put("mimeType", task.mimeType)
        put("originalFilename", task.fileName)
        task.checksum?.let { put("checksum", it) }
        downloadUrl?.let { put("downloadUrl", it) }
        put("uploadedAt", FieldValue.serverTimestamp())
    }

    private fun taskProjection(task: UploadTaskEntity): Map<String, Any> = buildMap {
        put("taskId", task.id)
        put("uploadState", task.uploadState.name)
        put("priority", task.priority)
        put("storagePath", task.storagePath)
        task.checksum?.let { put("checksum", it) }
        put("retryCount", task.retryCount)
        put("updatedAt", FieldValue.serverTimestamp())
        // uploadedBytes deliberately NOT mirrored (revision doc 04).
    }
}
