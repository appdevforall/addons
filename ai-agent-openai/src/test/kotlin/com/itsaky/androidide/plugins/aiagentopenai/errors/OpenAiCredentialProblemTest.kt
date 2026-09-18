package com.itsaky.androidide.plugins.aiagentopenai.errors

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isCredentialProblem], which decides what the settings pane is allowed to report
 * as a key problem.
 *
 * Every failure is listed rather than only the three that answer true: reporting an outage or a
 * spent quota as a refused key sends the user off to replace a credential that works, which is the
 * confusion ADFA-5491 exists to remove.
 */
class OpenAiCredentialProblemTest {

    @Test
    fun givenARefusedKey_whenClassified_thenItIsACredentialProblem() {
        assertTrue(OpenAiFailure.KeyRefused.isCredentialProblem)
        assertTrue(OpenAiFailure.KeyMissing.isCredentialProblem)
        assertTrue(OpenAiFailure.KeyForbidden.isCredentialProblem)
    }

    @Test
    fun givenASpentQuota_whenClassified_thenItIsNotACredentialProblem() {
        // The key was accepted; the account simply has nothing left to spend, and telling the user
        // their key was refused would have them replace a working one.
        assertFalse(OpenAiFailure.QuotaExceeded.isCredentialProblem)
        assertFalse(OpenAiFailure.BillingRequired.isCredentialProblem)
    }

    @Test
    fun givenAFailureAboutAnythingElse_whenClassified_thenItIsNotACredentialProblem() {
        val others = listOf(
            OpenAiFailure.ModelUnavailable("gpt-5"),
            OpenAiFailure.RequestRejected("too long"),
            OpenAiFailure.RequestRejected(null),
            OpenAiFailure.ServiceUnavailable(503),
            OpenAiFailure.Unexpected(418, null),
            OpenAiFailure.ServerNotRunning,
            OpenAiFailure.Unreachable,
            OpenAiFailure.EmptyReply(skippedChunks = 3),
            OpenAiFailure.ReasoningOnly,
            OpenAiFailure.TruncatedBeforeReply,
            OpenAiFailure.Failed("socket closed"),
            OpenAiFailure.Failed(null),
        )
        others.forEach { failure ->
            assertFalse("$failure must not be reported as a key problem", failure.isCredentialProblem)
        }
    }
}
