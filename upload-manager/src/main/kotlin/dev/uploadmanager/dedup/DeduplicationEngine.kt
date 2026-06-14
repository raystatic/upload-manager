package dev.uploadmanager.dedup

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Per-uid content-hash dedup (revision doc 01). The index lives at
 * `users/{uid}/checksumIndex/{checksum}`. All access is best-effort: a failed
 * read returns [Result.New] (the upload proceeds) and writes are fire-and-forget,
 * so Firestore being unreachable or unconfigured never blocks an upload.
 *
 * [firestore] is null when dedup is disabled, which short-circuits everything.
 */
internal class DeduplicationEngine(
    private val firestore: FirebaseFirestore?,
    private val scope: CoroutineScope,
) {
    sealed class Result {
        object New : Result()
        data class Duplicate(val storagePath: String) : Result()
    }

    suspend fun check(uid: String, checksum: String): Result {
        val fs = firestore ?: return Result.New
        return runCatching {
            val doc = indexDoc(fs, uid, checksum).get().await()
            val path = doc.getString("storagePath")
            if (doc.exists() && path != null) Result.Duplicate(path) else Result.New
        }.getOrDefault(Result.New)
    }

    /** Record (or bump the refCount of) the index entry for a freshly uploaded object. */
    fun recordUpload(uid: String, checksum: String, storagePath: String, sizeBytes: Long, mimeType: String) {
        val fs = firestore ?: return
        scope.launch {
            runCatching {
                indexDoc(fs, uid, checksum).set(
                    mapOf(
                        "checksum" to checksum,
                        "storagePath" to storagePath,
                        "sizeBytes" to sizeBytes,
                        "mimeType" to mimeType,
                        "refCount" to FieldValue.increment(1),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
            }
        }
    }

    /** Another task referenced an already-stored object: bump the refCount. */
    fun recordHit(uid: String, checksum: String) {
        val fs = firestore ?: return
        scope.launch {
            runCatching {
                indexDoc(fs, uid, checksum).set(
                    mapOf(
                        "refCount" to FieldValue.increment(1),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                ).await()
            }
        }
    }

    private fun indexDoc(fs: FirebaseFirestore, uid: String, checksum: String): DocumentReference =
        fs.collection("users").document(uid).collection("checksumIndex").document(checksum)
}
