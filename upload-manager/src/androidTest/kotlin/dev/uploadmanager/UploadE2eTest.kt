package dev.uploadmanager

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import dev.uploadmanager.api.UploadManagerConfig
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import dev.uploadmanager.api.UploadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * End-to-end tests against the Firebase Emulator Suite (spec §12.3).
 *
 * Prerequisite on the host machine:
 *   cd firebase && firebase emulators:start --project demo-upload-manager
 *
 * Run with: ./gradlew :upload-manager:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class UploadE2eTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setProjectId("demo-upload-manager")
                    .setApplicationId("1:123456789012:android:test")
                    .setApiKey("fake-api-key-for-emulator")
                    .setStorageBucket("demo-upload-manager.appspot.com")
                    .build(),
            )
            FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099)
            FirebaseStorage.getInstance().useEmulator(EMULATOR_HOST, 9199)
        }
        UploadManager.initialise(context, UploadManagerConfig(enableLogging = true))
    }

    private suspend fun signIn() {
        if (FirebaseAuth.getInstance().currentUser == null) {
            FirebaseAuth.getInstance().signInAnonymously().await()
        }
    }

    private fun tempFile(sizeBytes: Int): Uri {
        val file = File(context.cacheDir, "e2e-${System.nanoTime()}.bin")
        file.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024)
            var remaining = sizeBytes
            while (remaining > 0) {
                Random.nextBytes(chunk)
                val n = minOf(chunk.size, remaining)
                out.write(chunk, 0, n)
                remaining -= n
            }
        }
        return Uri.fromFile(file)
    }

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

    private suspend fun awaitState(
        taskId: String,
        target: UploadState,
        timeoutMs: Long,
    ) = withTimeout(timeoutMs) {
        while (true) {
            val status = UploadManager.getStatus(taskId) ?: error("task not found")
            if (status.state == target) return@withTimeout status
            check(status.state != UploadState.FAILED || target == UploadState.FAILED) {
                "unexpected FAILED: ${status.errorCode}"
            }
            delay(250)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private companion object {
        const val EMULATOR_HOST = "10.0.2.2"
    }
}
