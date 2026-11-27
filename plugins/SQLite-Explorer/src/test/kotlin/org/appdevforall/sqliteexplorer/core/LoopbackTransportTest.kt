package org.appdevforall.sqliteexplorer.core

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

class LoopbackTransportTest {
    private var server: HttpServer? = null

    @After
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `posts JSON to 127_0_0_1 slash mcp with the headers given, and reads the session header`() {
        var seen: Triple<String, String?, String>? = null
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { server = it }
        http.createContext("/") { exchange ->
            seen = Triple(exchange.requestURI.path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestBody.readBytes().decodeToString())
            exchange.responseHeaders.add("Mcp-Session-Id", "abc")
            val reply = """{"ok":true}""".toByteArray()
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        http.start()

        val reply = LoopbackTransport().post(http.address.port, mapOf("Authorization" to "Bearer t"), """{"x":1}""")

        assertEquals(200, reply.status)
        assertEquals("abc", reply.sessionId)
        assertEquals("""{"ok":true}""", reply.body)
        assertEquals(Triple("/mcp", "Bearer t", """{"x":1}"""), seen)
    }

    @Test
    fun `an error status keeps its body`() {
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { server = it }
        http.createContext("/") { exchange ->
            val reply = "Forbidden host".toByteArray()
            exchange.sendResponseHeaders(403, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        http.start()

        val reply = LoopbackTransport().post(http.address.port, emptyMap(), "{}")

        assertEquals(403, reply.status)
        assertEquals("Forbidden host", reply.body)
    }

    @Test
    fun `a port nothing listens on is unreachable`() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        assertThrows(UnreachableException::class.java) { LoopbackTransport(connectTimeoutMs = 2_000).post(port, emptyMap(), "{}") }
    }
}
