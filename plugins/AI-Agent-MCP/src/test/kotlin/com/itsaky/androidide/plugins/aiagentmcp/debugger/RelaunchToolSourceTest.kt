package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolSource
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the agent is offered, driven through [McpToolSource] against loopback servers. */
class RelaunchToolSourceTest {

    private val app = "com.example.app"
    private val servers = mutableListOf<FakeMcpServer>()
    private val source = McpToolSource()
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; a hand-added server in these tests has no secrets.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        source.close()
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    private fun fake(token: String, vararg tools: org.json.JSONObject) =
        FakeMcpServer(token, tools.toList()).also { servers += it }

    private fun offered(): Map<String, ToolSourceRegistry.ToolSpec> =
        source.listTools().associateBy { it.name }

    private fun invoke(name: String): ToolSourceRegistry.ToolOutcome =
        source.invoke(
            mockk<ToolSourceRegistry.ToolInvocation> {
                every { toolName } returns name
                every { arguments } returns emptyMap()
                every { callId } returns "call-1"
            }
        ).get(30, TimeUnit.SECONDS)

    private fun debuggerTools() = arrayOf(tool("list_tables", true), tool("run_sql", false), tool("unmarked", null))

    @Test
    fun `a relaunch keeps the read-only tools offered and the next call reaches the new endpoint`() {
        val first = fake("t1", *debuggerTools())
        var stored = listOf(AutomaticEndpoint(app, first.port, "t1"))
        AutomaticServers.install { stored }
        McpToolCatalog.connect(AutomaticServers.servers().single())
        val before = offered()
        assertEquals(1, before.size)
        val name = before.keys.single()

        val second = fake("t2", *debuggerTools())
        stored = listOf(AutomaticEndpoint(app, second.port, "t2"))
        assertTrue(AutomaticServers.refresh())

        // No re-list has happened yet: the agent must not lose its tools meanwhile.
        assertEquals(setOf(name), offered().keys)
        val outcome = invoke(name)
        assertTrue(outcome.errorMessage, outcome.isSuccess)
        assertEquals("served by ${second.port}", outcome.output)
    }

    @Test
    fun `an automatic entry's read-only tool is pre-approved and the others are not offered`() {
        val server = fake("t1", *debuggerTools())
        AutomaticServers.install { listOf(AutomaticEndpoint(app, server.port, "t1")) }
        McpToolCatalog.connect(AutomaticServers.servers().single())

        val spec = offered().values.single()
        assertTrue(spec.name.endsWith("list_tables"))
        assertFalse(spec.requiresApproval())
        assertTrue(spec.isReadOnly)
    }

    @Test
    fun `a hand-added server claiming readOnlyHint is off by default and asks once switched on`() {
        val server = fake("", tool("claims", true))
        val handAdded = McpServerStore.saveDetails(
            McpServerStore.newServer("Mine", "http://127.0.0.1:${server.port}/mcp")
        )
        McpToolCatalog.connect(handAdded)
        assertTrue(McpToolCatalog.tools(handAdded.id).single().readOnlyHint)

        assertTrue(offered().isEmpty())

        McpServerStore.setToolEnabled(handAdded.id, "claims", true)
        val spec = offered().values.single()
        assertTrue(spec.requiresApproval())
        assertFalse(spec.isReadOnly)
    }
}
