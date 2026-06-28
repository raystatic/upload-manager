package dev.uploadmanager.sample

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadState
import dev.uploadmanager.api.UploadTaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One row in the upload list. [isLocal] means the task is running on *this* device
 * (so it has live progress and can be paused/resumed/cancelled); a non-local row was
 * uploaded by another device on the same account and is observed via Firestore.
 */
data class UploadRow(
    val taskId: String,
    val fileName: String,
    val sizeBytes: Long,
    val stateLabel: String,
    val progressPct: Int?,        // null when unknown (a remote in-progress upload)
    val isLocal: Boolean,
    val state: UploadState?,      // non-null for local rows; drives the action buttons
    val downloadUrl: String?,
)

/** A Firestore-projected upload from any device on this account. */
private data class RemoteUpload(
    val taskId: String,
    val fileName: String,
    val sizeBytes: Long,
    val state: String,
    val downloadUrl: String?,
)

class UploadsViewModel(application: Application) : AndroidViewModel(application) {

    val uid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    val rows: StateFlow<List<UploadRow>> =
        combine(UploadManager.observeAll(), remoteUploads()) { local, remote ->
            merge(local, remote)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Enqueue every picked file; the SDK handles staging, scheduling and retries. */
    fun enqueue(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                val (name, mime) = describe(uri)
                runCatching {
                    UploadManager.enqueue(
                        UploadRequest(
                            localUri = uri,
                            mimeType = mime,
                            fileName = name,
                            priority = UploadPriority.P1,
                        )
                    )
                }
            }
        }
    }

    fun pause(taskId: String) = UploadManager.pause(taskId)
    fun resume(taskId: String) = UploadManager.resume(taskId)
    fun cancel(taskId: String) = UploadManager.cancel(taskId)
    fun retry(taskId: String) = UploadManager.retry(taskId)

    // ---- internals ----

    /** Live Firestore view of this account's uploads, across all devices. */
    private fun remoteUploads() = callbackFlow<List<RemoteUpload>> {
        val user = uid
        if (user == null) {
            trySend(emptyList()); awaitClose { }
            return@callbackFlow
        }
        // Emit immediately so the local list shows even before Firestore responds
        // (combine waits for every source's first emission).
        trySend(emptyList())

        val fs = FirebaseFirestore.getInstance()
        val account = fs.collection("users").document(user)

        // In-progress lifecycle projections (SyncPolicy.FULL) and completed-file records.
        val live = HashMap<String, RemoteUpload>()
        val done = HashMap<String, RemoteUpload>()
        fun emitMerged() = trySend((live + done).values.toList())

        val taskReg = account.collection("uploadTasks").addSnapshotListener { snap, _ ->
            live.clear()
            snap?.documents?.forEach { d ->
                val id = d.getString("taskId") ?: d.id
                live[id] = RemoteUpload(
                    taskId = id,
                    fileName = d.getString("originalFilename") ?: "file",
                    sizeBytes = d.getLong("sizeBytes") ?: 0L,
                    state = d.getString("uploadState") ?: UploadState.UPLOADING.name,
                    downloadUrl = null,
                )
            }
            emitMerged()
        }
        val fileReg = account.collection("files").addSnapshotListener { snap, _ ->
            done.clear()
            snap?.documents?.forEach { d ->
                val id = d.getString("taskId") ?: d.id
                done[id] = RemoteUpload(
                    taskId = id,
                    fileName = d.getString("originalFilename") ?: "file",
                    sizeBytes = d.getLong("sizeBytes") ?: 0L,
                    state = UploadState.COMPLETED.name,
                    downloadUrl = d.getString("downloadUrl"),
                )
            }
            emitMerged()
        }
        awaitClose { taskReg.remove(); fileReg.remove() }
    }

    /** Local rows (authoritative: live progress + controllable) overlay remote-only rows. */
    private fun merge(local: List<UploadTaskState>, remote: List<RemoteUpload>): List<UploadRow> {
        val byId = LinkedHashMap<String, UploadRow>()
        remote.forEach { r ->
            byId[r.taskId] = UploadRow(
                taskId = r.taskId,
                fileName = r.fileName,
                sizeBytes = r.sizeBytes,
                stateLabel = r.state,
                progressPct = if (r.state == UploadState.COMPLETED.name) 100 else null,
                isLocal = false,
                state = null,
                downloadUrl = r.downloadUrl,
            )
        }
        local.forEach { t ->
            byId[t.taskId] = UploadRow(
                taskId = t.taskId,
                fileName = t.fileName,
                sizeBytes = t.fileSizeBytes,
                stateLabel = t.state.name,
                progressPct = t.progressPct,
                isLocal = true,
                state = t.state,
                downloadUrl = t.downloadUrl,
            )
        }
        // Active uploads first, then by name — so the things in motion stay on top.
        return byId.values.sortedWith(
            compareBy({ isTerminalLabel(it.stateLabel) }, { it.fileName.lowercase() })
        )
    }

    private fun isTerminalLabel(label: String): Boolean = label in TERMINAL_LABELS

    private suspend fun describe(uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        var name = "file"
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx)?.let { name = it }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        name to mime
    }

    private companion object {
        val TERMINAL_LABELS = setOf(
            UploadState.COMPLETED.name,
            UploadState.DEDUP_HIT.name,
            UploadState.FAILED.name,
            UploadState.CANCELLED.name,
        )
    }
}
