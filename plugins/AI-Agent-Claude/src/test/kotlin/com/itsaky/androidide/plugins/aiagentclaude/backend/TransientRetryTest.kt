package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The retry schedule: what is retried, how often, and how long it may wait. */
class TransientRetryTest {

    private fun status(code: Int, retryAfter: Long? = null) = ClaudeHttpException(code, "{}", retryAfter)

    @Test
    fun givenAnOverload_whenFirstSeen_thenItIsRetriedAfterTheBaseDelay() {
        assertEquals(1_000L, TransientRetry.delayMs(status(529), retriesSoFar = 0))
    }

    @Test
    fun givenRepeatedFailures_whenRetried_thenTheDelayDoubles() {
        assertEquals(2_000L, TransientRetry.delayMs(status(503), retriesSoFar = 1))
    }

    @Test
    fun givenTheRetryBudgetIsSpent_whenItFailsAgain_thenItGivesUp() {
        assertNull(TransientRetry.delayMs(status(529), retriesSoFar = TransientRetry.MAX_RETRIES))
    }

    @Test
    fun givenARetryAfterHeader_whenRetried_thenItIsHonouredUpToTheCap() {
        assertEquals(3_000L, TransientRetry.delayMs(status(429, retryAfter = 3), retriesSoFar = 0))
        assertEquals(10_000L, TransientRetry.delayMs(status(429, retryAfter = 120), retriesSoFar = 0))
    }

    @Test
    fun givenAFailureAboutTheRequestOrKey_whenSeen_thenItIsNotRetried() {
        // Sending the same request again can only fail the same way, and slower.
        listOf(400, 401, 403, 404, 413).forEach { code ->
            assertNull("$code must not retry", TransientRetry.delayMs(status(code), retriesSoFar = 0))
        }
    }

    @Test
    fun givenNoHttpAnswer_whenSeen_thenItIsNotRetried() {
        // A dead network is not the API asking for patience; the user is better told at once.
        assertNull(TransientRetry.delayMs(IOException("Unable to resolve host"), retriesSoFar = 0))
    }
}
