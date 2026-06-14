package dev.uploadmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Lifecycle-control CUJs: pause→resume (D1) and cancel (D2). */
@RunWith(AndroidJUnit4::class)
class UploadControlE2eTest : EmulatorTestBase() {

    // Larger than the 64 MB auto-copy threshold so it uploads from the reference
    // (no staging copy) and the transfer stays in flight long enough to act on.
    private val bigFileBytes = 80 * 1024 * 1024

    @Test
    fun pauseThenResumeReactivates() = runBlocking {
        signIn()
        val taskId = UploadManager.enqueue(
            UploadRequest(tempFile(bigFileBytes), "application/octet-stream", "pause.bin", UploadPriority.P0)
        )

        awaitUploading(taskId)
        UploadManager.pause(taskId)
        assertEquals(UploadState.PAUSED, awaitState(taskId, UploadState.PAUSED, 30_000).state)

        // resume() re-dispatches the task out of PAUSED. We assert re-activation rather
        // than full completion: the Storage *emulator* is unreliable at finishing a
        // resumed session, so end-to-end resume-to-COMPLETED is an on-device CUJ.
        UploadManager.resume(taskId)
        val resumed = awaitAnyState(
            taskId, setOf(UploadState.UPLOADING, UploadState.COMPLETED), 60_000,
        )
        assertTrue(resumed.state == UploadState.UPLOADING || resumed.state == UploadState.COMPLETED)
    }

    @Test
    fun cancelStopsUpload() = runBlocking {
        signIn()
        val taskId = UploadManager.enqueue(
            UploadRequest(tempFile(bigFileBytes), "application/octet-stream", "cancel.bin", UploadPriority.P0)
        )

        awaitUploading(taskId)
        UploadManager.cancel(taskId)
        assertEquals(UploadState.CANCELLED, awaitState(taskId, UploadState.CANCELLED, 30_000).state)
    }
}
