package com.itsaky.androidide.plugins.aicore.plugin

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import com.itsaky.androidide.plugins.services.SharedServices
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import java.util.concurrent.CompletableFuture

/**
 * Integration test for the AI Core Plugin workflow: publish the router, let a backend plugin
 * register into it, route a request, and tear everything down.
 */
class AiCoreIntegrationTest {

    private lateinit var plugin: AiCorePlugin
    private lateinit var context: PluginContext

    @Before
    fun setup() {
        // The plugin registers into the process-global SharedServices; clear it so the
        // workflow starts from a known-empty state and can't inherit another test's service.
        SharedServices.clear()
        val mockLogger = mockk<PluginLogger>(relaxed = true)
        context = mockk(relaxed = true) {
            every { logger } returns mockLogger
        }
        plugin = AiCorePlugin()
    }

    @After
    fun teardown() {
        SharedServices.clear()
    }

    /** Stands in for a backend plugin's backend, which is what registers with AI Core now. */
    private fun unavailableBackend(backendId: String) = mockk<LlmBackend> {
        every { getId() } returns backendId
        every { getName() } returns backendId
        every { isAvailable() } returns false
        every { generate(any(), any()) } returns CompletableFuture.completedFuture(
            LlmResponse.success("unreachable", 0, 0)
        )
    }

    @Test
    fun testCompletePluginWorkflow() {
        // Step 1: Initialize plugin
        val initSuccess = plugin.initialize(context)
        assertTrue("Plugin initialization should succeed", initSuccess)

        // Step 2: Activate plugin (publishes the router; contributes no backend)
        val activateSuccess = plugin.activate()
        assertTrue("Plugin activation should succeed", activateSuccess)

        // Step 3: Retrieve LlmInferenceService published to SharedServices
        val service = SharedServices.get(LlmInferenceService::class.java)
        assertNotNull("LlmInferenceService should be registered", service)

        // Step 4: AI Core owns no backend of its own
        assertTrue("AI Core should register no backends", service!!.getAvailableBackends().isEmpty())

        // Step 5: A backend plugin registers itself, exactly as ai-agent-local does
        service.registerBackend(unavailableBackend("local"))
        assertEquals("Backend should be registered", 1, service.getAvailableBackends().size)
        assertNotNull("Registered backend should be resolvable", service.getBackend("local"))
        assertFalse("Local backend should not be available", service.isBackendAvailable("local"))

        // Step 6: Attempt generation with an unavailable backend
        val config = LlmConfig("local")
        config.temperature = 0.7f
        config.maxTokens = 100

        val response = service.generateCompletion("Write a hello world function", config).get()

        assertFalse("Response should fail (backend unavailable)", response.success)
        assertNotNull("Error message should be present", response.error)
        assertTrue("Error should mention availability", response.error!!.contains("not available"))

        // Step 7: Deactivate plugin
        val deactivateSuccess = plugin.deactivate()
        assertTrue("Plugin deactivation should succeed", deactivateSuccess)

        // Step 8: The router is withdrawn, so nothing can reach a backend through it
        assertNull(
            "LlmInferenceService should be unregistered",
            SharedServices.get(LlmInferenceService::class.java)
        )
    }
}
