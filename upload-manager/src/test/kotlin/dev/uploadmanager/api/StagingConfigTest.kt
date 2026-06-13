package dev.uploadmanager.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingConfigTest {

    @Test
    fun `reference mode stages files at or below the auto-copy threshold`() {
        val config = StagingConfig(mode = StagingMode.REFERENCE, autoCopyBelowBytes = 64L * 1024 * 1024)
        assertTrue(config.shouldStage(1024))
        assertTrue(config.shouldStage(64L * 1024 * 1024)) // boundary inclusive
        assertFalse(config.shouldStage(64L * 1024 * 1024 + 1))
    }

    @Test
    fun `reference mode does not stage unknown-size sources`() {
        val config = StagingConfig(mode = StagingMode.REFERENCE)
        assertFalse(config.shouldStage(-1L))
    }

    @Test
    fun `copy mode always stages`() {
        val config = StagingConfig(mode = StagingMode.COPY, autoCopyBelowBytes = 1)
        assertTrue(config.shouldStage(0))
        assertTrue(config.shouldStage(Long.MAX_VALUE))
        assertTrue(config.shouldStage(-1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative auto-copy threshold rejected`() {
        StagingConfig(autoCopyBelowBytes = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non-positive dir budget rejected`() {
        StagingConfig(stagingDirMaxBytes = 0)
    }
}
