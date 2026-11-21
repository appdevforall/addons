package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import com.sun.net.httpserver.HttpServer
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cancel that lands once the request has read its flag before the write sends nothing.
 *
 * Without a streaming mode the write only fills a buffer: the request goes out when the answer is
 * first asked for. A cancel landing between the flag's read and that point disconnected the
 * connection, and the JDK connected again at the write and sent the request when it was answered.
 * The request now reads the flag once more after the write, before it asks for the answer.
 *
 * The cancel is made from inside that first read, just after it passed: the write, and the point
 * where the request would go out, both come after it. The server counts the request bodies it gets.
 */
class CancelAfterCheckTest {

    /** The JSON-RPC method of every request body the server received, in order. */
    private val received = CopyOnWriteArrayList<String>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/mcp") { exchange ->
            val request = JSONObject(exchange.requestBody.use { String(it.readBytes()) })
            received += request.optString("method")
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                val result = when (request.optString("method")) {
                    "initialize" -> JSONObject().put("protocolVersion", "2025-06-18")
                    else -> JSONObject().put("content", JSONArray())
                }
                val bytes = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                    .put("result", result).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
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
    fun `a cancel that lands after the check before the write sends nothing`() {
        val handle = McpCallHandle()
        val http = object : McpHttpClient() {
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
            ): Response {
                var checks = 0
                return super.post(
                    url,
                    token,
                    body,
                    sessionId,
                    protocolVersion,
                    extraHeaders,
                    onConnected,
                    beforeSend = {
                        beforeSend()
                        // Here: the connection is up and the flag was read; the write comes next.
                        if (body.optString("method") == "tools/call" && checks++ == 0) handle.cancel()
                    },
                    exchangeListener = exchangeListener,
                )
            }
        }
        val session = McpSession(
            "http://127.0.0.1:${server.address.port}/mcp",
            { McpCredentials("", emptyMap()) },
            http,
        )

        val failure = runCatching { session.callTool("echo", emptyMap(), handle) }.exceptionOrNull()

        assertEquals(
            "the cancelled call's request reached the server",
            listOf("initialize", "notifications/initialized"),
            received.toList(),
        )
        assertTrue("the call did not end cancelled: $failure", failure is InterruptedIOException)
    }
}
