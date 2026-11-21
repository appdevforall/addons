package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * A lifecycle call whose teardown fails after it has taken the sessions out still ends them, and a
 * disposal still lets go of the plugin context.
 *
 * The teardown returned the sessions it took out only once its last step had run. A step after
 * the take-out that threw, clearing the SQLite debugger entries here, lost them: nothing ended
 * them, so their servers kept them open. A disposal also skipped clearing the context. The
 * sessions now go into the caller's list as they are taken out, and the caller ends them whatever
 * the teardown threw.
 */
class TeardownFailureSessionTest {

    private val server = FakeMcpServer("", listOf(tool("search", true)), sessionId = "session-1")
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Absent
        every { McpServerStore.headers(any()) } returns emptyMap()
        val saved = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "http://127.0.0.1:${server.port}/mcp")
        )
        McpConnections.withSession(saved) { it.initialize() }
        // The teardown's last step, after the sessions are taken out.
        mockkObject(AutomaticServers)
        every { AutomaticServers.install(null) } throws IllegalStateException("clearing the entries failed")
    }

    @After
    fun tearDown() {
        unmockkObject(AutomaticServers)
        McpConnections.closeAll()
        McpToolCatalog.clear()
        server.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a deactivation whose teardown fails after taking the sessions out still ends them`() {
        assertFalse(plugin.deactivate())

        assertEquals(
            "the session taken out before the failing step was never ended",
            1,
            server.requests.count { it.httpMethod == "DELETE" },
        )
    }

    @Test
    fun `a disposal whose teardown fails still ends the sessions and lets go of the context`() {
        assertThrows(IllegalStateException::class.java) { plugin.dispose() }

        assertEquals(
            "the session taken out before the failing step was never ended",
            1,
            server.requests.count { it.httpMethod == "DELETE" },
        )
        assertNull("the disposal kept the plugin context", McpPlugin.getContext())
    }
}
