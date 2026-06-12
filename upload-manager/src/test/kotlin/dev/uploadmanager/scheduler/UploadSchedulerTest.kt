package dev.uploadmanager.scheduler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.uploadmanager.api.NetworkPreference
import dev.uploadmanager.api.UploadManagerConfig
import dev.uploadmanager.api.UploadState
import dev.uploadmanager.db.UploadTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UploadSchedulerTest {

    private lateinit var scheduler: UploadScheduler
    private lateinit var cellularScheduler: UploadScheduler

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val wm = WorkManager.getInstance(context)
        scheduler = UploadScheduler(wm, UploadManagerConfig(networkPreference = NetworkPreference.WIFI_ONLY))
        cellularScheduler = UploadScheduler(
            wm,
            UploadManagerConfig(
                networkPreference = NetworkPreference.ALLOW_CELLULAR,
                cellularMaxBytes = 10L * 1024 * 1024,
            ),
        )
    }

    private fun task(priority: Int, sizeBytes: Long = 1024) = UploadTaskEntity(
        id = "t-$priority-$sizeBytes",
        uid = "u",
        localUri = "content://x",
        storagePath = "users/u/files/t",
        fileName = "f",
        mimeType = "image/jpeg",
        fileSizeBytes = sizeBytes,
        uploadState = UploadState.PENDING,
        priority = priority,
        createdAt = 0,
        updatedAt = 0,
    )

    @Test
    fun `wifi only forces unmetered for every priority`() {
        for (p in 0..4) {
            assertEquals(
                NetworkType.UNMETERED,
                scheduler.constraintsFor(task(priority = p)).requiredNetworkType,
            )
        }
    }

    @Test
    fun `cellular allowed for small files but large files wait for wifi`() {
        assertEquals(
            NetworkType.CONNECTED,
            cellularScheduler.constraintsFor(task(priority = 2, sizeBytes = 1024)).requiredNetworkType,
        )
        assertEquals(
            NetworkType.UNMETERED,
            cellularScheduler.constraintsFor(task(priority = 2, sizeBytes = 50L * 1024 * 1024)).requiredNetworkType,
        )
    }

    @Test
    fun `p4 backfill requires charging, p0 does not`() {
        assertTrue(cellularScheduler.constraintsFor(task(priority = 4)).requiresCharging())
        assertFalse(cellularScheduler.constraintsFor(task(priority = 0)).requiresCharging())
    }

    @Test
    fun `p0 has no battery constraint but p1-p4 do`() {
        assertFalse(cellularScheduler.constraintsFor(task(priority = 0)).requiresBatteryNotLow())
        for (p in 1..4) {
            assertTrue(cellularScheduler.constraintsFor(task(priority = p)).requiresBatteryNotLow())
        }
    }

    @Test
    fun `p0 requests are expedited`() {
        val request = scheduler.buildRequest(task(priority = 0))
        assertTrue(request.workSpec.expedited)
        val normal = scheduler.buildRequest(task(priority = 2))
        assertFalse(normal.workSpec.expedited)
    }

    @Test
    fun `requests carry the task id tag and backoff config`() {
        val t = task(priority = 1)
        val request = scheduler.buildRequest(t)
        assertTrue(request.tags.contains(t.id))
        assertTrue(request.tags.contains(UploadScheduler.TAG_UPLOAD))
        assertEquals(2_000L, request.workSpec.backoffDelayDuration)
    }
}
