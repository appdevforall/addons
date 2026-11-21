package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import java.net.HttpURLConnection
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Ending the sessions a lifecycle call took out ends every one of them, even when ending one
 * throws an Error, and then throws that Error.
 *
 * A session's close survives an Exception or a LinkageError and lets any other Error through. The
 * loop over the sessions stopped at the first, and every session after it was never ended, so its
 * server kept it open. Each is now ended, and the first Error is thrown after the last.
 */
class EndAllSessionsTest {

    private val ended = CopyOnWriteArrayList<String>()

    /** A session with a server-side id, whose DELETE throws [failure], or is recorded when null. */
    private fun session(url: String, failure: Error? = null) = McpSession(
        url,
        { McpCredentials("", emptyMap()) },
        object : McpHttpClient() {
            override fun post(
                url: String,
                token: String,
                body: JSONObject,
                sessionId: String?,
                protocolVersion: String?,
                extraHeaders: Map<String, String>,
                onConnected: (HttpURLConnection) -> Unit,
                beforeSend: () -> Unit,
                exchangeListener: McpExchangeListener,
            ): Response = when (body.optString("method")) {
                "initialize" -> Response(
                    """{"jsonrpc":"2.0","id":${body.get("id")},"result":{"protocolVersion":"2025-03-26"}}""",
                    "session-of-$url",
                )
                else -> Response(null, null)
            }

            override fun deleteSession(url: String, token: String, sessionId: String, extraHeaders: Map<String, String>) {
                failure?.let { throw it }
                ended += url
            }
        },
    ).also { it.initialize() }

    @Test
    fun `an Error ending one session does not stop the others, and the first is thrown after them`() {
        val first = OutOfMemoryError("ending a")
        val second = OutOfMemoryError("ending c")
        val released = listOf(
            session(url("a"), first),
            session(url("b")),
            session(url("c"), second),
            session(url("d")),
        ).map { McpConnections.Released(it, "", null) }

        val thrown = assertThrows(OutOfMemoryError::class.java) { McpConnections.endAll(released) }

        assertEquals("the sessions after a failing one were not ended", listOf(url("b"), url("d")), ended)
        assertSame("not the first Error", first, thrown)
        assertSame("the second Error was lost", second, thrown.suppressed.single())
    }

    private fun url(name: String) = "http://127.0.0.1:1/$name"
}
