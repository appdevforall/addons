package com.itsaky.androidide.plugins.aiagentclaude.errors

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
class ClaudeCredentialProblemTest {

    @Test
    fun givenARefusedKey_whenClassified_thenItIsACredentialProblem() {
        assertTrue(ClaudeFailure.KeyRefused.isCredentialProblem)
        assertTrue(ClaudeFailure.KeyMissing.isCredentialProblem)
        assertTrue(ClaudeFailure.KeyForbidden.isCredentialProblem)
    }

    @Test
    fun givenASpentQuota_whenClassified_thenItIsNotACredentialProblem() {
        // The key was accepted; the account simply has nothing left to spend, and telling the user
        // their key was refused would have them replace a working one.
        assertFalse(ClaudeFailure.QuotaExceeded.isCredentialProblem)
        assertFalse(ClaudeFailure.BillingRequired.isCredentialProblem)
    }

    @Test
    fun givenAFailureAboutAnythingElse_whenClassified_thenItIsNotACredentialProblem() {
        val others = listOf(
            ClaudeFailure.ModelUnavailable("gpt-5"),
            ClaudeFailure.RequestRejected("too long"),
            ClaudeFailure.RequestRejected(null),
            ClaudeFailure.ServiceUnavailable(503),
            ClaudeFailure.Unexpected(418, null),
            ClaudeFailure.ServerNotRunning,
            ClaudeFailure.Unreachable,
            ClaudeFailure.EmptyReply(skippedChunks = 3),
            ClaudeFailure.ReasoningOnly,
            ClaudeFailure.TruncatedBeforeReply,
            ClaudeFailure.Failed("socket closed"),
            ClaudeFailure.Failed(null),
        )
        others.forEach { failure ->
            assertFalse("$failure must not be reported as a key problem", failure.isCredentialProblem)
        }
    }
}
