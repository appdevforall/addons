package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import com.itsaky.androidide.plugins.services.DebuggerEndpointService
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The DELETE that ends a SQLite debugger entry's session carries the entry's token, whether the
 * plugin is deactivated or the entry's app goes away, as when its project is closed.
 *
 * The app's MCP server refuses a DELETE without the token, and the session stays open. Both
 * DELETEs read the token when they were sent, and by then it had gone: a deactivation clears the
 * entries before it ends the sessions, outside its lock, and a removed entry has left the list
 * before its session is ended. The token is now read as each session is taken out, from the entry
 * as it is removed.
 */
class AutomaticSessionCloseTest {

    /** Asks for the token on every request but a DELETE, and records each with its Authorization. */
    private val server = FakeMcpServer(TOKEN, listOf(tool("query", true)), sessionId = "session-1")

    private val stored = AtomicReference(listOf(AutomaticEndpoint(APP, server.port, TOKEN)))

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        // The entry is installed by the test, not read from the host.
        every { context.services.get(DebuggerEndpointService::class.java) } returns null
        every { context.getPluginService(any(), ToolSourceRegistry::class.java) } returns null
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM; nothing here reads a hand-added server's secrets.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
    }

    @After
    fun tearDown() {
        AutomaticServers.install(null)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        plugin.dispose()
        server.stop()
        unmockkObject(McpServerStore)
    }

    /** Installs the entry and lists it, which leaves it a session with a server-side id. */
    private fun connectEntry() {
        AutomaticServers.install { stored.get() }
        McpToolCatalog.connect(AutomaticServers.servers().single())
    }

    private fun deleteAuthorizations() =
        server.requests.filter { it.httpMethod == "DELETE" }.map { it.authorization }

    @Test
    fun `a deactivation's DELETE for a SQLite debugger entry carries the entry's token`() {
        plugin.activate()
        connectEntry()

        plugin.deactivate()

        assertEquals("the DELETE ending the entry's session", listOf("Bearer $TOKEN"), deleteAuthorizations())
    }

    @Test
    fun `the DELETE when an entry's app goes away carries the entry's token`() {
        connectEntry()

        stored.set(emptyList())
        AutomaticServers.refresh()

        assertEquals("the DELETE ending the entry's session", listOf("Bearer $TOKEN"), deleteAuthorizations())
    }

    private companion object {
        const val APP = "com.example.notes"
        const val TOKEN = "t1"
    }
}
