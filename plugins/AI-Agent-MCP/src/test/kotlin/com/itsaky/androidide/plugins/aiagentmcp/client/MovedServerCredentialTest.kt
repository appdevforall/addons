package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
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
import org.junit.Before
import org.junit.Test

/**
 * A server of the user's moved to another URL never hands the credentials entered for the new URL
 * to the old one.
 *
 * The settings pane stored the new URL, token and headers, and only then ended the old session. Its
 * DELETE read the server's credentials without asking which URL they were for, so it went to the
 * old URL with the new token and headers; so did any request of the old session still running.
 * The credentials are now released only to a session built for the URL the server has, the rule a
 * SQLite debugger entry already followed.
 *
 * The save now takes the old session out before it writes, so that session's DELETE carries the
 * old credentials (SaveServerSessionTest). The rule still holds for any session of the old URL
 * ended once the server has moved: one a call stored between that take-out and the write, which the
 * next lookup replaces, and every request still running on the old session.
 */
class MovedServerCredentialTest {

    private val old = FakeMcpServer("", listOf(tool("search", true)), sessionId = "old-session")
    private val moved = FakeMcpServer("new-token", listOf(tool("search", true)))

    @Volatile private var token = "old-token"
    @Volatile private var headers = mapOf("X-Team" to "old")

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM: the store answers what the user last entered.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } answers { KeystoreSecretStore.Stored.Value(token) }
        every { McpServerStore.headers(any()) } answers { headers }
    }

    @After
    fun tearDown() {
        McpConnections.closeAll()
        McpToolCatalog.clear()
        old.stop()
        moved.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `the old session's DELETE carries none of the credentials entered for the new URL`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        McpConnections.withSession(saved) { it.initialize() }
        assertEquals("the old session did not carry the old token", "Bearer old-token", old.requests.first().authorization)

        moveToTheNewUrl(saved)
        McpConnections.invalidate(saved.id)

        val delete = old.requests.single { it.httpMethod == "DELETE" }
        assertNull("the old URL was sent a token", delete.authorization)
        assertFalse("the old URL was sent the user's headers", "x-team" in delete.headerNames)
    }

    @Test
    fun `a request of the old session after the move carries none of the new credentials`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        val session = McpConnections.withSession(saved) { it.also { kept -> kept.initialize() } }

        moveToTheNewUrl(saved)
        session.listTools()

        val listing = old.requests.last()
        assertEquals("POST", listing.httpMethod)
        assertNull("the old URL was sent a token", listing.authorization)
        assertFalse("the old URL was sent the user's headers", "x-team" in listing.headerNames)
    }

    /** What the settings pane's save stores; a session of the old URL is ended after it. */
    private fun moveToTheNewUrl(saved: McpServer) {
        McpServerStore.saveDetails(saved.copy(url = url(moved)))
        token = "new-token"
        headers = mapOf("X-Team" to "new")
    }

    private fun url(server: FakeMcpServer) = "http://127.0.0.1:${server.port}/mcp"
}
