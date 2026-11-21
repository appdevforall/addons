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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A failed credential write while a server's URL changes never sends the old credential to the
 * new URL.
 *
 * The save wrote the URL, then the token and the headers, and reported "token save failed" when
 * the Keystore refused one. The refused write left the old value stored, so the new URL's first
 * request carried the old token or the old headers. The old value is now forgotten in the same
 * step as the URL change; when forgetting fails too, the URL is put back.
 *
 * The secret store here is a fake that refuses every write of a value, and refuses the forgetting
 * as well when [forgetFails] is set.
 */
class FailedCredentialWriteTest {

    private val old = FakeMcpServer("old-token", listOf(tool("search", true)), sessionId = "old-session")
    private val moved = FakeMcpServer("", listOf(tool("search", true)))

    @Volatile private var token = "old-token"
    @Volatile private var headers = mapOf("X-Old" to "1")
    @Volatile private var forgetFails = false

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } answers {
            token.takeIf { it.isNotEmpty() }?.let { KeystoreSecretStore.Stored.Value(it) }
                ?: KeystoreSecretStore.Stored.Absent
        }
        every { McpServerStore.headers(any()) } answers { headers }
        // The Keystore refuses to store a value; a blank, which removes, lands unless told not to.
        every { McpServerStore.writeToken(any(), any()) } answers {
            val value = secondArg<String>()
            if (value.isNotEmpty() || forgetFails) false else { token = ""; true }
        }
        every { McpServerStore.writeHeaders(any(), any()) } answers {
            val value = secondArg<Map<String, String>>()
            if (value.isNotEmpty() || forgetFails) false else { headers = emptyMap(); true }
        }
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
    fun `a refused token write during a URL change leaves the new URL with no token`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))

        val (record, failure) = McpSettingsViewModel { null }
            .saveNow(saved.copy(url = url(moved)), "new-token", null)

        assertNotNull("the pane was not told the token was not saved", failure)
        assertEquals(url(moved), record.url)
        // The headers were not part of this save: the user kept them, as for any URL change.
        assertNull("the new URL was sent the old token", firstRequestToTheNewUrl(saved.id).authorization)
    }

    @Test
    fun `a refused headers write during a URL change leaves the new URL with no headers`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))

        val (record, failure) = McpSettingsViewModel { null }
            .saveNow(saved.copy(url = url(moved)), null, mapOf("X-New" to "1"))

        assertNotNull("the pane was not told the headers were not saved", failure)
        assertEquals(url(moved), record.url)
        assertFalse(
            "the new URL was sent the old headers",
            "x-old" in firstRequestToTheNewUrl(saved.id).headerNames,
        )
    }

    @Test
    fun `when forgetting the old credential fails too, the URL stays where the credential belongs`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(old)))
        forgetFails = true

        val (record, failure) = McpSettingsViewModel { null }
            .saveNow(saved.copy(url = url(moved)), "new-token", mapOf("X-New" to "1"))

        assertNotNull("the pane was not told the save failed", failure)
        assertEquals("the URL moved with the old credential still stored", url(old), record.url)
        McpConnections.withSession(McpServerStore.server(saved.id)!!) { it.initialize() }
        assertEquals("the new URL was contacted", emptyList<FakeMcpServer.Seen>(), moved.requests.toList())
    }

    private fun firstRequestToTheNewUrl(id: String): FakeMcpServer.Seen {
        McpConnections.withSession(McpServerStore.server(id)!!) { it.initialize() }
        return moved.requests.first()
    }

    private fun url(server: FakeMcpServer) = "http://127.0.0.1:${server.port}/mcp"
}
