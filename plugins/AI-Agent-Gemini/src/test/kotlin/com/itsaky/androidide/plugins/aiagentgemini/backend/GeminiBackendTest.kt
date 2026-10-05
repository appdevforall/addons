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
        backend = GeminiBackend(mockk(relaxed = true)) { null }
    }

    @Test
    fun givenConfigNotYetLoaded_whenAskedForItsPrompt_thenItReturnsNullInsteadOfBlocking() {
        // Null is the contract's "no prompt of my own": ai-core then sends its default prompt.
        assertNull(backend.getSystemPrompt(SystemPromptRequest(emptyList(), null, "app/Main.kt")))
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
        )

        val roles = (0 until contents.length()).map { contents.getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "model", "user"), roles)
        assertEquals(
            "hello",
            contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"),
        )
    }

    @Test
    fun givenASystemPrompt_whenBuildingTheRequest_thenItIsSentAsSystemInstruction() {
        // As a user turn it carried no more weight than text read out of a file (ADFA-6223).
        val config = LlmConfig("gemini").apply { systemPrompt = "be brief" }

        val body = backend.buildRequestJson(backend.buildContents(emptyList(), "hi"), config)

        assertEquals(
            "be brief",
            body.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text"),
        )
    }

    @Test
    fun givenASystemPrompt_whenBuildingTheRequest_thenTheCallersContentsAreLeftUntouched() {
        // The caller keeps and reuses the array; the old shape prepended the prompt and an
        // "Understood." turn the model never produced, and both landed in the next request too.
        val config = LlmConfig("gemini").apply { systemPrompt = "be brief" }
        val contents = backend.buildContents(emptyList(), "hi")

        assertEquals(1, contents.length())
        backend.buildRequestJson(contents, config)

        assertEquals(1, contents.length())
        val turn = contents.getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals("hi", turn.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun givenNoSystemPrompt_whenBuildingTheRequest_thenTheFieldIsLeftOff() {
        // An empty systemInstruction is a 400 from the API, so a blank prompt must omit the field.
        val blank = backend.buildRequestJson(
            backend.buildContents(emptyList(), "hi"),
            LlmConfig("gemini").apply { systemPrompt = "   " },
        )
        val absent = backend.buildRequestJson(backend.buildContents(emptyList(), "hi"), LlmConfig("gemini"))

        assertFalse(blank.has("systemInstruction"))
        assertFalse(absent.has("systemInstruction"))
    }

    @Test
    fun givenARequiredDeclaredTool_whenBuildingTheRequest_thenGeminiIsMadeToCallOnlyIt() {
        // Left to itself the model approved removed APIs without searching (ADFA-6223).
        val config = LlmConfig("gemini").apply { extraParams = mapOf("required_tool" to "web_search") }

        val body = backend.buildRequestJson(backend.buildContents(emptyList(), "review"), config, SEARCH_TOOLS)

        val calling = body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig")
        assertEquals("ANY", calling.getString("mode"))
        assertEquals("web_search", calling.getJSONArray("allowedFunctionNames").getString(0))
        assertEquals(1, calling.getJSONArray("allowedFunctionNames").length())
    }

    @Test
    fun givenARequiredToolThatIsNotDeclared_whenBuildingTheRequest_thenNoToolConfigIsSent() {
        // Gemini refuses the whole request over an allowed name it was not given.
        val config = LlmConfig("gemini").apply { extraParams = mapOf("required_tool" to "fetch_url") }

        val body = backend.buildRequestJson(backend.buildContents(emptyList(), "review"), config, SEARCH_TOOLS)

        assertFalse(body.has("toolConfig"))
    }

    @Test
    fun givenNoRequiredTool_whenBuildingTheRequest_thenTheModelChoosesFreely() {
        val body = backend.buildRequestJson(backend.buildContents(emptyList(), "hi"), LlmConfig("gemini"), SEARCH_TOOLS)

        assertFalse(body.has("toolConfig"))
    }

    private companion object {
        val SEARCH_TOOLS = listOf(
            ToolDefinition("web_search", "Search the web", emptyMap()),
            ToolDefinition("respond", "Reply", emptyMap()),
        )
    }
}
