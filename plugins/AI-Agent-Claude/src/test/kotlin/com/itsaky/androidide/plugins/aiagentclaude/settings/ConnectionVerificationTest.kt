package com.itsaky.androidide.plugins.aiagentclaude.settings

import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * The verdict mapping. It decides whether a key is written to disk, so a wrong row here either
 * stores a key the API already refused or refuses a good one because the network was down.
 */
class ConnectionVerificationTest {

    private fun failedWith(status: Int): ConnectionVerification =
        CatalogResult.Failed(ClaudeHttpException(status, "{}")).toConnectionVerification()

    @Test
    fun givenModelsCameBack_whenInterpreted_thenTheConnectionIsVerified() {
        val verdict = CatalogResult.Success(listOf("claude-opus-5-5", "claude-haiku-4-5")).toConnectionVerification()
        assertEquals(ConnectionVerification.Verified(2), verdict)
        assertTrue(verdict.isConfirmedValid)
    }

    @Test
    fun givenAnEmptyCatalog_whenInterpreted_thenTheKeyWorksButHasNoModels() {
        // The API answered 200, so the key was accepted; it is the account that has nothing.
        val verdict = CatalogResult.Success(emptyList()).toConnectionVerification()
        assertEquals(ConnectionVerification.NoModels, verdict)
        assertTrue(verdict.isConfirmedValid)
    }

    @Test
    fun givenNoBackend_whenInterpreted_thenNothingIsKnown() {
        assertEquals(ConnectionVerification.Unknown, CatalogResult.NoBackend.toConnectionVerification())
    }

    @Test
    fun givenA401_whenInterpreted_thenTheCredentialIsRejected() {
        assertEquals(ConnectionVerification.Rejected, failedWith(401))
    }

    @Test
    fun givenA403_whenInterpreted_thenTheCredentialIsRejected() {
        assertEquals(ConnectionVerification.Rejected, failedWith(403))
    }

    @Test
    fun givenA429_whenInterpreted_thenTheCredentialIsStillValid() {
        // Ordered before the other 4xx on purpose: a throttled key is a working key.
        val verdict = failedWith(429)
        assertEquals(ConnectionVerification.RateLimited, verdict)
        assertTrue(verdict.isConfirmedValid)
    }

    @Test
    fun givenAnotherClientError_whenInterpreted_thenNothingIsEstablished() {
        // Only 401 and 403 are about the key; blocking a save on anything else would refuse a
        // key the API never judged.
        assertEquals(ConnectionVerification.Unknown, failedWith(400))
        assertEquals(ConnectionVerification.Unknown, failedWith(404))
    }

    @Test
    fun givenAKeyWithNoWorkspace_whenInterpreted_thenItNeedsAWorkspaceAndIsNotSaved() {
        // Was Unknown, which offered "Save anyway" for a key every request then refused, and
        // blamed a missing AI Core plugin (ADFA-6311, on device).
        val body = """{"type":"error","error":{"type":"invalid_request_error","message":"This API key is not scoped to a workspace, so this request must include the anthropic-workspace-id header with the ID of the workspace to use. Add the header, or use an API key that is scoped to a workspace."}}"""
        val verdict = CatalogResult.Failed(ClaudeHttpException(400, body)).toConnectionVerification()
        assertEquals(ConnectionVerification.NeedsWorkspace, verdict)
        assertFalse(verdict.isConfirmedValid)
    }

    @Test
    fun givenAnOverloadedApi_whenInterpreted_thenItIsUnreachable() {
        assertEquals(ConnectionVerification.Unreachable, failedWith(529))
        assertEquals(ConnectionVerification.Unreachable, failedWith(503))
    }

    @Test
    fun givenATransportFailureWithNoStatus_whenInterpreted_thenTheApiIsUnreachable() {
        val verdict = CatalogResult.Failed(IOException("Unable to resolve host")).toConnectionVerification()
        assertEquals(ConnectionVerification.Unreachable, verdict)
    }

    @Test
    fun givenANonIoFailureWithNoStatus_whenInterpreted_thenNothingIsEstablished() {
        val verdict = CatalogResult.Failed(TimeoutException("gave up")).toConnectionVerification()
        assertEquals(ConnectionVerification.Unknown, verdict)
    }

    @Test
    fun givenAStatusNestedInACauseChain_whenInterpreted_thenItIsStillFound() {
        val nested = RuntimeException("wrapper", ClaudeHttpException(401, "{}"))
        assertEquals(
            ConnectionVerification.Rejected,
            CatalogResult.Failed(nested).toConnectionVerification()
        )
    }

    @Test
    fun givenAStatusOnlyInTheApisOwnBody_whenInterpreted_thenItIsNotReadAsAVerdict() {
        // The status is a field, so no wording the API sends back can forge one.
        val forged = ClaudeHttpException(200, """{"error":"HTTP 401 unauthorized"}""")
        val verdict = CatalogResult.Failed(forged).toConnectionVerification()
        assertEquals(ConnectionVerification.Unknown, verdict)
    }

    @Test
    fun givenEveryInconclusiveVerdict_whenAskedIfConfirmed_thenNoneIs() {
        listOf(
            ConnectionVerification.Rejected,
            ConnectionVerification.NeedsWorkspace,
            ConnectionVerification.Unreachable,
            ConnectionVerification.Unknown,
        ).forEach { assertFalse("$it must not confirm a key", it.isConfirmedValid) }
    }
}
