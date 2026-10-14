package com.itsaky.androidide.plugins.aiagentmcp.transport

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** [McpHttpClientCleartextTest]'s other half: `http://` on this device connects, credentials and all. */
class McpHttpClientLoopbackTest {

    private val reply = """{"jsonrpc":"2.0","id":1,"result":{}}"""
    private val received = mutableListOf<Pair<String, Headers>>()
    private lateinit var server: HttpServer

    @Before
    fun startServer() {
        // The wildcard address, so one server answers 127.0.0.1, localhost and [::1] alike.
        server = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/mcp") { exchange ->
                synchronized(received) { received += exchange.requestMethod to exchange.requestHeaders }
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

    private fun urlOn(host: String) = "http://$host:${server.address.port}/mcp"

    private fun assertPostCarriesCredentials(host: String) {
        val response = McpHttpClient().post(
            urlOn(host),
            "loopback-token",
            JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "ping"),
            extraHeaders = mapOf("X-Api-Key" to "loopback-key"),
        )

        assertEquals(reply, response.document)
        val (method, headers) = received.single()
        assertEquals("POST", method)
        assertEquals("Bearer loopback-token", headers.getFirst("Authorization"))
        assertEquals("loopback-key", headers.getFirst("X-Api-Key"))
    }

    @Test
    fun givenAnIpv4LoopbackUrlAndAToken_whenPosted_thenTheRequestArrivesWithItsCredentials() {
        assertPostCarriesCredentials("127.0.0.1")
    }

    @Test
    fun givenALocalhostUrlAndAToken_whenPosted_thenTheRequestArrivesWithItsCredentials() {
        assertPostCarriesCredentials("localhost")
    }

    @Test
    fun givenAnIpv6LoopbackUrlAndAToken_whenPosted_thenTheRequestArrivesWithItsCredentials() {
        val hasIpv6Loopback = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isLoopback && it.isUp }
            .flatMap { it.inetAddresses.asSequence() }
            .any { it is Inet6Address }
        assumeTrue("No IPv6 loopback on this machine", hasIpv6Loopback)
        assertPostCarriesCredentials("[::1]")
    }

    @Test
    fun givenALoopbackUrlAndAToken_whenItsSessionIsDeleted_thenTheRequestArrivesWithItsCredentials() {
        McpHttpClient().deleteSession(
            urlOn("127.0.0.1"),
            "loopback-token",
            "session-1",
            extraHeaders = mapOf("X-Api-Key" to "loopback-key"),
        )

        val (method, headers) = received.single()
        assertEquals("DELETE", method)
        assertEquals("Bearer loopback-token", headers.getFirst("Authorization"))
        assertEquals("loopback-key", headers.getFirst("X-Api-Key"))
        assertEquals("session-1", headers.getFirst(McpHttpClient.HEADER_SESSION_ID))
    }
}
