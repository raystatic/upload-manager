package dev.uploadmanager.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.uploadmanager.api.UploadState

/**
 * Source of truth for every upload (spec §4.1, with the fields added by revision
 * docs 02 and 03 included from day one to avoid pre-1.0 schema migrations).
 */
@Entity(tableName = "upload_tasks")
data class UploadTaskEntity(
    @PrimaryKey val id: String,
    val uid: String,
    val localUri: String,
    val storagePath: String,
    val fileName: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val uploadState: UploadState,
    val uploadedBytes: Long = 0,
    val priority: Int,
    /** App-specific metadata, flattened as k=v lines (no nesting needed in M1). */
    val appMetadata: String = "",
    val downloadUrl: String? = null,
    val errorCode: String? = null,

    // Resumability (spec §4.1 + revision 02 §4)
    val uploadSessionUri: String? = null,
    val sessionCreatedAt: Long? = null,

    // Retry / park tier (revision 02 §3)
    val retryCount: Int = 0,
    val parkCount: Int = 0,
    val parkedUntil: Long? = null,
    val firstAttemptAt: Long = 0,

    // Source durability (revision 03; COPY staging lands in M2)
    val checksum: String? = null,
    val sourceSizeBytes: Long = 0,
    val sourceLastModified: Long = 0,
    val stagedPath: String? = null,
    val persistablePermission: Boolean = false,

    val createdAt: Long,
    val updatedAt: Long,
)
