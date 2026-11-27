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
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cancelling one tool call stops that call and nothing else on the same server.
 *
 * Every call to a server, the health probe's listing and a SQLite debugger entry's relisting share
 * one session. The session used to hold one connection for a cancel to drop: whichever request had
 * published last. Cancelling call A while call B, or the probe, was in flight dropped B's or the
 * probe's socket instead (the probe then marked the server degraded), and A's own request went out
 * and ran to its timeout. Each call now publishes its requests to its own handle.
 *
 * A is cancelled while its `tools/call` request reads its credentials, the point at which it has
 * no connection of its own yet and the other request's is the session's latest. Every wait ends on
 * a latch or a worker finishing; nothing sleeps.
 */
class SameServerCancelTest {

    private val pool = Executors.newFixedThreadPool(2)

    /** Ended latches, one per worker in the order the calls were invoked. */
    private val workerEnded = List(2) { CountDownLatch(1) }
    private val scheduled = AtomicInteger(0)
    private val executor = object : ExecutorService by pool {
        override fun execute(command: Runnable) {
            val ended = workerEnded[scheduled.getAndIncrement()]
            pool.execute {
                try {
                    command.run()
                } finally {
                    ended.countDown()
                }
            }
        }
    }
    private val source = McpToolSource(executor)

    private val armed = AtomicBoolean(false)
    private val reads = AtomicInteger(0)

    /** Which calls' `tools/call` reached the server, by their `who` argument. */
    private val calls = CopyOnWriteArrayList<String>()

    /** Held: B's `tools/call` stays in flight until [release]. */
    private val bArrived = CountDownLatch(1)

    /** Held when set: the next `tools/list` stays in flight until [release]. */
    private val holdListing = AtomicBoolean(false)
    private val listingArrived = CountDownLatch(1)
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
        // Once armed, A reads the token twice: for its session lookup, then for its tools/call
        // request's credentials, which is where A is cancelled.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } answers {
            if (armed.get() && reads.incrementAndGet() == CREDENTIALS_READ) {
                armed.set(false)
                source.cancel(CALL_A)
            }
            KeystoreSecretStore.Stored.Absent
        }
        every { McpServerStore.headers(any()) } returns emptyMap()
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            // Concurrent, so a held request does not hold the others behind it.
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

    /** The MCP methods this test needs, holding B's call and, when asked, one listing. */
    private fun answer(exchange: com.sun.net.httpserver.HttpExchange) {
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
            "tools/list" -> {
                if (holdListing.getAndSet(false)) {
                    listingArrived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                JSONObject().put("tools", JSONArray(listOf(tool("search", true))))
            }
            "tools/call" -> {
                val who = request.getJSONObject("params").getJSONObject("arguments").optString("who")
                calls += who
                if (who == CALL_B) {
                    bArrived.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
                JSONObject().put(
                    "content",
                    JSONArray().put(JSONObject().put("type", "text").put("text", "ok $who")),
                )
            }
            else -> JSONObject()
        }
        val bytes = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
            .toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        // A dropped client is what the old code did to B; the write failing then is expected.
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
    fun `cancelling a call leaves another call to the same server alone and sends nothing`() {
        val b = invoke(CALL_B)
        assertTrue("B never reached the server", bArrived.await(10, TimeUnit.SECONDS))

        armed.set(true)
        invoke(CALL_A)
        assertTrue("A's worker never finished", workerEnded[1].await(10, TimeUnit.SECONDS))
        release.countDown()
        val outcome = b.get(10, TimeUnit.SECONDS)

        assertFalse("A was never cancelled", armed.get())
        assertTrue("B was never cancelled but failed: ${outcome.errorMessage}", outcome.isSuccess)
        assertEquals("A was cancelled but its request went out", listOf(CALL_B), calls.toList())
    }

    @Test
    fun `cancelling a call leaves the probe's listing of the same server alone`() {
        holdListing.set(true)
        // The probe's own call, on a thread of its own as the probe's coroutine would be.
        var listing: Result<McpToolCatalog.Listing>? = null
        val probe = Thread { listing = runCatching { McpToolCatalog.connect(docs) } }.apply { start() }
        assertTrue("the listing never reached the server", listingArrived.await(10, TimeUnit.SECONDS))

        armed.set(true)
        invoke(CALL_A)
        assertTrue("A's worker never finished", workerEnded[0].await(10, TimeUnit.SECONDS))
        release.countDown()
        probe.join(10_000)

        assertFalse("A was never cancelled", armed.get())
        val result = requireNotNull(listing) { "the listing never finished" }
        assertTrue(
            "the listing was never cancelled but failed: ${result.exceptionOrNull()}",
            result.isSuccess,
        )
        assertNotEquals(
            "a cancelled call marked the server degraded",
            McpServerHealth.State.DEGRADED,
            McpServerHealth.of(docs.id)?.state,
        )
        assertEquals("A was cancelled but its request went out", emptyList<String>(), calls.toList())
    }

    private companion object {
        const val CALL_A = "a"
        const val CALL_B = "b"

        /** The session lookup reads the token first; the `tools/call` request's read is second. */
        const val CREDENTIALS_READ = 2
    }
}
