package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpServerHealth
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The plugin's four listeners, registered once however often the host activates it.
 *
 * The host can activate twice without a deactivation in between. A listener added twice and
 * removed once stays registered after [McpPlugin.deactivate]: a change to the servers then tells
 * the agent the tools changed although the plugin is off, and while it is on each endpoint change
 * starts two tool listings. That the first endpoint read, which can outlive its activation, adds
 * or removes no listener is [McpPluginDebuggerStartTest]'s.
 */
class McpPluginListenerTest {

    private lateinit var plugin: McpPlugin

    @Before
    fun setUp() {
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
    }

    @After
    fun tearDown() {
        plugin.dispose()
    }

    /** Each registry's listener count, in a fixed order, so the assertions can name the registry. */
    private fun counts() = listOf(
        AutomaticServers.changeListenerCount,
        AutomaticServers.endpointListenerCount,
        McpServerStore.changeListenerCount,
        McpServerHealth.changeListenerCount,
    )

    private fun assertCounts(message: String, expected: List<Int>) {
        val names = listOf(
            "AutomaticServers change listeners",
            "AutomaticServers endpoint listeners",
            "McpServerStore change listeners",
            "McpServerHealth change listeners",
        )
        val actual = counts()
        for (i in names.indices) assertEquals("${names[i]} $message", expected[i], actual[i])
    }

    @Test
    fun `two activations register each listener once and one deactivation removes them all`() {
        val before = counts()

        plugin.activate()
        plugin.activate()
        // Counted as soon as activate returns: it registers every listener before it launches anything.
        assertCounts("after two activations", before.map { it + 1 })

        plugin.deactivate()
        assertCounts("after the deactivation", before)
    }
}
