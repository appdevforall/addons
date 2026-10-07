package com.itsaky.androidide.plugins.aiagentclaude.backend

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentclaude.preferences.ClaudePreferences
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.services.LlmInferenceService.ActiveModelReportingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.CancellableBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.HistoryCapableBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallingBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this backend declares to the caller, and what it answers from its prompt config and model
 * setting. Each interface is optional, so dropping one compiles and degrades silently.
 */
class ClaudeBackendTest {

    private val backend = ClaudeBackend(mockk(relaxed = true)) { null }

    @Test
    fun givenConfigNotYetLoaded_whenAskedForItsPrompt_thenItReturnsNullInsteadOfBlocking() {
        // Null is the contract's "no prompt of my own": ai-core then sends its default prompt.
        assertNull(backend.getSystemPrompt(SystemPromptRequest(emptyList(), null, "app/Main.kt")))
    }

    @Test
    fun givenTheBackend_whenAskedForItsIdentity_thenItRegistersAsClaude() {
        assertEquals("claude", backend.getId())
    }

    @Test
    fun givenTheBackend_whenAskedForItsCapabilities_thenItDeclaresToolCallingToo() {
        // Dropping ToolCallingBackend drops the agent back to parsing calls out of the reply text,
        // and takes the prompt's envelope instructions with it (ADFA-5410).
        val declared: LlmBackend = backend

        assertTrue(declared is HistoryCapableBackend)
        assertTrue(declared is CancellableBackend)
        assertTrue(declared is ToolCallingBackend)
    }

    @Test
    fun givenTheBackend_whenAskedForItsCapabilities_thenItReportsItsActiveModel() {
        // Without it the Agent's backend tag names the backend but never the model it talks to.
        val declared: LlmBackend = backend

        assertTrue(declared is ActiveModelReportingBackend)
    }

    @Test
    fun givenALoadedConfig_whenAskedForItsPrompt_thenItRendersTheShippedFiles() {
        val loaded = ClaudeBackend(mockk(relaxed = true)) { shippedConfig }
        val tools = listOf(ToolDefinition("read_file", "Read a file", emptyMap()))

        val prompt = loaded.getSystemPrompt(SystemPromptRequest(tools, null, "app/Main.kt"))

        assertNotNull(prompt)
        assertTrue(prompt!!.contains("- read_file: Read a file"))
        assertTrue(prompt.contains("TOOL CALL FORMAT — the tools above are declared to you"))
    }

    @Test
    fun givenAConfigThatCannotRender_whenAskedForItsPrompt_thenItFallsBackToNull() {
        // A typo that slipped past activation must cost the prompt, not the whole chat turn.
        val broken = shippedWith("layout.yml") { it.replace("{{IDENTITY}}", "{{IDENTITTY}}") }
        val backend = ClaudeBackend(mockk(relaxed = true)) { broken }

        assertNull(backend.getSystemPrompt(SystemPromptRequest(emptyList(), null, "app/Main.kt")))
    }

    @Test
    fun givenAStoredModel_whenAskedForTheActiveModel_thenItIsThatIdTrimmed() {
        assertEquals("claude-sonnet-5-5", backendWithStoredModel(" claude-sonnet-5-5 ").getActiveModelName())
    }

    @Test
    fun givenNoStoredModel_whenAskedForTheActiveModel_thenItIsTheDefault() {
        // A blank field must not reach the backend tag as an unnamed model.
        assertEquals(ClaudeBackend.DEFAULT_MODEL, backendWithStoredModel("  ").getActiveModelName())
    }

    private fun backendWithStoredModel(model: String): ClaudeBackend {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(ClaudePreferences.KEY_MODEL, any()) } returns model
        val context = mockk<PluginContext>(relaxed = true)
        every { context.getPluginSharedPreferences(any()) } returns prefs
        return ClaudeBackend(context) { null }
    }
}
