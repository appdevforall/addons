package com.itsaky.androidide.plugins.aicore.backends

import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import com.itsaky.androidide.plugins.services.SharedServices
import com.itsaky.androidide.plugins.aicore.services.LlmInferenceServiceImpl
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests [BackendRegistry.selected], the one place the selection is resolved against what is
 * installed. Every surface that reports the selection — the chat's status line, its send-blocked
 * error, its availability check — branches on this, so the three cases have to stay distinct.
 *
 * The stored id is passed explicitly: no PluginContext is registered in a JVM test, so the
 * preference file cannot be written.
 */
class BackendRegistryTest {

    @Before
    fun setUp() {
        SharedServices.clear()
    }

    @After
    fun tearDown() {
        SharedServices.clear()
    }

    @Test
    fun givenNothingInstalled_whenNothingStored_thenNone() {
        installBackends()

        assertEquals(SelectedBackend.None, BackendRegistry.selected(storedId = null))
    }

    @Test
    fun givenNothingInstalled_whenSelectionStored_thenMissing() {
        installBackends()

        // Not None: the user has to reinstall the backend they chose, not pick one for the first
        // time, and those are two different instructions.
        assertEquals(SelectedBackend.Missing, BackendRegistry.selected(storedId = "gemini"))
    }

    @Test
    fun givenBackendInstalled_whenSelectionNamesAnother_thenMissing() {
        installBackends(backend("local", "Local LLM"))

        // Never Installed("local"): substituting the one that happens to be installed would hand
        // the prompt to a provider the user did not choose.
        assertEquals(SelectedBackend.Missing, BackendRegistry.selected(storedId = "gemini"))
    }

    @Test
    fun givenSelectionInstalled_whenResolved_thenInstalledNamesIt() {
        installBackends(backend("local", "Local LLM"), backend("gemini", "Gemini API"))

        val selected = BackendRegistry.selected(storedId = "gemini")

        assertTrue("expected Installed, got $selected", selected is SelectedBackend.Installed)
        assertEquals("gemini", (selected as SelectedBackend.Installed).option.id)
        assertEquals("Gemini API", selected.option.displayName)
    }

    @Test
    fun givenNothingStored_whenBackendsInstalled_thenInstalledFallsBackToDefault() {
        installBackends(backend("gemini", "Gemini API"), backend(AiBackend.DEFAULT_ID, "Local LLM"))

        val selected = BackendRegistry.selected(storedId = null)

        assertTrue("expected Installed, got $selected", selected is SelectedBackend.Installed)
        assertEquals(AiBackend.DEFAULT_ID, (selected as SelectedBackend.Installed).option.id)
    }

    private fun installBackends(vararg backends: LlmBackend) {
        val service = LlmInferenceServiceImpl()
        backends.forEach(service::registerBackend)
        SharedServices.register(LlmInferenceService::class.java, service)
    }

    private fun backend(backendId: String, displayName: String) = mockk<LlmBackend> {
        every { getId() } returns backendId
        every { getName() } returns displayName
        every { isAvailable() } returns true
    }
}
