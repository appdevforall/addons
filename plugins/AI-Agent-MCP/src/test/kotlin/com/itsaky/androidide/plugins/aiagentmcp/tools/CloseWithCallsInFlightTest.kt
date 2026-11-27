package com.itsaky.androidide.plugins.aiagentmcp.tools

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Closing the tool source ends every call it accepted, running or queued.
 *
 * `close()` shut the executor down and cleared its maps. A call queued behind a running one was
 * never started, so its future never completed and the host waited on it; the running call's
 * worker stayed blocked in a socket read until the server answered or the read timed out.
 *
 * One worker runs the held call, so the second call is queued behind it. The server answers the
 * held call only when [release] is counted down, which happens after the assertions: both futures
 * must be done by then. Nothing sleeps.
 */
class CloseWithCallsInFlightTest {

    private val pool = Executors.newSingleThreadExecutor()
    private val source = McpToolSource(pool)

    /** Counted down when the held `tools/call` reaches the server. */
    private val arrived = CountDownLatch(1)
    private val release = CountDownLatch(1)

    private val handlers = Executors.newCachedThreadPool()
    private lateinit var http: HttpServer
    private lateinit var plugin: McpPlugin
    private lateinit var docs: McpServer
    private lateinit var name: String

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = handlers
            createContext("/mcp") { exchange -> answer(exchange) }
            start()
        }
        docs = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "http://127.0.0.1:${http.address.port}/mcp")
        )
        McpToolCatalog.connect(docs)
        McpServerStore.setToolEnabled(docs.id, "search", true)
        name = source.listTools().single().name
    }

    @After
    fun tearDown() {
        release.countDown()
        source.close()
        pool.shutdownNow()
        McpConnections.closeAll()
        McpToolCatalog.clear()
        http.stop(0)
        handlers.shutdownNow()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    private fun answer(exchange: HttpExchange) {
        if (exchange.requestMethod == "DELETE") {
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
            return
        }
        val request = JSONObject(exchange.requestBody.use { String(it.readBytes()) })
        val id = request.opt("id")
        if (id == null) {
            exchange.sendResponseHeaders(202, -1)
            exchange.close()
            return
        }
        val result = when (request.getString("method")) {
            "initialize" -> JSONObject().put("protocolVersion", "2025-03-26")
            "tools/list" -> JSONObject().put("tools", JSONArray(listOf(tool("search", true))))
            "tools/call" -> {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS)
                JSONObject().put(
                    "content",
                    JSONArray().put(JSONObject().put("type", "text").put("text", "ok")),
                )
            }
            else -> JSONObject()
        }
        val bytes = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
            .toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        // The client is dropped by close(); the write failing then is expected.
        runCatching {
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        exchange.close()
    }

    private fun invoke(id: String): CompletableFuture<ToolSourceRegistry.ToolOutcome> =
        source.invoke(
            mockk<ToolSourceRegistry.ToolInvocation> {
                every { toolName } returns name
                every { arguments } returns mapOf("who" to id)
                every { callId } returns id
            }
        )

    @Test
    fun `close completes a running call and a queued one before the server answers`() {
        val running = invoke("running")
        assertTrue("the call never reached the server", arrived.await(10, TimeUnit.SECONDS))
        val queued = invoke("queued")

        source.close()

        assertTrue("the running call's future is still pending after close", running.isDone)
        assertTrue("the queued call's future is still pending after close", queued.isDone)
        assertEquals("a call was left registered", 0, source.pendingCalls())
    }
}
