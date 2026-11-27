package com.itsaky.androidide.plugins.aiagentmcp.tools

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeMcpServer.Companion.tool
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A probe or a listing handed one of the user's servers before its URL was edited neither marks
 * the server refused nor replaces its session.
 *
 * Such a caller asks the old URL, which gets no credentials once the server has moved. Its 401
 * marked the server degraded and refused after the edit had cleared that, so the probe skipped it
 * until the user edited it again; and its lookup, by id alone, stored a session for the old URL in
 * place of the one for the new, ending that one. A server of the user's now counts as configured
 * only at the URL it is stored with.
 */
class StaleServerAfterEditTest {

    private val before = FakeMcpServer("t", listOf(tool("search", true)), sessionId = "before")
    private val after = FakeMcpServer("t", listOf(tool("search", true)), sessionId = "after")

    private lateinit var plugin: McpPlugin
    private lateinit var stale: McpServer
    private lateinit var current: McpServer

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
        // The Keystore is not there on the JVM.
        mockkObject(McpServerStore)
        every { McpServerStore.token(any()) } returns KeystoreSecretStore.Stored.Value("t")
        every { McpServerStore.headers(any()) } returns emptyMap()

        // A probe read the server, then the user moved it, and the new URL was listed.
        stale = McpServerStore.saveDetails(McpServerStore.newServer("Docs", url(before)))
        current = McpServerStore.saveDetails(stale.copy(url = url(after)))
        McpServerHealth.credentialChanged(current.id)
        McpToolCatalog.connect(current)
    }

    @After
    fun tearDown() {
        McpConnections.closeAll()
        McpToolCatalog.clear()
        before.stop()
        after.stop()
        plugin.dispose()
        unmockkObject(McpServerStore)
    }

    @Test
    fun `a listing of the old URL after the edit does not mark the server refused`() {
        assertThrows(Exception::class.java) { McpToolCatalog.connect(stale) }

        assertEquals(
            "a listing of the old URL changed the moved server's health",
            McpServerHealth.Health(McpServerHealth.State.AVAILABLE),
            McpServerHealth.of(current.id),
        )
    }

    @Test
    fun `a listing of the old URL after the edit does not replace the stored session`() {
        assertThrows(Exception::class.java) { McpToolCatalog.connect(stale) }

        assertTrue(
            "the session for the new URL was ended to store one for the old",
            after.requests.none { it.httpMethod == "DELETE" },
        )
    }

    private fun url(server: FakeMcpServer) = "http://127.0.0.1:${server.port}/mcp"
}
