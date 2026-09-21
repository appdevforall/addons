package com.itsaky.androidide.plugins.aiagentmcp.errors

import com.itsaky.androidide.plugins.aiagentmcp.client.McpProtocolException
import com.itsaky.androidide.plugins.aiagentmcp.security.UnavailableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.security.UnreadableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpRedirectException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [McpErrorFormatter]. The classification is what decides whether a user re-types a
 * token or goes looking for a network problem that does not exist.
 */
class McpErrorFormatterTest {

    @Test
    fun givenAnUndecryptableToken_whenClassified_thenItIsNotMistakenForARefusedOne() {
        // The token never reached the wire, so "the server refused it" would send the user to
        // re-check a token that is still stored and still correct.
        val failure = McpErrorFormatter.classify(UnreadableSecretException("no key"))

        assertEquals(McpFailure.SecretUnreadable, failure)
    }

    @Test
    fun givenAKeystoreThatWouldNotAnswer_whenClassified_thenItIsRetryableRatherThanAReachFailure() {
        // Kept apart from the arm above and from the generic one: nothing was sent, so "Could not
        // reach X" sends the user hunting a network problem when the answer is to try again.
        val failure = McpErrorFormatter.classify(UnavailableSecretException("keystore busy"))

        assertEquals(McpFailure.SecretUnavailable, failure)
    }

    @Test
    fun givenARefusedRedirect_whenClassified_thenItIsItsOwnFailureRatherThanAGenericOne() {
        // "Could not reach X: <reason>" would read as a network problem; nothing was sent, and the
        // endpoint URL is what the user has to look at.
        val failure = McpErrorFormatter.classify(McpRedirectException("off origin"))

        assertEquals(McpFailure.RedirectRefused, failure)
    }

    @Test
    fun givenAnUnauthorizedStatus_whenClassified_thenItPointsAtTheToken() {
        val failure = McpErrorFormatter.classify(McpHttpException(401, "{}"))

        assertEquals(McpFailure.TokenRefused, failure)
    }

    @Test
    fun givenAMethodNotAllowed_whenClassified_thenItPointsAtTheTransport() {
        // The commonest way a non-MCP URL fails, and the least obvious to a user.
        assertEquals(McpFailure.WrongTransport, McpErrorFormatter.classify(McpHttpException(405, "")))
    }

    @Test
    fun givenAServerSideStatus_whenClassified_thenTheStatusIsCarried() {
        val failure = McpErrorFormatter.classify(McpHttpException(503, "upstream down"))

        assertEquals(McpFailure.ServerError(503), failure)
    }

    @Test
    fun givenAnUnhandledStatus_whenClassified_thenItStaysGenericRatherThanGuessing() {
        assertEquals(McpFailure.Http(418), McpErrorFormatter.classify(McpHttpException(418, "")))
    }

    @Test
    fun givenAJsonRpcError_whenClassified_thenTheServersOwnWordsAreKept() {
        val failure = McpErrorFormatter.classify(McpProtocolException(-32602, "Unknown tool"))

        assertEquals(McpFailure.Rejected("Unknown tool"), failure)
    }

    @Test
    fun givenATimeout_whenClassified_thenItIsNotReportedAsCancellation() {
        // SocketTimeoutException extends InterruptedIOException, which is the cancellation branch.
        assertEquals(McpFailure.TimedOut, McpErrorFormatter.classify(SocketTimeoutException("read")))
    }

    @Test
    fun givenADnsFailure_whenClassified_thenItPointsAtTheUrl() {
        assertEquals(McpFailure.UnknownHost, McpErrorFormatter.classify(UnknownHostException("nope")))
    }

    @Test
    fun givenATlsFailure_whenClassified_thenItPointsAtTheScheme() {
        assertEquals(McpFailure.TlsFailed, McpErrorFormatter.classify(SSLHandshakeException("bad cert")))
    }

    @Test
    fun givenSomethingElse_whenClassified_thenTheReasonSurvivesForTheLog() {
        val failure = McpErrorFormatter.classify(IOException("socket closed"))

        assertTrue(failure is McpFailure.Failed)
        assertEquals("socket closed", (failure as McpFailure.Failed).reason)
    }

    @Test
    fun givenNoPluginContext_whenFormatted_thenTheUserStillGetsASentence() {
        val message = McpErrorFormatter.format(null, "Docs", IOException("socket closed"))

        assertTrue(message.contains("Docs"))
        assertTrue(message.contains("socket closed"))
    }
}
