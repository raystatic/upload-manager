package dev.uploadmanager.internal

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyGovernorTest {

    @Test
    fun `admits up to the limit then blocks`() = runTest {
        val governor = ConcurrencyGovernor(2)
        val entered = AtomicInteger(0)
        val hold = CompletableDeferred<Unit>()

        repeat(3) {
            launch { governor.withSlot { entered.incrementAndGet(); hold.await() } }
        }
        advanceUntilIdle()
        assertEquals(2, entered.get()) // third is queued behind the limit

        hold.complete(Unit)
        advanceUntilIdle()
        assertEquals(3, entered.get()) // freed slot admits the third
    }

    @Test
    fun `raising the limit admits a waiter`() = runTest {
        val governor = ConcurrencyGovernor(1)
        val entered = AtomicInteger(0)
        val hold = CompletableDeferred<Unit>()

        repeat(2) {
            launch { governor.withSlot { entered.incrementAndGet(); hold.await() } }
        }
        advanceUntilIdle()
        assertEquals(1, entered.get())

        governor.setLimit(2)
        advanceUntilIdle()
        assertEquals(2, entered.get())

        hold.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `lowering the limit does not preempt in-flight work`() = runTest {
        val governor = ConcurrencyGovernor(2)
        val entered = AtomicInteger(0)
        val hold = CompletableDeferred<Unit>()

        repeat(2) {
            launch { governor.withSlot { entered.incrementAndGet(); hold.await() } }
        }
        advanceUntilIdle()
        assertEquals(2, entered.get())

        governor.setLimit(1) // active stays at 2; just no new admissions
        advanceUntilIdle()
        assertEquals(2, governor.snapshot().first)

        hold.complete(Unit)
        advanceUntilIdle()
    }
}
