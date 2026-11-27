package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import com.sun.net.httpserver.HttpServer
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cancel that lands just after a request published its connection, after [McpCallHandle.attach]
 * read the flag and before the connection is connected, sends nothing.
 *
 * Such a cancel finds the connection and disconnects it, but a connection that is not connected
 * yet has nothing to drop, on Android and on the JDK alike; the request's first write then
 * connected and sent it. The request now reads the flag again once its connection is up.
 */
class CancelAfterAttachTest {

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
                    else -> JSONObject().put("content", org.json.JSONArray())
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
    fun `a cancel that lands just after the request attached sends nothing`() {
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
            ): Response = super.post(
                url,
                token,
                body,
                sessionId,
                protocolVersion,
                extraHeaders,
                onConnected = { conn ->
                    onConnected(conn)
                    // Here: attach has read the flag, and the connection is not connected yet.
                    if (body.optString("method") == "tools/call") handle.cancel()
                },
                beforeSend = beforeSend,
                exchangeListener = exchangeListener,
            )
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
