package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeLog
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpRedactor
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session id the server assigns in its reply to `initialize` is masked in the connection log
 * wherever that same reply's body echoes it.
 *
 * The log's redactor is built from what the request carried, and `initialize` carries no session
 * id: the server assigns one in its answer. The `Mcp-Session-Id` header itself was hidden by name,
 * but a body repeating the id showed it. The redactor for that answer now masks it too.
 */
class AssignedSessionRedactionTest {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/mcp") { exchange ->
            val request = JSONObject(exchange.requestBody.use { String(it.readBytes()) })
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                val result = JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put("serverInfo", JSONObject().put("name", "echoing"))
                    .put("instructions", "Your session is $SESSION_ID.")
                val bytes = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                    .put("result", result).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Mcp-Session-Id", SESSION_ID)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `a session id the server assigns is masked where the same reply's body echoes it`() {
        val session = McpSession(
            "http://127.0.0.1:${server.address.port}/mcp",
            { McpCredentials("", emptyMap()) },
        )
        val log = McpExchangeLog()

        session.recording(log) { session.initialize() }

        val reply = requireNotNull(log.snapshot().first().responseBody).text
        assertFalse("the connection log shows the assigned session id: $reply", reply.contains(SESSION_ID))
        assertTrue("the echo was not masked: $reply", reply.contains("Your session is ${McpRedactor.MASK}."))
    }

    private companion object {
        const val SESSION_ID = "sess-7d41c9e2a8b3f650"
    }
}
