package dev.uploadmanager.internal

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Stages source files into app-private storage so an upload reads an immutable
 * snapshot rather than a volatile content URI (revision doc 03 §2). The SHA-256
 * checksum is computed in the same pass, so staging makes hashing effectively free.
 */
class FileStager(
    appContext: Context,
    private val maxDirBytes: Long,
) {
    private val resolver = appContext.contentResolver
    val stagingDir: File = File(appContext.filesDir, STAGING_DIR)

    data class Staged(val path: String, val sizeBytes: Long, val checksum: String)

    /**
     * Copies [source] to `staging/{taskId}` and returns its path, size, and SHA-256.
     * Returns null if the staging budget is exhausted or the source can't be read,
     * so the caller falls back to REFERENCE mode.
     */
    fun stage(taskId: String, source: Uri): Staged? {
        if (currentDirBytes() >= maxDirBytes) return null
        stagingDir.mkdirs()
        val dest = File(stagingDir, taskId)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val ok = runCatching {
            resolver.openInputStream(source)?.use { input ->
                DigestInputStream(input, digest).use { hashing ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = hashing.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)

        if (!ok) {
            dest.delete()
            return null
        }
        return Staged(dest.absolutePath, total, digest.digest().toHex())
    }

    fun cleanup(taskId: String) {
        File(stagingDir, taskId).delete()
    }

    /** Deletes staged files whose task is no longer active (crash/terminal residue). */
    fun reconcile(activeTaskIds: Set<String>) {
        stagingDir.listFiles()?.forEach { file ->
            if (file.name !in activeTaskIds) file.delete()
        }
    }

    fun bytesUsed(): Long = currentDirBytes()

    private fun currentDirBytes(): Long =
        stagingDir.listFiles()?.sumOf { it.length() } ?: 0L

    private companion object {
        const val STAGING_DIR = "upload_staging"
    }
}

internal fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX[v ushr 4])
        out.append(HEX[v and 0x0F])
    }
    return out.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
