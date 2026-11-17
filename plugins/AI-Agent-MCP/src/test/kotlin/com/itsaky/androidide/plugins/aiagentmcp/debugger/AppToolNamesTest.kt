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


/**
 * What the model is told for each SQLite debugger entry: tools that differ per app in name and
 * description, so two apps reporting for one project are never confused.
 */
class AppToolNamesTest {

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

    private fun connectAll(vararg apps: Pair<String, FakeMcpServer>, reversed: Boolean = false) {
        val endpoints = apps.map { (id, server) -> AutomaticEndpoint(id, server.port, server.token) }
        AutomaticServers.install { if (reversed) endpoints.reversed() else endpoints }
        AutomaticServers.servers().forEach { McpToolCatalog.connect(it) }
    }

    /** The exposed names by application ID, read from each description. */
    private fun byApp(): Map<String, Pair<String, String>> = offered().values.associate {
        it.description.substringBefore(" (SQLite debugger)") to (it.name to it.description)
    }

    @Test
    fun `two apps get distinct tool names and descriptions naming each app`() {
        connectAll(
            "com.example.notes" to fake("a", tool("query", true)),
            "com.example.shop" to fake("b", tool("query", true)),
        )

        val apps = byApp()
        assertEquals(setOf("com.example.notes", "com.example.shop"), apps.keys)
        assertEquals("db_notes_query", apps.getValue("com.example.notes").first)
        assertEquals("db_shop_query", apps.getValue("com.example.shop").first)
        assertEquals("com.example.notes (SQLite debugger): query", apps.getValue("com.example.notes").second)
    }

    @Test
    fun `apps sharing a last segment stay distinct and keep their names whatever the row order`() {
        val one = "com.alpha.notes"
        val two = "com.beta.notes"
        connectAll(one to fake("a", tool("query", true)), two to fake("b", tool("query", true)))
        val forward = byApp()
        assertEquals(2, forward.values.map { it.first }.toSet().size)
        assertTrue(forward.getValue(one).first.length <= 10 + "_query".length)

        AutomaticServers.install(null)
        McpToolCatalog.clear()
        servers.forEach { it.stop() }
        servers.clear()
        connectAll(one to fake("a", tool("query", true)), two to fake("b", tool("query", true)), reversed = true)

        assertEquals(forward, byApp())
    }

    @Test
    fun `a single app still works and a hand-added server keeps its name and description`() {
        val debugger = fake("a", tool("query", true))
        connectAll("com.example.notes" to debugger)
        val mine = fake("", tool("claims", true))
        val handAdded = McpServerStore.saveDetails(
            McpServerStore.newServer("Company Docs", "http://127.0.0.1:${mine.port}/mcp")
        )
        McpToolCatalog.connect(handAdded)
        McpServerStore.setToolEnabled(handAdded.id, "claims", true)

        val specs = offered()
        assertEquals(setOf("db_notes_query", "company_do_claims"), specs.keys)
        assertEquals("claims", specs.getValue("company_do_claims").description)
        val outcome = invoke("db_notes_query")
        assertTrue(outcome.errorMessage, outcome.isSuccess)
    }
}
