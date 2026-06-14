package dev.uploadmanager.sample

import android.content.Context
import android.net.Uri
import dev.uploadmanager.UploadManager
import dev.uploadmanager.api.UploadPriority
import dev.uploadmanager.api.UploadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * Generates self-contained test files and enqueues them, so every CUJ can be run
 * with one tap — no need to find files on the device. Returns a short status line.
 */
class CujRunner(private val context: Context) {

    /** Small random file: staged by default, completes quickly. CUJ B1/S1. */
    suspend fun uploadSmall(): String =
        enqueue(randomFile("small", 3 * 1024 * 1024), "small.bin")

    /** ~70 MB random file: above the 64 MB auto-copy threshold, so it uploads by reference. CUJ B2. */
    suspend fun uploadLarge(): String =
        enqueue(randomFile("large", 70 * 1024 * 1024), "large.bin")

    /**
     * Fixed-content file. Tap once and wait for COMPLETED, then tap again: the
     * second upload of identical content is a DEDUP_HIT. CUJ dedup.
     */
    suspend fun uploadDuplicate(): String {
        val file = fixedFile("dup", sizeBytes = 1 * 1024 * 1024, fill = 0x7)
        return enqueue(Uri.fromFile(file), "duplicate.bin") +
            " — tap again after it completes to see DEDUP_HIT"
    }

    /**
     * Enqueues a file then deletes the backing source. With staging on (Default)
     * the snapshot still uploads (immutability). With the "Reference (no staging)"
     * preset the worker finds the source gone → FAILED/SOURCE_GONE. CUJ S4/S8.
     */
    suspend fun uploadThenDeleteSource(): String {
        val file = randomBackingFile("ephemeral", 2 * 1024 * 1024)
        val taskId = UploadManager.enqueue(request(Uri.fromFile(file), "ephemeral.bin"))
        withContext(Dispatchers.IO) { file.delete() }
        return "enqueued $taskId then deleted the source"
    }

    suspend fun uploadPicked(uri: Uri, name: String, mime: String): String {
        val id = UploadManager.enqueue(
            UploadRequest(localUri = uri, mimeType = mime, fileName = name, priority = UploadPriority.P0)
        )
        return "enqueued $name ($id)"
    }

    private suspend fun enqueue(uri: Uri, name: String): String {
        val id = UploadManager.enqueue(request(uri, name))
        return "enqueued $name ($id)"
    }

    private fun request(uri: Uri, name: String) = UploadRequest(
        localUri = uri,
        mimeType = "application/octet-stream",
        fileName = name,
        priority = UploadPriority.P0,
    )

    private suspend fun randomFile(tag: String, size: Int): Uri =
        Uri.fromFile(randomBackingFile(tag, size))

    private suspend fun randomBackingFile(tag: String, size: Int): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "cuj-$tag-${System.nanoTime()}.bin")
        file.outputStream().use { out ->
            val chunk = ByteArray(64 * 1024)
            var remaining = size
            while (remaining > 0) {
                Random.nextBytes(chunk)
                val n = minOf(chunk.size, remaining)
                out.write(chunk, 0, n)
                remaining -= n
            }
        }
        file
    }

    private suspend fun fixedFile(tag: String, sizeBytes: Int, fill: Byte): File = withContext(Dispatchers.IO) {
        // Stable name + content → identical checksum across taps (for dedup).
        val file = File(context.cacheDir, "cuj-$tag-fixed.bin")
        if (file.length().toInt() != sizeBytes) {
            file.writeBytes(ByteArray(sizeBytes) { fill })
        }
        file
    }
}
