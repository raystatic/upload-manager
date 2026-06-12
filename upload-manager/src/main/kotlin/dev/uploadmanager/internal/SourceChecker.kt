package dev.uploadmanager.internal

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/** Source-file validation per revision doc 03 §4 (M1: readability + size fingerprint). */
object SourceChecker {

    data class Fingerprint(val sizeBytes: Long, val lastModified: Long)

    sealed class Result {
        object Ok : Result()
        object Gone : Result()
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

    /** Cheap pre-upload check: the URI must still be openable. */
    fun check(context: Context, uri: Uri): Result = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { } ?: return Result.Gone
        Result.Ok
    }.getOrDefault(Result.Gone)

    private fun openForSize(context: Context, uri: Uri): Fingerprint? = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
            Fingerprint(it.length, 0L)
        }
    }.getOrNull()
}
