package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.Arrays
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body of a `202 Accepted`, which should be empty, is read to its end so its socket can be
 * reused, through a fixed buffer: a server answering a notification with a huge body must not cost
 * Code on the Go its memory, and an OutOfMemoryError while reading one is the process failing, not
 * the read, so it reaches the IDE's uncaught-exception handler. A body that runs past the drain's
 * budget is abandoned and its socket disconnected, so one that never ends cannot hold the handshake.
 */
class McpHttpClientDrainTest {

    /** [size] bytes of `a`, made as they are read; records how far it was read and whether it was closed. */
    private class GeneratedBody(private val size: Long) : InputStream() {
        var position = 0L
        var closed = false

        override fun read(): Int = if (position < size) 'a'.code.also { position++ } else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (position >= size) return -1
            val n = minOf(len.toLong(), size - position).toInt()
            Arrays.fill(b, off, off + n, 'a'.code.toByte())
            position += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a large body within the budget is read to its end but only its start is kept`() {
        val size = McpHttpClient.DRAIN_BUDGET_CHARS / 2
        val body = GeneratedBody(size)

        val drained = McpHttpClient.drain(keep = 100) { body }.getOrThrow()

        assertEquals("characters kept", 100, drained.text.length)
        assertEquals("characters dropped", size - 100, drained.dropped)
        assertEquals("the body was not read to its end", size, body.position)
        assertTrue("the body was not closed", body.closed)
    }

    @Test
    fun `a body past the budget is abandoned without being read to its end`() {
        val keep = 100
        val budget = 64L * 1024
        val body = GeneratedBody(64L * 1024 * 1024)

        val drained = McpHttpClient.drain(keep = keep, budget = budget) { body }

        assertTrue(
            "a body past the budget was read to its end: ${body.position} bytes",
            drained.exceptionOrNull() is McpHttpClient.DrainBudgetException,
        )
        // The reader buffers ahead of what the drain has counted, by at most a buffer or two.
        assertTrue("read ${body.position} bytes", body.position <= keep + budget + 64 * 1024)
        assertTrue("the body was not closed", body.closed)
    }

    @Test
    fun `an OutOfMemoryError while draining reaches the caller`() {
        val body = object : InputStream() {
            override fun read(): Int = throw OutOfMemoryError("reading a 202 body")
        }

        assertThrows(OutOfMemoryError::class.java) { McpHttpClient.drain { body } }
    }

    @Test
    fun `a read that fails with an IOException is a failure the caller survives`() {
        val body = object : InputStream() {
            override fun read(): Int = throw IOException("connection reset")
        }

        assertTrue(McpHttpClient.drain { body }.isFailure)
    }

    /**
     * A 202 whose body never ends: the POST returns once the drain's budget is spent, and the next
     * request arrives on a new connection, so the abandoned socket was disconnected rather than
     * pooled. Timed out rather than left to hang, should the drain read forever.
     */
    @Test(timeout = 30_000)
    fun `a 202 whose body never ends returns within the budget and its socket is not reused`() {
        val ports = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                ports += exchange.remoteAddress.port
                val request = JSONObject(exchange.requestBody.use { String(it.readBytes()) })
                if (request.optString("method") == "notifications/initialized") {
                    // Chunked, with no end: written until the client goes away.
                    exchange.sendResponseHeaders(202, 0)
                    val chunk = ByteArray(8 * 1024) { 'a'.code.toByte() }
                    runCatching { exchange.responseBody.use { out -> while (true) out.write(chunk) } }
                } else {
                    val bytes = JSONObject().put("jsonrpc", "2.0").put("id", 1)
                        .put("result", JSONObject()).toString().toByteArray()
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/mcp"
            val client = McpHttpClient()

            val accepted = client.post(
                url,
                "",
                JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"),
            )
            client.post(url, "", JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"))

            assertNull(accepted.document)
            assertEquals("requests seen", 2, ports.size)
            assertNotEquals("the abandoned socket was reused", ports[0], ports[1])
        } finally {
            server.stop(0)
        }
    }

    /** The control for the test above: a 202 with no body leaves its socket for the next request. */
    @Test
    fun `a 202 with no body leaves its socket for the next request`() {
        val ports = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                ports += exchange.remoteAddress.port
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/mcp"
            val client = McpHttpClient()
            val notification = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")

            client.post(url, "", notification)
            client.post(url, "", notification)

            assertEquals("requests seen", 2, ports.size)
            assertEquals("a drained 202 did not leave its socket for reuse", ports[0], ports[1])
        } finally {
            server.stop(0)
        }
    }

    /** The drain as [McpHttpClient.post] uses it: the record shows the start and counts the rest. */
    @Test
    fun `a 202 with a large body is recorded capped with the rest counted`() {
        val size = 100 * 1024
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(202, size.toLong())
                exchange.responseBody.use { it.write(ByteArray(size) { 'a'.code.toByte() }) }
            }
            start()
        }
        try {
            var recorded: McpExchange? = null
            val response = McpHttpClient().post(
                "http://127.0.0.1:${server.address.port}/mcp",
                "",
                JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"),
                exchangeListener = { exchange, _ -> recorded = exchange },
            )

            assertNull(response.document)
            val body = requireNotNull(recorded?.responseBody)
            assertEquals(McpExchange.Body.MAX_CHARS, body.text.length)
            assertEquals(size - McpExchange.Body.MAX_CHARS, body.omitted)
        } finally {
            server.stop(0)
        }
    }
}
