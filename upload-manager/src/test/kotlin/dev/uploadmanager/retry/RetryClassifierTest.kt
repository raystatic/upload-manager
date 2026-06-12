package dev.uploadmanager.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException

/**
 * StorageException has no public constructor, so the StorageException branches are
 * exercised by the emulator integration tests; these cover the generic paths.
 */
class RetryClassifierTest {

    private val policy = RetryPolicy(fastTierMaxAttempts = 5)

    @Test
    fun `plain io exception is transient until tier exhausted`() {
        assertEquals(FailureAction.RETRY_FAST, RetryClassifier.classify(IOException("radio drop"), 1, policy))
        assertEquals(FailureAction.RETRY_FAST, RetryClassifier.classify(SocketTimeoutException(), 4, policy))
        assertEquals(FailureAction.PARK, RetryClassifier.classify(IOException("still down"), 5, policy))
        assertEquals(FailureAction.PARK, RetryClassifier.classify(IOException(), 6, policy))
    }

    @Test
    fun `wrapped io exception is found through the cause chain`() {
        val wrapped = RuntimeException("upload failed", IOException("underneath"))
        assertEquals(FailureAction.RETRY_FAST, RetryClassifier.classify(wrapped, 1, policy))
    }

    @Test
    fun `unknown exception is treated as transient`() {
        assertEquals(FailureAction.RETRY_FAST, RetryClassifier.classify(RuntimeException("?"), 1, policy))
    }

    @Test
    fun `cancellation is terminal`() {
        assertEquals(FailureAction.TERMINAL, RetryClassifier.classify(CancellationException(), 1, policy))
    }

    @Test
    fun `error codes are descriptive`() {
        assertTrue(RetryClassifier.errorCode(IOException()).startsWith("IO_"))
        assertEquals("RuntimeException", RetryClassifier.errorCode(RuntimeException()))
    }
}
