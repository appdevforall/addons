package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The health probe must not refresh or re-publish the automatic entries once its activation is
 * over: deactivation has cleared them, and a refresh that outlived it would restore them.
 */
class McpPluginProbeHealthTest {

    private lateinit var plugin: McpPlugin
    private val reads = AtomicInteger()
    private val active = AtomicBoolean(true)

    @Before
    fun setUp() {
        AutomaticServers.install(null)
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        plugin = McpPlugin().also { it.initialize(context) }
    }

    @After
    fun tearDown() {
        plugin.dispose()
        AutomaticServers.install(null)
    }

    private var current = emptyList<AutomaticEndpoint>()
    private var onRead: () -> Unit = {}

    private fun installSource() {
        AutomaticServers.install({
            reads.incrementAndGet()
            onRead()
            current
        })
    }

    @Test
    fun `an activation that ends during the delay refreshes nothing`() {
        installSource()
        current = listOf(AutomaticEndpoint("com.example.app", 4000, "t1"))
        val before = reads.get()
        // The loop condition sees a live activation; the check after the delay sees it ended.
        val asked = AtomicInteger()

        runBlocking { plugin.probeHealth({ asked.incrementAndGet() == 1 }, intervalMs = 10) }

        assertEquals(before, reads.get())
        assertTrue(AutomaticServers.servers().isEmpty())
    }

    @Test
    fun `an activation that ends during the refresh read stores nothing`() {
        installSource()
        current = listOf(AutomaticEndpoint("com.example.app", 4000, "t1"))
        onRead = { active.set(false) }

        runBlocking { plugin.probeHealth({ active.get() }, intervalMs = 10) }

        assertTrue(AutomaticServers.servers().isEmpty())
    }
}
