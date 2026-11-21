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
import org.junit.Assert.assertEquals
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

    /**
     * The second DELETE, for the session a call stored meanwhile, goes out after the server's token
     * was deleted with it; it carries the token read for the first one instead.
     */
    @Test
    fun `the second DELETE of a deleted server carries the credentials read for the first`() {
        val guarded = FakeMcpServer("ut", listOf(tool("search", true)), sessionId = "session-2")
        try {
            var removed = false
            every { McpServerStore.token(any()) } answers {
                if (removed) KeystoreSecretStore.Stored.Absent else KeystoreSecretStore.Stored.Value("ut")
            }
            val saved = McpServerStore.saveDetails(
                McpServerStore.newServer("Guarded", "http://127.0.0.1:${guarded.port}/mcp")
            )
            McpConnections.session(saved).initialize()
            every { McpServerStore.remove(saved.id) } answers {
                // A call landing between the two steps: its session has a server-side id too.
                McpConnections.session(saved).initialize()
                callOriginal().also { removed = true }
            }

            McpSettingsViewModel { null }.deleteNow(saved.id)

            assertEquals(
                "the DELETEs ending the deleted server's two sessions",
                listOf("Bearer ut", "Bearer ut"),
                guarded.requests.filter { it.httpMethod == "DELETE" }.map { it.authorization },
            )
        } finally {
            guarded.stop()
        }
    }

    /**
     * The same when the deletion found no session to end first, so no credentials were read for
     * one: the DELETE for the session a call stored meanwhile carries the token read before the
     * server was removed, not none.
     */
    @Test
    fun `the second DELETE of a deleted server with no first session carries its token`() {
        val guarded = FakeMcpServer("ut", listOf(tool("search", true)), sessionId = "session-3")
        try {
            var removed = false
            every { McpServerStore.token(any()) } answers {
                if (removed) KeystoreSecretStore.Stored.Absent else KeystoreSecretStore.Stored.Value("ut")
            }
            val saved = McpServerStore.saveDetails(
                McpServerStore.newServer("Guarded", "http://127.0.0.1:${guarded.port}/mcp")
            )
            every { McpServerStore.remove(saved.id) } answers {
                // A call landing between the two steps, the server's first session.
                McpConnections.session(saved).initialize()
                callOriginal().also { removed = true }
            }

            McpSettingsViewModel { null }.deleteNow(saved.id)

            assertEquals(
                "the DELETE ending the session stored during the deletion",
                listOf("Bearer ut"),
                guarded.requests.filter { it.httpMethod == "DELETE" }.map { it.authorization },
            )
        } finally {
            guarded.stop()
        }
    }
}
