package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SystemPromptFactory]. It assembles a prompt out of collaborators it is handed,
 * so a fake backend and a fake IDE cover the whole order — no device, no network, no model.
 */
class SystemPromptFactoryTest {

    private val tools = listOf(ToolDefinition("read_file", "Read a file.", emptyMap()))

    /** A backend that answers with [prompt] and records what it was asked. */
    private class FakeBackend(
        private val prompt: String?,
        private val native: Boolean = false,
    ) : BackendPrompts {
        var request: SystemPromptRequest? = null
        override fun callsToolsNatively(): Boolean = native
        override fun systemPrompt(request: SystemPromptRequest): String? {
            this.request = request
            return prompt
        }
    }

    private fun factory(
        backend: BackendPrompts,
        context: IdeContext = IdeContext.EMPTY,
        session: SessionContext = SESSION,
    ) =
        SystemPromptFactory(
            config = { shippedConfig },
            ideContext = { context },
            backend = backend,
            session = { session },
            terminalTool = "respond",
            toolCallSyntax = SYNTAX,
        )

    @Test
    fun givenABackendWithItsOwnPrompt_whenCreating_thenThatWordingIsUsed() {
        // The backend knows its own model; this side only supplies the contract around it.
        val prompt = runBlocking { factory(FakeBackend("I am Gemini.")).create(tools) }

        assertTrue(prompt.startsWith("I am Gemini.\n\n"))
    }

    @Test
    fun givenABackendWithItsOwnPrompt_whenCreating_thenTheDeviceTimeIsAppended() {
        // Every backend, its own prompt or not, is told the date: none of them knows it.
        val prompt = runBlocking { factory(FakeBackend("I am Gemini.")).create(tools) }

        assertTrue(prompt.contains("Current date and time on the user's device: ${SESSION.currentTime}"))
    }

    @Test
    fun givenABackendWithItsOwnPromptOfferingWebSearch_whenCreating_thenItIsToldTheWebIsReachable() {
        val search = ToolDefinition("web_search", "Search the web.", emptyMap())
        val prompt = runBlocking { factory(FakeBackend("I am Gemini.")).create(tools + search) }

        assertTrue(prompt.contains("You can reach the internet."))
    }

    @Test
    fun givenABackendWithNoPromptOfItsOwn_whenCreating_thenTheDefaultCarriesTheDeviceTime() {
        val prompt = runBlocking { factory(FakeBackend(null)).create(tools) }

        assertTrue(prompt.contains("Current date and time on the user's device: ${SESSION.currentTime}"))
    }

    @Test
    fun givenABackendWithNoPromptOfItsOwn_whenCreating_thenTheDefaultIsUsed() {
        // A third-party `.cgp` must work without shipping prompt text.
        val prompt = runBlocking { factory(FakeBackend(null)).create(tools) }

        assertTrue(prompt.contains("You are a coding assistant inside CodeOnTheGo."))
    }

    @Test
    fun givenATextProtocolBackend_whenCreating_thenItIsAskedForTheEnvelopeToTeach() {
        val backend = FakeBackend("prompt", native = false)

        runBlocking { factory(backend).create(tools) }

        assertEquals(SYNTAX, backend.request?.toolCallSyntax)
    }

    @Test
    fun givenANativelyCallingBackend_whenCreating_thenItIsToldNoEnvelopeIsParsed() {
        // Null is how SystemPromptRequest says "the provider carries the call, not the text".
        val backend = FakeBackend("prompt", native = true)

        runBlocking { factory(backend).create(tools) }

        assertNull(backend.request?.toolCallSyntax)
    }

    @Test
    fun givenAnOpenFile_whenCreating_thenTheBackendIsGivenItAsTheExamplePath() {
        val backend = FakeBackend("prompt")
        val context = IdeContext("app/Main.kt", emptyList(), emptyList())

        runBlocking { factory(backend, context).create(tools) }

        assertEquals("app/Main.kt", backend.request?.exampleFilePath)
    }

    @Test
    fun givenAnyBackend_whenCreating_thenTheRunsToolsAreTheOnesDescribed() {
        // The same list the natively calling backend is sent, passed through unchanged.
        val backend = FakeBackend("prompt")

        runBlocking { factory(backend).create(tools) }

        assertEquals(tools, backend.request?.tools)
    }

    @Test
    fun givenAnOpenFile_whenCreating_thenTheContextBlockIsAppendedToTheBackendsPrompt() {
        val context = IdeContext("app/Main.kt", emptyList(), emptyList())

        val prompt = runBlocking { factory(FakeBackend("I am Gemini."), context).create(tools) }

        assertTrue(prompt.startsWith("I am Gemini.\n\nCurrent date and time"))
        assertTrue(prompt.contains("\n\nIDE CONTEXT"))
        assertTrue(prompt.contains("- File the user is viewing: app/Main.kt"))
    }

    private companion object {
        val SESSION = SessionContext("Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)")
        const val SYNTAX = """<tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>"""
    }
}
