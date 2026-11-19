package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.Arrays
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The body of a `202 Accepted`, which should be empty, is read to its end so its socket can be
 * reused, through a fixed buffer: a server answering a notification with a huge body must not cost
 * Code on the Go its memory, and an OutOfMemoryError while reading one is the process failing, not
 * the read, so it reaches the IDE's uncaught-exception handler.
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
    fun `a large body is read to its end but only its start is kept`() {
        val size = 10L * 1024 * 1024
        val body = GeneratedBody(size)

        val drained = McpHttpClient.drain(keep = 100) { body }.getOrThrow()

        assertEquals("characters kept", 100, drained.text.length)
        assertEquals("characters dropped", size - 100, drained.dropped)
        assertEquals("the body was not read to its end", size, body.position)
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
