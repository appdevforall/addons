package com.itsaky.androidide.plugins.aiagentmcp.settings

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deleting one of the user's servers leaves no session behind, even one a call stored while the
 * deletion ran.
 *
 * The deletion ends the server's session first, so the DELETE still has the credentials that
 * removing the server deletes. A call or a listing that looked the session up between the two
 * stored a new one, for a server that was then gone, and nothing would ever end it. The session
 * is now ended again once the server is gone.
 *
 * The lookup is made from inside the store's removal, just before it runs: the one point between
 * the two steps. Nothing sleeps.
 */
class DeleteServerSessionTest {

    private lateinit var server: FakeMcpServer
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
        server = FakeMcpServer("", listOf(tool("search", true)), sessionId = "session-1")
    }

    @After
    fun tearDown() {
        McpConnections.closeAll()
        McpToolCatalog.clear()
        server.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a session stored while the server was being deleted is ended with it`() {
        val saved = McpServerStore.saveDetails(
            McpServerStore.newServer("Docs", "http://127.0.0.1:${server.port}/mcp")
        )
        McpConnections.session(saved).initialize()
        var lookedUp = false
        every { McpServerStore.remove(saved.id) } answers {
            // A call landing between the two steps: its lookup stores a session for the server.
            McpConnections.session(saved)
            lookedUp = true
            callOriginal()
        }

        McpSettingsViewModel { null }.deleteNow(saved.id)

        assertTrue("the removal never ran", lookedUp)
        assertNull("a session stored during the deletion outlived the server", McpConnections.detach(saved.id))
    }
}
