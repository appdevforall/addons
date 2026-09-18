package com.itsaky.androidide.plugins.aiagentgemini.errors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isCredentialProblem], which decides what the settings pane is allowed to report
 * as a key problem.
 *
 * Every failure is listed rather than only the two that answer true: reporting an outage or a spent
 * quota as a refused key sends the user off to replace a credential that works, which is the
 * confusion ADFA-5491 exists to remove.
 */
class GeminiCredentialProblemTest {

    @Test
    fun givenARefusedKey_whenClassified_thenItIsACredentialProblem() {
        assertTrue(GeminiFailure.KeyRefused.isCredentialProblem)
        assertTrue(GeminiFailure.KeyInvalid.isCredentialProblem)
    }

    @Test
    fun givenAFailureAboutAnythingElse_whenClassified_thenItIsNotACredentialProblem() {
        val others = listOf(
            GeminiFailure.ModelUnavailable("gemini-2.5-flash"),
            GeminiFailure.QuotaExceeded,
            GeminiFailure.RequestRejected("too long"),
            GeminiFailure.RequestRejected(null),
            GeminiFailure.ServiceUnavailable(503),
            GeminiFailure.Unexpected(418, null),
            GeminiFailure.Unreachable,
            GeminiFailure.ReplyTruncated,
            GeminiFailure.Failed("socket closed"),
            GeminiFailure.Failed(null),
        )
        others.forEach { failure ->
            assertFalse("$failure must not be reported as a key problem", failure.isCredentialProblem)
        }
    }
}
