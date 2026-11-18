package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An OutOfMemoryError (or a StackOverflowError, or a ThreadDeath) is the process failing, not one
 * host call. The guards that keep a LinkageError from a mismatched plugin-api away from the IDE's
 * uncaught-exception handler must let it through: caught, it was logged as an ordinary failure,
 * every 20 s on the probe's path, and the IDE never learnt of it.
 */
class McpPluginFatalErrorTest {

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
        // First, so no listener of this plugin hears the cleanup below.
        plugin.dispose()
        SharedServices.unregister(ToolSourceRegistry::class.java)
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
    }

    private fun fake(token: String, vararg tools: JSONObject, sessionId: String? = null) =
        FakeMcpServer(token, tools.toList(), sessionId).also { servers += it }

    private fun userServer(port: Int): McpServer =
        McpServerStore.saveDetails(McpServerStore.newServer("Server", "http://127.0.0.1:$port/mcp"))

    /** Runs [block] with the default uncaught-exception handler replaced; returns what reached it. */
    private fun reachingDefaultHandler(waitMs: Long = 0, block: () -> Unit): List<Throwable> {
        val reached = CopyOnWriteArrayList<Throwable>()
        val first = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            reached += e
            first.countDown()
        }
        try {
            block()
            if (waitMs > 0) first.await(waitMs, TimeUnit.MILLISECONDS)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        return reached.toList()
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting until $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `an OutOfMemoryError that escapes a coroutine reaches the IDE's handler`() {
        val error = OutOfMemoryError("in a coroutine of the plugin's scope")

        val reached = reachingDefaultHandler {
            runBlocking { plugin.newScope().launch { throw error }.join() }
        }

        assertEquals(1, reached.size)
        assertSame(error, reached.single())
    }

    @Test
    fun `a keystore call that throws an OutOfMemoryError ends the refresh`() {
        val server = userServer(fake("", tool("query", null)).port)
        mockkObject(McpServerStore)
        try {
            every { McpServerStore.token(server.id) } throws OutOfMemoryError("KeystoreSecretStore.readAndMigrate")

            assertThrows(OutOfMemoryError::class.java) { McpToolCatalog.refreshAll() }
        } finally {
            unmockkObject(McpServerStore)
        }
    }

    @Test
    fun `a keystore call that throws an OutOfMemoryError ends the health probe`() {
        val server = userServer(fake("", tool("query", null)).port)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        mockkObject(McpServerStore)
        try {
            every { McpServerStore.token(server.id) } throws OutOfMemoryError("KeystoreSecretStore.readAndMigrate")

            assertThrows(OutOfMemoryError::class.java) {
                runBlocking { plugin.probeHealth({ System.nanoTime() < deadline }, intervalMs = 10) }
            }
        } finally {
            unmockkObject(McpServerStore)
        }
    }

    @Test
    fun `a registry that throws an OutOfMemoryError ends the health probe`() {
        val active = AtomicBoolean(true)
        val registry = mockk<ToolSourceRegistry>(relaxed = true) {
            every { notifyToolsChanged(any()) } answers {
                active.set(false)
                throw OutOfMemoryError("ToolSourceRegistry.notifyToolsChanged")
            }
        }
        SharedServices.register(ToolSourceRegistry::class.java, registry)
        val app = fake("t1", tool("query", true))
        AutomaticServers.install { listOf(AutomaticEndpoint("com.example.app", app.port, "t1")) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)

        assertThrows(OutOfMemoryError::class.java) {
            runBlocking {
                plugin.probeHealth({ active.get() && System.nanoTime() < deadline }, intervalMs = 10)
            }
        }
    }

    @Test
    fun `a debugger service lookup that throws an OutOfMemoryError reaches the caller`() {
        every { context.services.get(DebuggerEndpointService::class.java) } throws
            OutOfMemoryError("PluginContext.services.get")

        assertThrows(OutOfMemoryError::class.java) { plugin.startDebuggerEntry { true } }
    }

    @Test
    fun `a registry lookup that throws an OutOfMemoryError ends the activation`() {
        every { context.getPluginService(any(), ToolSourceRegistry::class.java) } throws
            OutOfMemoryError("PluginContext.getPluginService")
        try {
            assertThrows(OutOfMemoryError::class.java) { plugin.activate() }
        } finally {
            every { context.getPluginService(any(), ToolSourceRegistry::class.java) } returns null
        }
    }

    @Test
    fun `a registration that throws an OutOfMemoryError ends the activation`() {
        val registry = mockk<ToolSourceRegistry>(relaxed = true) {
            every { registerToolSource(any()) } throws OutOfMemoryError("ToolSourceRegistry.registerToolSource")
        }
        SharedServices.register(ToolSourceRegistry::class.java, registry)

        assertThrows(OutOfMemoryError::class.java) { plugin.activate() }
    }

    @Test
    fun `an unregistration that throws an OutOfMemoryError ends the deactivation`() {
        val registry = mockk<ToolSourceRegistry>(relaxed = true)
        SharedServices.register(ToolSourceRegistry::class.java, registry)
        assertTrue(plugin.activate())
        every { registry.unregisterToolSource(any()) } throws
            OutOfMemoryError("ToolSourceRegistry.unregisterToolSource")
        try {
            assertThrows(OutOfMemoryError::class.java) { plugin.deactivate() }
        } finally {
            every { registry.unregisterToolSource(any()) } just Runs
        }
    }

    @Test
    fun `a health report that throws an OutOfMemoryError reaches whoever changed the health`() {
        val registry = mockk<ToolSourceRegistry>(relaxed = true)
        SharedServices.register(ToolSourceRegistry::class.java, registry)
        assertTrue(plugin.activate())
        every { registry.notifyToolSourceStatusChanged(any()) } throws
            OutOfMemoryError("ToolSourceRegistry.notifyToolSourceStatusChanged")
        try {
            assertThrows(OutOfMemoryError::class.java) { McpServerHealth.available("a-server") }
        } finally {
            every { registry.notifyToolSourceStatusChanged(any()) } just Runs
        }
    }

    @Test
    fun `a settings listener that throws an OutOfMemoryError reaches whoever changed the settings`() {
        val listener: () -> Unit = { throw OutOfMemoryError("telling the agent") }
        McpServerStore.addChangeListener(listener)
        try {
            assertThrows(OutOfMemoryError::class.java) { userServer(1) }
        } finally {
            McpServerStore.removeChangeListener(listener)
        }
    }

    @Test
    fun `a keystore call that throws an OutOfMemoryError while closing a session reaches the caller`() {
        val server = userServer(fake("", tool("query", null), sessionId = "s1").port)
        mockkObject(McpServerStore)
        try {
            every { McpServerStore.token(server.id) } returns KeystoreSecretStore.Stored.Absent
            every { McpServerStore.headers(any()) } returns emptyMap()
            McpToolCatalog.connect(server)
            every { McpServerStore.token(server.id) } throws OutOfMemoryError("KeystoreSecretStore.readAndMigrate")

            assertThrows(OutOfMemoryError::class.java) { McpConnections.closeAll() }
        } finally {
            unmockkObject(McpServerStore)
        }
    }

    @Test
    fun `dispose lets an OutOfMemoryError from the host through`() {
        every { context.removePluginLifecycleListener(any()) } throws
            OutOfMemoryError("PluginContext.removePluginLifecycleListener")
        try {
            assertThrows(OutOfMemoryError::class.java) { plugin.dispose() }
        } finally {
            every { context.removePluginLifecycleListener(any()) } just Runs
        }
    }

    @Test
    fun `a relist that throws an OutOfMemoryError reaches the IDE's handler`() {
        val app = fake("t1", tool("query", true))
        val currentPort = AtomicInteger(app.port)
        val service = mockk<DebuggerEndpointService> {
            every { endpoints } answers { listOf(DebuggerEndpoint("com.example.app", currentPort.get(), "t1", 0L)) }
        }
        every { context.services.get(DebuggerEndpointService::class.java) } returns service
        val id = AutomaticServers.idFor("com.example.app")
        assertTrue(plugin.activate())
        // The activation's own refresh lists the tools; after it only a relist connects, until the
        // probe's first cycle 20 s later.
        awaitUntil("the activation listed the tools") { McpToolCatalog.tools(id).isNotEmpty() }
        mockkObject(McpToolCatalog)
        try {
            every { McpToolCatalog.connect(any(), any()) } throws OutOfMemoryError("relisting a relaunched app")

            val reached = reachingDefaultHandler(waitMs = 10_000) {
                currentPort.set(app.port + 1)
                AutomaticServers.refresh()
            }

            assertTrue("reached the IDE's handler: $reached", reached.firstOrNull() is OutOfMemoryError)
        } finally {
            unmockkObject(McpToolCatalog)
        }
    }
}
