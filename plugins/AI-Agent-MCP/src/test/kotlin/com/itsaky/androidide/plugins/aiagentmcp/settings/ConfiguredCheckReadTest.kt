package com.itsaky.androidide.plugins.aiagentmcp.settings

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.debugger.McpServers
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The check that a server is still configured, made under the health, catalog and session locks,
 * reads no preferences, and so parses no stored JSON there.
 *
 * It read the stored server list and parsed every server's JSON on each call, under those locks; a
 * SQLite debugger entry's refresh, which waits on two of them while holding its own, waited behind
 * that read. The store now answers from the list it last read or wrote.
 */
class ConfiguredCheckReadTest {

    private val prefs = spyk(FakeSharedPreferences())
    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns prefs
        plugin = McpPlugin().also { it.initialize(context) }
    }

    @After
    fun tearDown() {
        plugin.dispose()
    }

    @Test
    fun `the configured check reads no preferences`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("Docs", "http://127.0.0.1:1/mcp"))
        clearMocks(prefs, answers = false, recordedCalls = true, childMocks = false)

        assertTrue(McpServers.isConfigured(saved))
        assertFalse(McpServers.isConfigured(saved.copy(url = "http://127.0.0.1:2/mcp")))

        verify(exactly = 0) { prefs.getString(any(), any()) }
    }

    @Test
    fun `the list it answers from is the one last written`() {
        val saved = McpServerStore.saveDetails(McpServerStore.newServer("", "http://127.0.0.1:1/mcp"))
        val moved = McpServerStore.saveDetails(saved.copy(url = "http://127.0.0.1:2/mcp"))

        assertTrue(McpServers.isConfigured(moved))
        assertFalse(McpServers.isConfigured(saved))
        // As read back from the preferences: a blank name is the URL.
        assertEquals("http://127.0.0.1:2/mcp", McpServerStore.server(saved.id)?.name)
        McpServerStore.remove(saved.id)
        assertFalse(McpServers.isConfigured(moved))
    }
}
