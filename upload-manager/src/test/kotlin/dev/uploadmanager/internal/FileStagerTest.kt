package dev.uploadmanager.internal

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class FileStagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun registerSource(uri: Uri, bytes: ByteArray) {
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
    }

    @Test
    fun `stage copies bytes and computes sha256 in one pass`() {
        val bytes = "hello upload manager".toByteArray()
        val uri = Uri.parse("content://test/source")
        registerSource(uri, bytes)
        val stager = FileStager(context, maxDirBytes = Long.MAX_VALUE)

        val staged = stager.stage("task-1", uri)!!

        assertEquals(bytes.size.toLong(), staged.sizeBytes)
        assertEquals(sha256Hex(bytes), staged.checksum)
        assertArrayEquals(bytes, File(staged.path).readBytes())
    }

    @Test
    fun `stage returns null when the staging budget is exhausted`() {
        val stager = FileStager(context, maxDirBytes = 4)
        stager.stagingDir.mkdirs()
        File(stager.stagingDir, "existing").writeBytes(ByteArray(8)) // already over the 4-byte cap

        val uri = Uri.parse("content://test/source")
        registerSource(uri, "data".toByteArray())

        assertNull(stager.stage("task-2", uri))
    }

    @Test
    fun `cleanup deletes only the named task's staged file`() {
        val stager = FileStager(context, maxDirBytes = Long.MAX_VALUE)
        stager.stagingDir.mkdirs()
        File(stager.stagingDir, "keep").writeBytes(ByteArray(2))
        File(stager.stagingDir, "drop").writeBytes(ByteArray(2))

        stager.cleanup("drop")

        assertTrue(File(stager.stagingDir, "keep").exists())
        assertFalse(File(stager.stagingDir, "drop").exists())
    }

    @Test
    fun `reconcile deletes staged files for inactive tasks only`() {
        val stager = FileStager(context, maxDirBytes = Long.MAX_VALUE)
        stager.stagingDir.mkdirs()
        File(stager.stagingDir, "active").writeBytes(ByteArray(2))
        File(stager.stagingDir, "orphan").writeBytes(ByteArray(2))

        stager.reconcile(setOf("active"))

        assertTrue(File(stager.stagingDir, "active").exists())
        assertFalse(File(stager.stagingDir, "orphan").exists())
    }

    @Test
    fun `bytesUsed sums the staging directory`() {
        val stager = FileStager(context, maxDirBytes = Long.MAX_VALUE)
        stager.stagingDir.mkdirs()
        File(stager.stagingDir, "a").writeBytes(ByteArray(3))
        File(stager.stagingDir, "b").writeBytes(ByteArray(5))

        assertEquals(8L, stager.bytesUsed())
    }

    @Test
    fun `toHex encodes bytes lower-case fixed-width`() {
        assertEquals("00", byteArrayOf(0).toHex())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHex())
        assertEquals("0a10", byteArrayOf(0x0A, 0x10).toHex())
    }
}
