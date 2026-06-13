package dev.uploadmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/** Staging CUJs: snapshot lifecycle (S1–S3) and source immutability (S4). */
@RunWith(AndroidJUnit4::class)
class StagingE2eTest : EmulatorTestBase() {

    @Test
    fun stagedFileIsRecordedHashedAndCleanedUp() = runBlocking {
        signIn()
        val bytes = ByteArray(2 * 1024 * 1024) { (it and 0xFF).toByte() }
        val file = tempBackingFile(0).apply { writeBytes(bytes) }
        val taskId = UploadManager.enqueue(
            UploadRequest(
                localUri = android.net.Uri.fromFile(file),
                mimeType = "application/octet-stream",
                fileName = "staged.bin",
                priority = UploadPriority.P0,
            )
        )

        val core = UploadManager.coreOrNull(context)!!
        val row = core.dao.getById(taskId)!!
        // Staged at enqueue: path recorded and SHA-256 computed in the copy pass.
        assertNotNull("expected a staged path", row.stagedPath)
        assertEquals(sha256Hex(bytes), row.checksum)

        awaitState(taskId, UploadState.COMPLETED, 90_000)
        // Cleanup runs just after the COMPLETED write; allow it a moment.
        awaitFileGone(row.stagedPath!!)
        assertFalse(java.io.File(row.stagedPath!!).exists())
    }

    @Test
    fun snapshotIsImmuneToPostEnqueueEdits() = runBlocking {
        signIn()
        val original = ByteArray(1 * 1024 * 1024) { 0x41 } // 'A'
        val file = tempBackingFile(0).apply { writeBytes(original) }

        val taskId = UploadManager.enqueue(
            UploadRequest(
                localUri = android.net.Uri.fromFile(file),
                mimeType = "application/octet-stream",
                fileName = "immutable.bin",
                priority = UploadPriority.P0,
            )
        )
        // Mutate the source AFTER enqueue: different size and content.
        file.writeBytes(ByteArray(2 * 1024 * 1024) { 0x42 }) // 'B'

        awaitState(taskId, UploadState.COMPLETED, 90_000)

        // The stored object must equal the original snapshot, not the edited file.
        assertArrayEquals(original, download(taskId))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
