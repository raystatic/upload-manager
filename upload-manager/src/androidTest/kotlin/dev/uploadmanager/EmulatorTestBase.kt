package dev.uploadmanager

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import dev.uploadmanager.api.UploadManagerConfig
import dev.uploadmanager.api.UploadState
import dev.uploadmanager.api.UploadTaskState
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Before
import java.io.File
import kotlin.random.Random

/**
 * Shared wiring for instrumented tests against the Firebase Emulator Suite (spec §12.3).
 *
 * Prerequisite on the host:
 *   cd firebase && firebase emulators:start --project demo-upload-manager
 * Run with:
 *   ./gradlew :upload-manager:connectedDebugAndroidTest
 */
abstract class EmulatorTestBase {

    protected val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun baseSetUp() {
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

    protected suspend fun signIn() {
        if (FirebaseAuth.getInstance().currentUser == null) {
            FirebaseAuth.getInstance().signInAnonymously().await()
        }
    }

    protected val uid: String
        get() = FirebaseAuth.getInstance().currentUser!!.uid

    /** A cache file of [sizeBytes]; random content unless [fill] is given. Returns its file:// uri. */
    protected fun tempFile(sizeBytes: Int, fill: Byte? = null): Uri =
        Uri.fromFile(tempBackingFile(sizeBytes, fill))

    protected fun tempBackingFile(sizeBytes: Int, fill: Byte? = null): File {
        val file = File(context.cacheDir, "e2e-${System.nanoTime()}.bin")
        file.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024)
            var remaining = sizeBytes
            while (remaining > 0) {
                if (fill != null) chunk.fill(fill) else Random.nextBytes(chunk)
                val n = minOf(chunk.size, remaining)
                out.write(chunk, 0, n)
                remaining -= n
            }
        }
        return file
    }

    protected suspend fun awaitState(taskId: String, target: UploadState, timeoutMs: Long): UploadTaskState =
        withTimeout(timeoutMs) {
            while (true) {
                val status = UploadManager.getStatus(taskId) ?: error("task $taskId not found")
                if (status.state == target) return@withTimeout status
                check(status.state != UploadState.FAILED || target == UploadState.FAILED) {
                    "unexpected FAILED for $taskId: ${status.errorCode}"
                }
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }

    /** Wait until the upload is actively transferring (so a control op lands mid-flight). */
    protected suspend fun awaitUploading(taskId: String, timeoutMs: Long = 60_000) {
        withTimeout(timeoutMs) {
            while (true) {
                val s = UploadManager.getStatus(taskId) ?: error("task $taskId not found")
                if (s.state == UploadState.UPLOADING && s.uploadedBytes > 0) return@withTimeout
                check(s.state != UploadState.FAILED) { "unexpected FAILED: ${s.errorCode}" }
                check(s.state != UploadState.COMPLETED) { "completed before the control op could run" }
                delay(50)
            }
        }
    }

    protected suspend fun awaitFileGone(path: String, timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            while (File(path).exists()) delay(100)
        }
    }

    protected suspend fun download(taskId: String): ByteArray =
        FirebaseStorage.getInstance().reference
            .child("users/$uid/files/$taskId")
            .getBytes(MAX_DOWNLOAD_BYTES)
            .await()

    protected companion object {
        const val EMULATOR_HOST = "10.0.2.2"
        const val MAX_DOWNLOAD_BYTES = 16L * 1024 * 1024
    }
}
