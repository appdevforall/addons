package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.testing.awaitFinishedOrBlockedBy
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpServerHealth
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A deactivation ends its sessions after it has let go of the lifecycle lock, so an activation
 * arriving meanwhile does not wait for them.
 *
 * Each session's close sends a DELETE, and can first wait, up to about 70 s, for a handshake in
 * flight to let go of the session. The teardown used to close the sessions under the lock that
 * serialises the lifecycle calls, so an activate, or AI Core's registration of the tool source,
 * waited behind every DELETE. The teardown now only takes the sessions out under it.
 *
 * The server holds the DELETE on a latch; the activation runs on another thread, and the test
 * waits until it has finished or is blocked on a lock the deactivating thread holds. Nothing
 * sleeps.
 */
class LifecycleSessionCloseTest {

    private val deleteArrived = CountDownLatch(1)
    private val releaseDelete = CountDownLatch(1)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        // Its own threads, so the held DELETE does not hold the next activation's listing.
        executor = Executors.newCachedThreadPool()
        createContext("/mcp") { exchange ->
            if (exchange.requestMethod == "DELETE") {
                deleteArrived.countDown()
                releaseDelete.await(10, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
                return@createContext
            }
            val request = JSONObject(exchange.requestBody.use { String(it.readBytes()) })
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                val result = when (request.optString("method")) {
                    "initialize" -> JSONObject().put("protocolVersion", "2025-06-18")
                    else -> JSONObject().put("tools", JSONArray())
                }
                val bytes = JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                    .put("result", result).toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Mcp-Session-Id", "session-1")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        every { context.getPluginService(any(), ToolSourceRegistry::class.java) } returns null
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        releaseDelete.countDown()
        plugin.dispose()
        McpConnections.closeAll()
        McpToolCatalog.clear()
        server.stop(0)
        unmockkObject(McpServerStore)
    }

    @Test
    fun `an activate arriving while a deactivation ends its sessions does not wait for them`() {
        val saved = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "http://127.0.0.1:${server.address.port}/mcp")
        )
        plugin.activate()
        // A session with a server-side id, so ending it sends the DELETE the server holds.
        McpConnections.session(saved).initialize()

        val deactivating = thread(name = "deactivating") { plugin.deactivate() }
        assertTrue("the deactivation never sent its DELETE", deleteArrived.await(10, TimeUnit.SECONDS))
        val activating = thread(name = "activating") { plugin.activate() }
        val state = awaitFinishedOrBlockedBy(activating, deactivating)
        releaseDelete.countDown()
        activating.join(10_000)
        deactivating.join(10_000)

        assertEquals("activate waited for the deactivation's DELETE", Thread.State.TERMINATED, state)
        assertFalse("deactivate never finished", deactivating.isAlive)
    }

    /**
     * An activate that runs after a deactivation has taken its sessions out, and before that
     * deactivation has ended them, leaves them to it: it neither ends them nor waits for them.
     *
     * The sessions taken out were kept in one list that every lifecycle call emptied once it had
     * let go of the lock. An activate taking the lock in that window emptied it, ended the
     * deactivation's sessions itself, and waited for their DELETEs, up to about 70 s each. Each
     * lifecycle call now ends only the sessions its own teardown took out.
     *
     * A health listener holds the deactivation at its teardown's last steps, after the sessions are
     * taken out, in a wait on the lifecycle lock, which lets that lock go: the window, opened for as
     * long as the test needs. The lock is internal for this test. Nothing sleeps.
     */
    @Test
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // wait and notifyAll on the lock's own monitor
    fun `an activate between a deactivation's teardown and its DELETEs leaves the sessions to it`() {
        val saved = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "http://127.0.0.1:${server.address.port}/mcp")
        )
        plugin.activate()
        McpConnections.session(saved).initialize()
        // A health row, so the teardown's clearing of the cache tells the health listeners.
        McpServerHealth.available(saved.id)
        val lock = plugin.lifecycleLock as java.lang.Object
        val held = CountDownLatch(1)
        var resume = false
        val hold = {
            if (Thread.currentThread().name == "deactivating" && held.count > 0) {
                synchronized(lock) {
                    held.countDown()
                    while (!resume) lock.wait()
                }
            }
        }
        McpServerHealth.addChangeListener(hold)
        try {
            val deactivating = thread(name = "deactivating") { plugin.deactivate() }
            assertTrue("the deactivation's teardown never took its sessions out", held.await(10, TimeUnit.SECONDS))
            val activating = thread(name = "activating") { plugin.activate() }
            // Whichever comes first: the activate ending, or a DELETE sent while the deactivation is held.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (activating.isAlive && deleteArrived.count > 0 && System.nanoTime() < deadline) activating.join(10)
            val endedByActivate = deleteArrived.count == 0L
            val activateEnded = !activating.isAlive
            synchronized(lock) {
                resume = true
                lock.notifyAll()
            }
            val endedByDeactivate = deleteArrived.await(10, TimeUnit.SECONDS)
            releaseDelete.countDown()
            activating.join(10_000)
            deactivating.join(10_000)

            assertFalse("activate ended the sessions the deactivation took out", endedByActivate)
            assertTrue("activate did not end", activateEnded)
            assertTrue("the deactivation never ended its own session", endedByDeactivate)
            assertFalse("deactivate never finished", deactivating.isAlive)
        } finally {
            McpServerHealth.removeChangeListener(hold)
        }
    }
}
