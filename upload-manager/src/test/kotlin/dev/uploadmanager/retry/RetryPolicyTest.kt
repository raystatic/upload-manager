package dev.uploadmanager.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class RetryPolicyTest {

    private val policy = RetryPolicy()

    @Test
    fun `park delays escalate and cap at the last entry`() {
        assertEquals(TimeUnit.MINUTES.toMillis(15), policy.parkDelayMs(0))
        assertEquals(TimeUnit.HOURS.toMillis(1), policy.parkDelayMs(1))
        assertEquals(TimeUnit.HOURS.toMillis(6), policy.parkDelayMs(2))
        assertEquals(TimeUnit.HOURS.toMillis(24), policy.parkDelayMs(3))
        // Beyond the table: stays capped.
        assertEquals(TimeUnit.HOURS.toMillis(24), policy.parkDelayMs(10))
        assertEquals(TimeUnit.HOURS.toMillis(24), policy.parkDelayMs(100))
    }

    @Test
    fun `negative park count is clamped`() {
        assertEquals(TimeUnit.MINUTES.toMillis(15), policy.parkDelayMs(-1))
    }

    @Test
    fun `ttl expiry only applies after first attempt`() {
        val now = System.currentTimeMillis()
        // firstAttemptAt == 0 means never attempted: never expired.
        assertFalse(policy.isExpired(0, now))
        assertFalse(policy.isExpired(now - TimeUnit.DAYS.toMillis(6), now))
        assertTrue(policy.isExpired(now - TimeUnit.DAYS.toMillis(8), now))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero fast tier attempts rejected`() {
        RetryPolicy(fastTierMaxAttempts = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty park delays rejected`() {
        RetryPolicy(parkDelaysMs = emptyList())
    }
}
