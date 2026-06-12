package dev.uploadmanager.api

import android.net.Uri

enum class UploadPriority(val value: Int) {
    P0(0), // most recent / user-visible
    P1(1),
    P2(2), // NORMAL
    P3(3),
    P4(4); // historical backfill

    companion object {
        val NORMAL = P2
    }
}

data class UploadRequest(
    val localUri: Uri,
    val mimeType: String,
    val fileName: String,
    val priority: UploadPriority = UploadPriority.NORMAL,
    /** App-specific metadata, stored as Storage custom metadata. */
    val metadata: Map<String, String> = emptyMap(),
)
