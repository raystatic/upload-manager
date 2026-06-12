package dev.uploadmanager.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.uploadmanager.api.UploadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadTaskDaoTest {

    private lateinit var db: UploadDatabase
    private lateinit var dao: UploadTaskDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, UploadDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.uploadTaskDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun task(
        id: String = "t1",
        state: UploadState = UploadState.PENDING,
        priority: Int = 2,
        parkedUntil: Long? = null,
    ) = UploadTaskEntity(
        id = id,
        uid = "user1",
        localUri = "content://test/$id",
        storagePath = "users/user1/files/$id",
        fileName = "$id.jpg",
        mimeType = "image/jpeg",
        fileSizeBytes = 1024,
        uploadState = state,
        priority = priority,
        parkedUntil = parkedUntil,
        createdAt = 1000,
        updatedAt = 1000,
    )

    @Test
    fun `session uri timestamp and state persist atomically`() = runTest {
        dao.upsert(task())
        dao.saveSession("t1", "https://session/abc", 2000)

        val loaded = dao.getById("t1")!!
        assertEquals("https://session/abc", loaded.uploadSessionUri)
        assertEquals(2000L, loaded.sessionCreatedAt)
        assertEquals(UploadState.UPLOADING, loaded.uploadState)
    }

    @Test
    fun `clear session resets uri timestamp and progress`() = runTest {
        dao.upsert(task())
        dao.saveSession("t1", "https://session/abc", 2000)
        dao.updateProgress("t1", 512, 2100)

        dao.clearSession("t1", 2200)

        val loaded = dao.getById("t1")!!
        assertNull(loaded.uploadSessionUri)
        assertNull(loaded.sessionCreatedAt)
        assertEquals(0L, loaded.uploadedBytes)
    }

    @Test
    fun `compare and set does not clobber a concurrent transition`() = runTest {
        dao.upsert(task(state = UploadState.UPLOADING))
        // API pauses the task...
        dao.updateState("t1", UploadState.PAUSED, null, 2000)
        // ...then the worker's cancellation handler tries UPLOADING -> QUEUED.
        val changed = dao.compareAndSetState("t1", UploadState.UPLOADING, UploadState.QUEUED, 2100)

        assertEquals(0, changed)
        assertEquals(UploadState.PAUSED, dao.getById("t1")!!.uploadState)
    }

    @Test
    fun `parked tasks become due only after parkedUntil`() = runTest {
        dao.upsert(task(id = "due", state = UploadState.PARKED, parkedUntil = 1000))
        dao.upsert(task(id = "later", state = UploadState.PARKED, parkedUntil = 9000))

        val due = dao.getParkedDue(now = 5000)
        assertEquals(listOf("due"), due.map { it.id })
        assertEquals(2, dao.countParked())
    }

    @Test
    fun `first attempt timestamp is written only once`() = runTest {
        dao.upsert(task())
        dao.markFirstAttempt("t1", 1111)
        dao.markFirstAttempt("t1", 9999)

        assertEquals(1111L, dao.getById("t1")!!.firstAttemptAt)
    }

    @Test
    fun `mark completed sets url full progress and clears error`() = runTest {
        dao.upsert(task(state = UploadState.UPLOADING).copy(errorCode = "IO_X", uploadedBytes = 10))
        dao.markCompleted("t1", "https://dl/abc", 3000)

        val loaded = dao.getById("t1")!!
        assertEquals(UploadState.COMPLETED, loaded.uploadState)
        assertEquals("https://dl/abc", loaded.downloadUrl)
        assertEquals(loaded.fileSizeBytes, loaded.uploadedBytes)
        assertNull(loaded.errorCode)
    }

    @Test
    fun `reset counters returns task to pending`() = runTest {
        dao.upsert(
            task(state = UploadState.FAILED).copy(
                retryCount = 5, parkCount = 3, parkedUntil = 99, firstAttemptAt = 1, errorCode = "X"
            )
        )
        dao.resetCounters("t1", 4000)

        val loaded = dao.getById("t1")!!
        assertEquals(UploadState.PENDING, loaded.uploadState)
        assertEquals(0, loaded.retryCount)
        assertEquals(0, loaded.parkCount)
        assertNull(loaded.parkedUntil)
        assertEquals(0L, loaded.firstAttemptAt)
        assertNull(loaded.errorCode)
    }

    @Test
    fun `dispatchable excludes paused parked and terminal`() = runTest {
        dao.upsert(task(id = "p", state = UploadState.PENDING))
        dao.upsert(task(id = "q", state = UploadState.QUEUED))
        dao.upsert(task(id = "r", state = UploadState.RETRYING))
        dao.upsert(task(id = "paused", state = UploadState.PAUSED))
        dao.upsert(task(id = "parked", state = UploadState.PARKED, parkedUntil = 99))
        dao.upsert(task(id = "done", state = UploadState.COMPLETED))

        val ids = dao.getDispatchable().map { it.id }.toSet()
        assertEquals(setOf("p", "q", "r"), ids)
    }

    @Test
    fun `observeByUid orders by priority then age`() = runTest {
        dao.upsert(task(id = "old-p2", priority = 2).copy(createdAt = 100))
        dao.upsert(task(id = "new-p0", priority = 0).copy(createdAt = 200))
        dao.upsert(task(id = "new-p2", priority = 2).copy(createdAt = 300))

        val captured = dao.observeByUid("user1").first()
        assertEquals(listOf("new-p0", "old-p2", "new-p2"), captured.map { it.id })
    }

    @Test
    fun `clear completed removes only completed rows`() = runTest {
        dao.upsert(task(id = "a", state = UploadState.COMPLETED))
        dao.upsert(task(id = "b", state = UploadState.UPLOADING))

        assertEquals(1, dao.clearCompleted())
        assertNull(dao.getById("a"))
        assertNotNull(dao.getById("b"))
        assertTrue(dao.getDispatchable().isEmpty()) // b is UPLOADING, not dispatchable
    }
}
