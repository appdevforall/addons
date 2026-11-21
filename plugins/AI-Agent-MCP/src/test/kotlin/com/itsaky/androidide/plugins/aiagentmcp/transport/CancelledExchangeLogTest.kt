package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.itsaky.androidide.plugins.aiagentmcp.errors.McpTranscript
import com.sun.net.httpserver.HttpServer
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A request its caller cancels before it is sent is recorded in the connection log as cancelled,
 * not as a request that went out.
 *
 * The record was built the same way whether the request had gone out or not, so the log showed
 * the request's headers and body marked as sent, then "no response". It now says the request was
 * cancelled before it was sent, and shows none of it as sent.
 */
class CancelledExchangeLogTest {

    private val bodies = AtomicInteger(0)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/mcp") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            bodies.incrementAndGet()
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
        }
        start()
    }

    private val labels = object : McpTranscript.Labels {
        override fun hidden(length: Int) = "<$length hidden>"
        override fun omitted(count: Int) = "<$count more>"
        override val nothingSent = "<nothing sent>"
        override val cancelled = "<cancelled>"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `a request cancelled before it is sent is logged as cancelled, not as sent`() {
        val exchanges = CopyOnWriteArrayList<McpExchange>()
        val cancel = InterruptedIOException("The tool call was cancelled before its request was sent")

        assertThrows(InterruptedIOException::class.java) {
            McpHttpClient().post(
                url = "http://127.0.0.1:${server.address.port}/mcp",
                token = "",
                body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call"),
                beforeSend = { throw cancel },
                exchangeListener = { exchange, _ -> exchanges += exchange },
            )
        }
        val transcript = McpTranscript.render(exchanges.toList(), cancel, labels) { it }

        assertEquals("the cancelled request reached the server", 0, bodies.get())
        assertTrue("the record does not say the request was cancelled", exchanges.single().cancelled)
        assertFalse(
            "the transcript shows the cancelled request as sent:\n$transcript",
            transcript.lines().any { it.startsWith(">") },
        )
        assertTrue("the transcript does not say it was cancelled:\n$transcript", "* <cancelled>" in transcript)
    }
}
