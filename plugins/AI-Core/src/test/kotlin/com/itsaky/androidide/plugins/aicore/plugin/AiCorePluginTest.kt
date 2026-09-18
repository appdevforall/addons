package com.itsaky.androidide.plugins.aicore.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class AiCorePluginTest {

    private lateinit var mockLogger: PluginLogger
    private lateinit var mockContext: PluginContext

    @Before
    fun setup() {
        // AiCorePlugin publishes its service into the process-global SharedServices,
        // so start from a clean slate to avoid leakage between tests.
        SharedServices.clear()
        mockLogger = mockk(relaxed = true)
        mockContext = mockk(relaxed = true) {
            every { logger } returns mockLogger
        }
    }

    @After
    fun tearDown() {
        SharedServices.clear()
    }

    @Test
    fun givenAContext_whenInitializing_thenItSucceedsAndKeepsThatContext() {
        val plugin = AiCorePlugin()
        val result = plugin.initialize(mockContext)

        assertTrue(result)
        // Held for this plugin's own fragments to reach.
        assertSame(mockContext, AiCorePlugin.getContext())
    }

    @Test
    fun givenInitialized_whenInspectingSharedServices_thenNoPluginContextIsPublished() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)

        // Backends used to read their own settings out of this plugin's preferences, reached
        // through a globally published PluginContext — a registry keyed by type, so whichever
        // plugin registered last won. Each backend now owns its settings; publishing this again
        // would re-create that coupling.
        assertNull(SharedServices.get(PluginContext::class.java))
    }

    @Test
    fun givenInitialized_whenActivating_thenItSucceeds() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)

        assertTrue(plugin.activate())
    }

    @Test
    fun givenActivated_whenDeactivating_thenTheRouterIsWithdrawn() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        plugin.activate()

        assertTrue(plugin.deactivate())
        // Leaving it published would hand consumers a router whose backends have all gone.
        assertNull(SharedServices.get(LlmInferenceService::class.java))
    }

    @Test
    fun testServiceRegistration() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        plugin.activate()

        // activate() registers the LlmInferenceService into SharedServices for other plugins.
        val service = SharedServices.get(LlmInferenceService::class.java)
        assertNotNull(service)
    }

    @Test
    fun givenActivated_whenInspectingBackends_thenNoneAreOwnedByAiCore() {
        val plugin = AiCorePlugin()
        plugin.initialize(mockContext)
        plugin.activate()

        // AI Core contributes no backend of its own; ai-agent-local and ai-agent-gemini
        // register themselves. A backend appearing here means that split regressed.
        val service = SharedServices.get(LlmInferenceService::class.java)
        assertNotNull(service)
        assertTrue(service!!.getAvailableBackends().isEmpty())
    }
}
