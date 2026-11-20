package com.itsaky.androidide.plugins.aiagentmcp.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentmcp.testing.FakeSharedPreferences
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A host that activates the plugin twice and then deactivates it leaves no tool source registered.
 *
 * The second activation used to build a new tool source. The first was already registered, so the
 * new one never was, and deactivation unregistered the new one: AI Core's registry matches by
 * identity, so the first stayed registered, and it was never closed. The registry here matches the
 * same way.
 */
class DoubleActivateToolSourceTest {

    /** What the registry holds now. */
    private val registered = mutableListOf<ToolSourceRegistry.ToolSource>()

    /** Every source ever registered, in order. */
    private val everRegistered = mutableListOf<ToolSourceRegistry.ToolSource>()

    private val registry = mockk<ToolSourceRegistry>(relaxed = true) {
        every { registerToolSource(any()) } answers {
            val source = firstArg<ToolSourceRegistry.ToolSource>()
            registered.removeAll { it.providerId == source.providerId }
            registered += source
            everRegistered += source
        }
        every { unregisterToolSource(any()) } answers {
            val source = firstArg<ToolSourceRegistry.ToolSource>()
            registered.removeAll { it === source }
        }
    }

    @After
    fun tearDown() {
        SharedServices.unregister(ToolSourceRegistry::class.java)
    }

    @Test
    fun `activate, activate, deactivate leaves no tool source registered`() {
        SharedServices.register(ToolSourceRegistry::class.java, registry)
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns FakeSharedPreferences()
        val plugin = McpPlugin().also { it.initialize(context) }
        try {
            plugin.activate()
            plugin.activate()
            plugin.deactivate()

            assertEquals("tool sources still registered after deactivate", 0, registered.size)
            assertEquals("the second activation registered another source", 1, everRegistered.size)
        } finally {
            plugin.dispose()
        }
    }
}
