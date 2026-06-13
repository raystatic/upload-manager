package dev.uploadmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Core upload + durability CUJs (B1, C2). */
@RunWith(AndroidJUnit4::class)
class UploadE2eTest : EmulatorTestBase() {

    @Test
    fun smallUploadCompletesEndToEnd() = runBlocking {
        signIn()
        val taskId = UploadManager.enqueue(
            UploadRequest(
                localUri = tempFile(256 * 1024),
                mimeType = "application/octet-stream",
                fileName = "small.bin",
                priority = UploadPriority.P0,
            )
        )

        val finalState = awaitState(taskId, UploadState.COMPLETED, timeoutMs = 90_000)
        assertEquals(UploadState.COMPLETED, finalState.state)
        assertNotNull(finalState.downloadUrl)
        assertEquals(100, finalState.progressPct)
    }

    @Test
    fun sessionUriIsPersistedDuringLargeUpload(): Unit = runBlocking {
        signIn()
        val taskId = UploadManager.enqueue(
            UploadRequest(
                localUri = tempFile(24 * 1024 * 1024),
                mimeType = "application/octet-stream",
                fileName = "large.bin",
                priority = UploadPriority.P0,
            )
        )

        // The durability guarantee (spec §6.1 step 4): the session URI must land in
        // Room while the transfer is in flight (or, at worst, by completion).
        val core = UploadManager.coreOrNull(context)!!
        var sessionSeen = false
        withTimeout(150_000) {
            while (true) {
                val row = core.dao.getById(taskId) ?: error("task row disappeared")
                if (row.uploadSessionUri != null) sessionSeen = true
                if (row.uploadState == UploadState.COMPLETED) break
                check(row.uploadState != UploadState.FAILED) { "upload failed: ${row.errorCode}" }
                delay(250)
            }
        }
        check(sessionSeen) { "session URI was never persisted during the upload" }
    }
}
