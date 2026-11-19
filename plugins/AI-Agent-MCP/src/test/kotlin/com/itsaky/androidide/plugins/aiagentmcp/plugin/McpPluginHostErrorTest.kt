package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpServerHealth
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A host whose plugin-api differs from the one this plugin was built against answers a call with an
 * Error (AbstractMethodError, NoSuchMethodError, another LinkageError), not an Exception. On this
 * plugin's own coroutines such an Error reached the IDE's uncaught-exception handler, which crashes
 * Code on the Go, since a plugin runs in its process.
 */
class McpPluginHostErrorTest {

    private lateinit var context: PluginContext
    private lateinit var plugin: McpPlugin
    private val servers = mutableListOf<FakeMcpServer>()

    @Before
    fun setUp() {
        AutomaticServers.install(null)
        context = mockk(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
    }

    @After
    fun tearDown() {
        SharedServices.unregister(ToolSourceRegistry::class.java)
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
        plugin.dispose()
    }

    private fun fake(token: String, vararg tools: org.json.JSONObject) =
        FakeMcpServer(token, tools.toList()).also { servers += it }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(10)
        }
    }

    /** Runs [block], turning an Error that escapes it into this test's failure. */
    private fun <T> survives(what: String, block: () -> T): T = try {
        block()
    } catch (e: Error) {
        if (e is AssertionError) throw e
        throw AssertionError("$what let $e escape", e)
    }

    @Test
    fun `a debugger service that throws a linkage error does not end the activation`() {
        val service = mockk<DebuggerEndpointService> {
            every { endpoints } throws AbstractMethodError("DebuggerEndpointService.getEndpoints")
        }
        every { context.services.get(DebuggerEndpointService::class.java) } returns service

        survives("startDebuggerEntry") { plugin.startDebuggerEntry { true } }

        assertTrue(AutomaticServers.servers().isEmpty())
    }

    @Test
    fun `a registry that throws a linkage error does not end the health probe`() {
        val active = AtomicBoolean(true)
        val registry = mockk<ToolSourceRegistry>(relaxed = true) {
            every { notifyToolsChanged(any()) } answers {
                active.set(false)
                throw AbstractMethodError("ToolSourceRegistry.notifyToolsChanged")
            }
        }
        SharedServices.register(ToolSourceRegistry::class.java, registry)
        val app = fake("t1", tool("query", true))
        AutomaticServers.install { listOf(AutomaticEndpoint("com.example.app", app.port, "t1")) }
        // A bound, so a probe that never lists the tools fails the verify below instead of hanging.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)

        survives("the health probe") {
            runBlocking {
                plugin.probeHealth({ active.get() && System.nanoTime() < deadline }, intervalMs = 10)
            }
        }

        verify { registry.notifyToolsChanged(McpPlugin.PLUGIN_ID) }
    }

    @Test
    fun `a keystore call that throws a linkage error costs only its own server`() {
        val app = fake("", tool("query", null))
        val broken = McpServerStore.saveDetails(
            McpServerStore.newServer("Broken", "http://127.0.0.1:${app.port}/mcp")
        )
        val working = McpServerStore.saveDetails(
            McpServerStore.newServer("Working", "http://127.0.0.1:${app.port}/mcp")
        )
        mockkObject(McpServerStore)
        try {
            every { McpServerStore.token(broken.id) } throws AbstractMethodError("KeystoreSecretStore.readAndMigrate")
            every { McpServerStore.token(working.id) } returns KeystoreSecretStore.Stored.Absent
            every { McpServerStore.headers(any()) } returns emptyMap()

            val answered = survives("refreshAll") { McpToolCatalog.refreshAll() }

            assertEquals(1, answered)
            assertEquals(listOf("query"), McpToolCatalog.tools(working.id).map { it.name })
        } finally {
            unmockkObject(McpServerStore)
        }
    }

    /**
     * The removal comes first in deactivate, so an Error from it used to skip every later step: the
     * scope and its 20 s probe kept running, and nothing was released.
     */
    @Test
    fun `a listener removal that throws a linkage error still stops the scope and releases the connections`() {
        val app = fake("t1", tool("query", true))
        val service = mockk<DebuggerEndpointService> {
            every { endpoints } returns listOf(DebuggerEndpoint("com.example.app", app.port, "t1", 0L))
        }
        every { context.services.get(DebuggerEndpointService::class.java) } returns service
        val id = AutomaticServers.idFor("com.example.app")
        assertTrue(plugin.activate())
        val job = requireNotNull(plugin.scopeJob)
        awaitUntil("the activation listed the tools") { McpToolCatalog.tools(id).isNotEmpty() }
        every { context.removePluginLifecycleListener(any()) } throws
            AbstractMethodError("PluginContext.removePluginLifecycleListener")

        val escaped = try {
            plugin.deactivate()
            null
        } catch (e: Error) {
            e
        } finally {
            every { context.removePluginLifecycleListener(any()) } just Runs
        }

        assertTrue("the activation's scope must be stopped", job.isCancelled)
        assertTrue("the automatic entries must be released", AutomaticServers.servers().isEmpty())
        assertTrue("the cached tools must be released", McpToolCatalog.tools(id).isEmpty())
        assertNull("deactivate let $escaped escape", escaped)
    }

    /** Every listener registry the plugin registers with, so a test can see one left behind. */
    private fun listenerCounts() = mapOf(
        "AutomaticServers change" to AutomaticServers.changeListenerCount,
        "AutomaticServers endpoint" to AutomaticServers.endpointListenerCount,
        "McpServerStore change" to McpServerStore.changeListenerCount,
        "McpServerHealth change" to McpServerHealth.changeListenerCount,
    )

    /**
     * The lifecycle listener is added after the scope has started and the change listeners are in.
     * The Error used to escape activate, which caught only an Exception, and the host need not call
     * deactivate after a failed activate: the scope and the listeners were left behind. Now the
     * failure costs only the listener, as its removal's does in deactivate.
     */
    @Test
    fun `a lifecycle listener the host cannot add costs only that listener`() {
        every { context.addPluginLifecycleListener(any()) } throws
            AbstractMethodError("PluginContext.addPluginLifecycleListener")
        val before = listenerCounts()

        val activated = survives("activate") { plugin.activate() }

        assertTrue("the activation must carry on", activated)
        assertTrue("the activation's scope must be live", requireNotNull(plugin.scopeJob).isActive)
        assertEquals(before.mapValues { it.value + 1 }, listenerCounts())
        plugin.deactivate()
        assertEquals(before, listenerCounts())
    }

    @Test
    fun `an activation that fails with a linkage error leaves no scope and no listener behind`() {
        assertFailedActivationLeavesNothing(AbstractMethodError("PluginLogger.info"))
    }

    @Test
    fun `an activation that fails with an exception leaves no scope and no listener behind`() {
        assertFailedActivationLeavesNothing(IllegalStateException("PluginLogger.info"))
    }

    /**
     * Fails an activation after it has started its scope and registered its listeners, at the one
     * host call left unguarded by then: the note that AI Core is not active yet.
     * @param failure what that call throws.
     */
    private fun assertFailedActivationLeavesNothing(failure: Throwable) {
        val logger = mockk<PluginLogger>(relaxed = true)
        every { context.logger } returns logger
        every { context.getPluginService(any(), ToolSourceRegistry::class.java) } returns null
        every { logger.info(match { it.contains("AI Core is not active") }) } throws failure
        val before = listenerCounts()

        val activated = survives("activate") { plugin.activate() }

        assertFalse("a failed activation must say so", activated)
        assertTrue("the failed activation's scope must be stopped", requireNotNull(plugin.activationJob).isCancelled)
        assertEquals("listeners left behind", before, listenerCounts())
    }

    @Test
    fun `an error that escapes a coroutine stops at the backstop, not at the IDE's handler`() {
        val reached = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> reached += e }
        try {
            runBlocking {
                plugin.newScope().launch { throw AbstractMethodError("an unguarded host call") }.join()
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertEquals("reached the IDE's uncaught-exception handler", emptyList<Throwable>(), reached.toList())
    }
}
