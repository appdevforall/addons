package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpProtocolException
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EndpointRetryTest {

    private val app = "com.example.app"
    private var stored = listOf(AutomaticEndpoint(app, 1111, "old"))
    private val retry = EndpointRetry(
        refresh = AutomaticServers::refresh,
        current = { id -> AutomaticServers.servers().firstOrNull { it.id == id } },
        token = AutomaticServers::token,
    )

    init {
        AutomaticServers.install { stored }
    }

    @After
    fun reset() = AutomaticServers.install(null)

    private fun server() = AutomaticServers.servers().single()

    @Test
    fun `a refused first connect uses the refreshed endpoint on retry`() {
        val urls = mutableListOf<String>()
        val first = server()
        stored = listOf(AutomaticEndpoint(app, 2222, "new"))

        val result = retry.call(first) { s ->
            urls += s.url
            if (s.url.endsWith(":1111/mcp")) throw ConnectException("refused")
            "ok:" + AutomaticServers.token(s.id)
        }

        assertEquals("ok:new", result)
        assertEquals(listOf("http://127.0.0.1:1111/mcp", "http://127.0.0.1:2222/mcp"), urls)
    }

    @Test
    fun `401 and 403 also refresh`() {
        for (failure in listOf<Exception>(McpHttpException(401, ""), McpHttpException(403, ""))) {
            stored = listOf(AutomaticEndpoint(app, 1111, "old"))
            AutomaticServers.install { stored }
            val first = server()
            stored = listOf(AutomaticEndpoint(app, 3333, "n"))
            var calls = 0
            val out = retry.call(first) { s -> calls++; if (calls == 1) throw failure else s.url }
            assertEquals("http://127.0.0.1:3333/mcp", out)
        }
    }

    @Test
    fun `a timeout is not retried, so a slow call is not run twice`() {
        val first = server()
        stored = listOf(AutomaticEndpoint(app, 3333, "n"))
        var calls = 0
        assertThrows(SocketTimeoutException::class.java) {
            retry.call(first) { calls++; throw SocketTimeoutException("read timed out") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a credential a concurrent refresh already replaced is still retried`() {
        val first = server()
        var calls = 0
        val out = retry.call(first) { s ->
            calls++
            if (calls == 1) {
                // The probe's refresh lands while this call is in flight, so the retry's own
                // refresh sees nothing new.
                stored = listOf(AutomaticEndpoint(app, 1111, "rotated"))
                AutomaticServers.refresh()
                throw McpHttpException(401, "")
            }
            "retried:" + AutomaticServers.token(s.id)
        }
        assertEquals("retried:rotated", out)
        assertEquals(2, calls)
    }

    @Test
    fun `another app's change does not cause a retry`() {
        stored = listOf(AutomaticEndpoint(app, 1111, "old"), AutomaticEndpoint("com.example.other", 9000, "o1"))
        AutomaticServers.install { stored }
        val mine = AutomaticServers.servers().first { it.id == AutomaticServers.idFor(app) }
        stored = listOf(AutomaticEndpoint(app, 1111, "old"), AutomaticEndpoint("com.example.other", 9001, "o2"))
        var calls = 0
        assertThrows(ConnectException::class.java) {
            retry.call(mine) { calls++; throw ConnectException("refused") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `an unchanged endpoint is not asked twice`() {
        var calls = 0
        assertThrows(ConnectException::class.java) {
            retry.call(server()) { calls++; throw ConnectException("refused") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `an app that is gone surfaces the original failure`() {
        val first = server()
        stored = emptyList()
        assertThrows(ConnectException::class.java) {
            retry.call(first) { throw ConnectException("refused") }
        }
    }

    @Test
    fun `other failures are not retried`() {
        stored = listOf(AutomaticEndpoint(app, 4444, "x"))
        var calls = 0
        assertThrows(McpProtocolException::class.java) {
            retry.call(server()) { calls++; throw McpProtocolException(-32000, "bad") }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `a hand-added server is never retried through the endpoint source`() {
        val handAdded = McpServer("h", "mine", "http://127.0.0.1:1111/mcp")
        stored = listOf(AutomaticEndpoint(app, 5555, "x"))
        var calls = 0
        assertThrows(ConnectException::class.java) {
            retry.call(handAdded) { calls++; throw ConnectException("refused") }
        }
        assertEquals(1, calls)
    }
}
