package com.itsaky.androidide.plugins.aiagentmcp.errors

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What [runCatchingRecoverable] keeps as a failure and what it lets through. */
class RecoverableTest {

    @Test
    fun `a cancellation is rethrown, not kept as a failure`() {
        val thrown = assertThrows(CancellationException::class.java) {
            runCatchingRecoverable<Unit> { throw CancellationException("the coroutine was cancelled") }
        }

        assertEquals("the coroutine was cancelled", thrown.message)
    }

    @Test
    fun `an exception and a linkage error are kept as failures`() {
        assertTrue(runCatchingRecoverable<Unit> { throw IllegalStateException("a host call") }.isFailure)
        assertTrue(runCatchingRecoverable<Unit> { throw AbstractMethodError("a host call") }.isFailure)
    }

    @Test
    fun `an OutOfMemoryError is let through`() {
        assertThrows(OutOfMemoryError::class.java) {
            runCatchingRecoverable<Unit> { throw OutOfMemoryError("the process") }
        }
    }
}
