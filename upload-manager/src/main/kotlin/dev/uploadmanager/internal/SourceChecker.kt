package dev.uploadmanager.internal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Source-file validation per revision doc 03 §4 (readability + size/mtime fingerprint). */
object SourceChecker {

    data class Fingerprint(val sizeBytes: Long, val lastModified: Long)

    sealed class Result {
        object Ok : Result()
        object Gone : Result()
        object Changed : Result()
    }

    /** Captured at enqueue. Returns null if the URI is not readable at all. */
    fun fingerprint(context: Context, uri: Uri): Fingerprint? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@runCatching null
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else -1L
            val modIdx = cursor.getColumnIndex("last_modified")
            val mod = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L
            Fingerprint(size, mod)
        } ?: openForSize(context, uri)
    }.getOrNull()

    /**
     * Pre-upload check for a non-staged (REFERENCE) source: it must still be
     * readable ([Gone] otherwise) and match the fingerprint captured at enqueue
     * ([Changed] otherwise). A size or last-modified mismatch means the content
     * may differ, so a resumed upload must restart rather than splice bytes.
     */
    fun check(context: Context, uri: Uri, expectedSize: Long, expectedLastModified: Long): Result {
        val current = fingerprint(context, uri) ?: return Result.Gone
        val sizeChanged = expectedSize >= 0 && current.sizeBytes >= 0 && current.sizeBytes != expectedSize
        val mtimeChanged = expectedLastModified > 0 && current.lastModified > 0 &&
            current.lastModified != expectedLastModified
        return if (sizeChanged || mtimeChanged) Result.Changed else Result.Ok
    }

    private fun openForSize(context: Context, uri: Uri): Fingerprint? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            Fingerprint(it.length, 0L)
        }
    }.getOrNull()
}
