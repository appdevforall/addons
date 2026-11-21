package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * The exchange listener feeds the connection log, a convenience: one that fails with an Exception
 * costs only its record, and the request's reply still reaches the caller. An OutOfMemoryError
 * from it is the process failing, not the listener, so it reaches the IDE's uncaught-exception
 * handler rather than being swallowed with the record.
 */
class McpHttpClientListenerFailureTest {

    private val reply = """{"jsonrpc":"2.0","id":1,"result":{}}"""
    private lateinit var server: HttpServer

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                val bytes = reply.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun postWith(listener: McpExchangeListener) = McpHttpClient().post(
        "http://127.0.0.1:${server.address.port}/mcp",
        "",
        JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"),
        exchangeListener = listener,
    )

    @Test
    fun `a listener that throws an Exception costs only its record`() {
        val response = postWith { _, _ -> throw IllegalStateException("the log is full") }

        assertEquals(reply, response.document)
    }

    @Test
    fun `an OutOfMemoryError from the listener reaches the caller`() {
        assertThrows(OutOfMemoryError::class.java) {
            postWith { _, _ -> throw OutOfMemoryError("recording an exchange") }
        }
    }
}
