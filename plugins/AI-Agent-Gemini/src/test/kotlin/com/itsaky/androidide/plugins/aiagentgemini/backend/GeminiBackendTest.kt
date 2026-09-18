package com.itsaky.androidide.plugins.aiagentgemini.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.*
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GeminiBackendTest {

    private lateinit var backend: GeminiBackend

    @Before
    fun setup() {
        backend = GeminiBackend(mockk(relaxed = true))
    }

    @Test
    fun givenTheBackend_whenAskedForItsIdentity_thenItRegistersAsGemini() {
        assertEquals("gemini", backend.getId())
        assertEquals("Gemini API", backend.getName())
    }

    @Test
    fun givenTheBackend_whenAskedForItsCapabilities_thenItDeclaresBothHistoryAndToolCalling() {
        // Dropping either compiles and degrades silently: history turns chat into one-shot
        // prompting, and tool calling drops the agent back to parsing calls out of the reply text.
        val declared: LlmBackend = backend

        assertTrue(declared is HistoryCapableBackend)
        assertTrue(declared is ToolCallingBackend)
    }

    @Test
    fun givenAdjacentUserTurns_whenBuildingContents_thenTheyAreMergedIntoOne() {
        // The agent loop stores no ASSISTANT turn for a native call with no prose beside it, so
        // the user message and the tool results it produced arrive adjacent. Sent as two user
        // contents they break Gemini's alternation; merged, the request stays well-formed.
        val contents = backend.buildContents(
            history = listOf(
                ChatMessage(ChatMessage.Role.USER, "add a dependency"),
                ChatMessage(ChatMessage.Role.USER, "Tool add_dependency: ok"),
            ),
            prompt = "Tool sync_project: ok",
            config = LlmConfig("gemini"),
        )

        assertEquals(1, contents.length())
        val turn = contents.getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals(
            "add a dependency\n\nTool add_dependency: ok\n\nTool sync_project: ok",
            turn.getJSONArray("parts").getJSONObject(0).getString("text"),
        )
    }

    @Test
    fun givenAlternatingTurns_whenBuildingContents_thenEachStaysItsOwnContent() {
        val contents = backend.buildContents(
            history = listOf(
                ChatMessage(ChatMessage.Role.USER, "hello"),
                ChatMessage(ChatMessage.Role.ASSISTANT, "hi"),
            ),
            prompt = "how are you?",
            config = LlmConfig("gemini").apply { systemPrompt = "be brief" },
        )

        val roles = (0 until contents.length()).map { contents.getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "model", "user", "model", "user"), roles)
        assertEquals(
            "be brief",
            contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"),
        )
    }
}
