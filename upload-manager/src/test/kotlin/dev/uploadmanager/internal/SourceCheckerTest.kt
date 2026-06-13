package dev.uploadmanager.internal

import dev.uploadmanager.internal.SourceChecker.Fingerprint
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceCheckerTest {

    @Test
    fun `identical fingerprint is unchanged`() {
        assertEquals(
            SourceChecker.Result.Ok,
            SourceChecker.compare(1000, 5000, Fingerprint(1000, 5000)),
        )
    }

    @Test
    fun `different size is a change`() {
        assertEquals(
            SourceChecker.Result.Changed,
            SourceChecker.compare(1000, 5000, Fingerprint(2000, 5000)),
        )
    }

    @Test
    fun `different last-modified is a change`() {
        assertEquals(
            SourceChecker.Result.Changed,
            SourceChecker.compare(1000, 5000, Fingerprint(1000, 9999)),
        )
    }

    @Test
    fun `unknown sizes do not trigger a false change`() {
        // -1 size means the provider didn't report it; can't conclude "changed".
        assertEquals(
            SourceChecker.Result.Ok,
            SourceChecker.compare(-1, 5000, Fingerprint(-1, 5000)),
        )
        assertEquals(
            SourceChecker.Result.Ok,
            SourceChecker.compare(1000, 5000, Fingerprint(-1, 5000)),
        )
    }

    @Test
    fun `missing last-modified is ignored when size matches`() {
        // Many providers report 0 for last_modified; size alone governs.
        assertEquals(
            SourceChecker.Result.Ok,
            SourceChecker.compare(1000, 0, Fingerprint(1000, 0)),
        )
        assertEquals(
            SourceChecker.Result.Ok,
            SourceChecker.compare(1000, 5000, Fingerprint(1000, 0)),
        )
    }
}
