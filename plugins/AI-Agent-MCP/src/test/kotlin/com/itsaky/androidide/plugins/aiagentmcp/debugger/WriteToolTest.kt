package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.testing.stringsXmlContext
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolSource
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The SQLite debugger's `execute` (ADFA-7300): offered only while the settings switch is on, and
 * never read-only, so AI-Core asks before every call and never offers "Always Allow" for it.
 */
class WriteToolTest {

    private val app = "com.example.app"
    private var server: FakeMcpServer? = null
    private val source = McpToolSource()
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        every { context.androidContext } returns stringsXmlContext()
        plugin = McpPlugin().also { it.initialize(context) }
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
        server?.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    /** A debugger whose execute claims readOnlyHint, as a buggy or hostile server could. */
    private fun connect() {
        val fake = FakeMcpServer("t1", listOf(tool("list_databases", true), tool("query", true), tool("execute", true)))
        server = fake
        AutomaticServers.install { listOf(AutomaticEndpoint(app, fake.port, "t1")) }
        McpToolCatalog.connect(AutomaticServers.servers().single())
    }

    /** What `query` adds while the switch is off, as strings.xml words it. */
    private val hint = "Changes to this app's databases are off. To make one, ask the user to switch on this setting on " +
        "AI-Agent-MCP's MCP servers screen: Let the Agent change app databases. Don't change the databases any other " +
        "way, for example through the shell."

    /** The offered tools, keyed by the tool name after the app prefix (`db_app_list_databases` -> `databases`). */
    private fun offered() = source.listTools().associateBy { it.name.substringAfterLast("_") }

    @Test
    fun `the switch is off by default and execute is not offered, even claiming the hint`() {
        assertFalse(McpServerStore.allowDatabaseWrites())
        connect()
        assertEquals(setOf("databases", "query"), offered().keys)
        assertEquals(setOf("list_databases", "query"), AutomaticServers.servers().single().enabledTools)
    }

    @Test
    fun `with the switch on execute is offered, asks every time and is not read-only`() {
        connect()
        McpServerStore.setAllowDatabaseWrites(true)
        assertTrue(McpServerStore.allowDatabaseWrites())
        val execute = offered().getValue("execute")
        assertTrue(execute.requiresApproval())
        assertFalse(execute.isReadOnly)
        // the read-only tools keep their grant
        assertFalse(offered().getValue("query").requiresApproval())
    }

    @Test
    fun `switching it off takes execute away again`() {
        connect()
        McpServerStore.setAllowDatabaseWrites(true)
        McpServerStore.setAllowDatabaseWrites(false)
        assertFalse("execute" in offered())
    }

    @Test
    fun `changing the switch tells the change listeners, so the Agent's list follows`() {
        var calls = 0
        val listener = { calls++; Unit }
        McpServerStore.addChangeListener(listener)
        try {
            McpServerStore.setAllowDatabaseWrites(true)
            McpServerStore.setAllowDatabaseWrites(false)
        } finally {
            McpServerStore.removeChangeListener(listener)
        }
        assertEquals(2, calls)
    }

    @Test
    fun `a hand-added server's execute is an ordinary tool, off until switched on, never read-only`() {
        McpServerStore.setAllowDatabaseWrites(true)
        val fake = FakeMcpServer("", listOf(tool("execute", true)))
        server = fake
        val handAdded = McpServerStore.saveDetails(McpServerStore.newServer("Mine", "http://127.0.0.1:${fake.port}/mcp"))
        McpToolCatalog.connect(handAdded)
        assertTrue(offered().isEmpty())
        McpServerStore.setToolEnabled(handAdded.id, "execute", true)
        val spec = offered().values.single()
        assertTrue(spec.requiresApproval())
        assertFalse(spec.isReadOnly)
    }

    @Test
    fun `with the switch off, query says how writes get allowed`() {
        connect()
        assertEquals("$app (SQLite debugger): query $hint", offered().getValue("query").description)
        assertEquals("$app (SQLite debugger): list_databases", offered().getValue("databases").description)
    }

    @Test
    fun `with the switch on, query has no hint`() {
        connect()
        McpServerStore.setAllowDatabaseWrites(true)
        assertEquals("$app (SQLite debugger): query", offered().getValue("query").description)
    }

    @Test
    fun `a debugger without execute gets no hint`() {
        val fake = FakeMcpServer("t1", listOf(tool("list_databases", true), tool("query", true)))
        server = fake
        AutomaticServers.install { listOf(AutomaticEndpoint(app, fake.port, "t1")) }
        McpToolCatalog.connect(AutomaticServers.servers().single())
        assertEquals("$app (SQLite debugger): query", offered().getValue("query").description)
    }

    @Test
    fun `a hand-added server's query is passed through, execute on or off`() {
        val fake = FakeMcpServer("", listOf(tool("query", true), tool("execute", true)))
        server = fake
        val handAdded = McpServerStore.saveDetails(McpServerStore.newServer("Mine", "http://127.0.0.1:${fake.port}/mcp"))
        McpToolCatalog.connect(handAdded)
        McpServerStore.setToolEnabled(handAdded.id, "query", true)
        assertEquals("query", offered().values.single().description)
    }
}
