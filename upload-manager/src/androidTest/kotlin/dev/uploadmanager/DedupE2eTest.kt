package dev.uploadmanager

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import kotlin.random.Random

/** Dedup CUJ (FR-08): a second upload of identical content is a DEDUP_HIT. */
@RunWith(AndroidJUnit4::class)
class DedupE2eTest : EmulatorTestBase() {

    @Test
    fun secondUploadOfIdenticalContentIsDeduped() = runBlocking {
        signIn()
        // Random so this checksum is unique to this run; the index starts empty for it.
        val bytes = ByteArray(512 * 1024).also { Random.nextBytes(it) }
        val checksum = sha256Hex(bytes)

        val first = UploadManager.enqueue(request(bytes, "dedup-a.bin"))
        awaitState(first, UploadState.COMPLETED, 90_000)
        // The index write is fire-and-forget; wait for it before the duplicate enqueue.
        awaitIndexEntry(checksum, 30_000)

        val second = UploadManager.enqueue(request(bytes, "dedup-b.bin"))
        val state = awaitState(second, UploadState.DEDUP_HIT, 60_000)
        assertEquals(UploadState.DEDUP_HIT, state.state)
        assertNotNull("dedup hit should carry the existing object's download URL", state.downloadUrl)
    }

    private fun request(bytes: ByteArray, name: String): UploadRequest {
        val file = tempBackingFile(0).apply { writeBytes(bytes) }
        return UploadRequest(Uri.fromFile(file), "application/octet-stream", name, UploadPriority.P0)
    }

    private suspend fun awaitIndexEntry(checksum: String, timeoutMs: Long) {
        withTimeout(timeoutMs) {
            while (true) {
                val doc = firestore().collection("users").document(uid)
                    .collection("checksumIndex").document(checksum).get().await()
                if (doc.exists()) return@withTimeout
                delay(200)
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
