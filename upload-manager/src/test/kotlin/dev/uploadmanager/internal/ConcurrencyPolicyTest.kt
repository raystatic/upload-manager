package dev.uploadmanager.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyPolicyTest {

    private fun conditions(
        battery: Int = 100,
        charging: Boolean = false,
        thermal: ThermalLevel = ThermalLevel.NONE,
        unmetered: Boolean = true,
    ) = DeviceConditions(battery, charging, thermal, unmetered)

    @Test
    fun `moderate or worse thermal pauses everything`() {
        for (t in listOf(ThermalLevel.MODERATE, ThermalLevel.SEVERE, ThermalLevel.CRITICAL)) {
            assertEquals(0, ConcurrencyPolicy.maxConcurrent(conditions(thermal = t), configMax = 3))
        }
    }

    @Test
    fun `cellular throttles to one`() {
        assertEquals(1, ConcurrencyPolicy.maxConcurrent(conditions(unmetered = false), 3))
    }

    @Test
    fun `wifi battery tiers`() {
        assertEquals(1, ConcurrencyPolicy.maxConcurrent(conditions(battery = 10), 3))
        assertEquals(2, ConcurrencyPolicy.maxConcurrent(conditions(battery = 35), 3))
        assertEquals(3, ConcurrencyPolicy.maxConcurrent(conditions(battery = 80), 3))
    }

    @Test
    fun `charging behaves like a healthy battery`() {
        assertEquals(3, ConcurrencyPolicy.maxConcurrent(conditions(battery = 5, charging = true), 3))
    }

    @Test
    fun `result is capped at the configured maximum`() {
        assertEquals(2, ConcurrencyPolicy.maxConcurrent(conditions(battery = 90), configMax = 2))
    }

    @Test
    fun `small uploads are always allowed`() {
        val hostile = conditions(battery = 5, thermal = ThermalLevel.CRITICAL, unmetered = false)
        assertTrue(ConcurrencyPolicy.allowsLargeUpload(hostile, sizeBytes = 1024, largeThresholdBytes = 50_000))
    }

    @Test
    fun `large uploads are held back under hostile conditions`() {
        val threshold = 10L * 1024 * 1024
        val big = 100L * 1024 * 1024
        assertFalse(ConcurrencyPolicy.allowsLargeUpload(conditions(unmetered = false), big, threshold))
        assertFalse(ConcurrencyPolicy.allowsLargeUpload(conditions(thermal = ThermalLevel.MODERATE), big, threshold))
        assertFalse(ConcurrencyPolicy.allowsLargeUpload(conditions(battery = 10), big, threshold))
        assertTrue(ConcurrencyPolicy.allowsLargeUpload(conditions(battery = 80), big, threshold))
        assertTrue(ConcurrencyPolicy.allowsLargeUpload(conditions(battery = 10, charging = true), big, threshold))
    }
}
