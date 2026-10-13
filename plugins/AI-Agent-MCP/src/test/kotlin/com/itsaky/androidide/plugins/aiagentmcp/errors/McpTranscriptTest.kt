package com.itsaky.androidide.plugins.aiagentmcp.errors

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchange
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a failed attempt is laid out: `>` sent, `<` received, `*` what interrupted it. */
class McpTranscriptTest {

    private val labels = object : McpTranscript.Labels {
        override fun hidden(length: Int) = "<$length hidden>"
        override fun omitted(count: Int) = "<$count more>"
        override val nothingSent = "<nothing sent>"
    }

    private fun exchange(
        status: String? = "HTTP/1.1 401 Unauthorized",
        responseBody: McpExchange.Body? = McpExchange.Body("""{"error":"invalid_token"}"""),
        failure: String? = null,
    ) = McpExchange(
        method = "POST",
        url = "https://mcp.example.test/v2/mcp",
        requestHeaders = listOf(
            McpExchange.Header("Authorization", McpExchange.Value.Hidden("Bearer", 40)),
            McpExchange.Header("Content-Type", McpExchange.Value.Shown("application/json")),
        ),
        requestBody = McpExchange.Body("""{"jsonrpc":"2.0","method":"initialize"}"""),
        status = status,
        responseHeaders = status?.let {
            listOf(McpExchange.Header("WWW-Authenticate", McpExchange.Value.Shown("Bearer realm=\"mcp\"")))
        }.orEmpty(),
        responseBody = responseBody,
        failure = failure,
    )

    @Test
    fun givenARefusedToken_whenRendered_thenBothDirectionsAppearInWireOrder() {
        val text = McpTranscript.render(listOf(exchange()), McpHttpException(401, ""), labels)

        val expected = """
            > POST https://mcp.example.test/v2/mcp
            > Authorization: Bearer <40 hidden>
            > Content-Type: application/json
            >
            > {"jsonrpc":"2.0","method":"initialize"}
            < HTTP/1.1 401 Unauthorized
            < WWW-Authenticate: Bearer realm="mcp"
            <
            < {"error":"invalid_token"}
        """.trimIndent()
        assertEquals(expected, text)
    }

    @Test
    fun givenATimeoutBeforeAnyAnswer_whenRendered_thenTheFailureEndsTheExchangeOnce() {
        val error = SocketTimeoutException("timeout")
        val failed = exchange(status = null, responseBody = null, failure = "java.net.SocketTimeoutException: timeout")

        val text = McpTranscript.render(listOf(failed), error, labels)

        assertTrue(text.endsWith("* java.net.SocketTimeoutException: timeout"))
        assertEquals(1, Regex("""^\* """, RegexOption.MULTILINE).findAll(text).count())
        assertFalse(text.contains("< "))
    }

    @Test
    fun givenAReplyThatWasNotJsonRpc_whenRendered_thenTheSessionsErrorFollowsTheExchange() {
        val ok = exchange(status = "HTTP/1.1 200 OK", responseBody = McpExchange.Body("<html>"))
        val error = IOException("The server's reply to 'initialize' was not JSON-RPC")

        val text = McpTranscript.render(listOf(ok), error, labels)

        assertTrue(text.endsWith("\n\n* java.io.IOException: The server's reply to 'initialize' was not JSON-RPC"))
    }

    @Test
    fun givenAFailureBeforeTheNetwork_whenRendered_thenItSaysNothingWasSent() {
        val text = McpTranscript.render(emptyList(), IOException("no token"), labels)

        assertEquals("<nothing sent>\n\n* java.io.IOException: no token", text)
    }

    @Test
    fun givenACappedBody_whenRendered_thenTheOmittedCountIsNoted() {
        val capped = exchange(responseBody = McpExchange.Body("abc", omitted = 12))

        val text = McpTranscript.render(listOf(capped), McpHttpException(401, ""), labels)

        assertTrue(text.endsWith("< abc\n< <12 more>"))
    }

    @Test
    fun givenAnHttpRefusal_whenLogged_thenNoSeparateErrorLineRepeatsTheStatus() {
        val log = McpTranscript.log(listOf(exchange()), McpHttpException(401, ""), labels)

        assertEquals(null, log.unexplained)
        assertEquals(McpTranscript.render(listOf(exchange()), McpHttpException(401, ""), labels), log.text)
    }

    @Test
    fun givenAFailureNoExchangeExplains_whenLogged_thenItGetsItsOwnLine() {
        val error = IOException("stream closed")
        val log = McpTranscript.log(listOf(exchange(status = "HTTP/1.1 200 OK")), error, labels)

        assertEquals("java.io.IOException: stream closed", log.unexplained)
    }
}
