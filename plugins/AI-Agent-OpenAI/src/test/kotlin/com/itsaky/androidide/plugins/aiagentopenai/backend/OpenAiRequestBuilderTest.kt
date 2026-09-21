package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request body. This is the shape a server 400s over, and the `messages[]` mapping is what
 * gives this backend real multi-turn fidelity rather than a flattened transcript.
 */
class OpenAiRequestBuilderTest {

    private fun config(
        temperature: Float = 0.7f,
        maxTokens: Int = 4096,
        systemPrompt: String? = null,
    ) = LlmConfig("openai").apply {
        this.temperature = temperature
        this.maxTokens = maxTokens
        this.systemPrompt = systemPrompt
    }

    private val defaultTuning = RequestTuning(RequestTuning.MAX_TOKENS, sendTemperature = true)

    @Test
    fun givenNoHistory_whenBuildingMessages_thenOnlyThePromptIsSent() {
        val messages = OpenAiRequestBuilder.messages(emptyList(), "Hello", null)
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("Hello", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun givenASystemPrompt_whenBuildingMessages_thenItLeadsAsARealSystemTurn() {
        val messages = OpenAiRequestBuilder.messages(emptyList(), "Hi", "You are helpful.")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("You are helpful.", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun givenABlankSystemPrompt_whenBuildingMessages_thenNoSystemTurnIsSent() {
        val messages = OpenAiRequestBuilder.messages(emptyList(), "Hi", "   ")
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun givenHistory_whenBuildingMessages_thenRolesArePreservedInOrder() {
        val history = listOf(
            ChatMessage(ChatMessage.Role.USER, "first"),
            ChatMessage(ChatMessage.Role.ASSISTANT, "reply"),
            ChatMessage(ChatMessage.Role.SYSTEM, "note"),
        )
        val messages = OpenAiRequestBuilder.messages(history, "latest", null)

        assertEquals(4, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("first", messages.getJSONObject(0).getString("content"))
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        // A real system role, unlike the Gemini transport which must fake one with a user turn.
        assertEquals("system", messages.getJSONObject(2).getString("role"))
        assertEquals("latest", messages.getJSONObject(3).getString("content"))
    }

    @Test
    fun givenAToolResult_whenBuildingMessages_thenItRidesInAsAUserTurn() {
        // A `tool` role is only legal after an assistant turn carrying the matching `tool_calls`,
        // which ChatMessage gives the assistant turn no way to hold.
        val history = listOf(ChatMessage.toolResult("call_1", "read_file", "file contents"))
        val messages = OpenAiRequestBuilder.messages(history, "next", null)

        assertEquals("user", messages.getJSONObject(0).getString("role"))
        assertEquals("file contents", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun givenStreamingRequested_whenBuildingTheBody_thenStreamIsTrue() {
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "gpt-4o",
            stream = true,
            config = config(),
            tuning = defaultTuning,
        )
        assertTrue(body.getBoolean("stream"))
        assertEquals("gpt-4o", body.getString("model"))
    }

    @Test
    fun givenTheLegacyTuning_whenBuildingTheBody_thenMaxTokensCarriesTheCap() {
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "qwen2.5-coder",
            stream = false,
            config = config(maxTokens = 2048),
            tuning = defaultTuning,
        )
        assertEquals(2048, body.getInt(RequestTuning.MAX_TOKENS))
        assertFalse(body.has(RequestTuning.MAX_COMPLETION_TOKENS))
    }

    @Test
    fun givenAReasoningTuning_whenBuildingTheBody_thenTemperatureIsAbsentAndTheModernCapIsUsed() {
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "gpt-5",
            stream = true,
            config = config(),
            tuning = RequestTuning(RequestTuning.MAX_COMPLETION_TOKENS, sendTemperature = false),
        )
        assertEquals(4096, body.getInt(RequestTuning.MAX_COMPLETION_TOKENS))
        assertFalse("temperature must be omitted for a reasoning model", body.has("temperature"))
        assertFalse(body.has(RequestTuning.MAX_TOKENS))
    }

    @Test
    fun givenNoTokenCap_whenBuildingTheBody_thenNoCapIsSentAtAll() {
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "gpt-4o",
            stream = false,
            config = config(maxTokens = 0),
            tuning = defaultTuning,
        )
        assertFalse(body.has(RequestTuning.MAX_TOKENS))
        assertFalse(body.has(RequestTuning.MAX_COMPLETION_TOKENS))
    }

    @Test
    fun givenStopSequences_whenBuildingTheBody_thenTheyAreSent() {
        val withStops = config().apply { stopSequences = listOf("</tool_call>", "") }
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "gpt-4o",
            stream = false,
            config = withStops,
            tuning = defaultTuning,
        )
        // The empty entry is dropped: an empty stop string ends every generation immediately.
        assertEquals(1, body.getJSONArray("stop").length())
        assertEquals("</tool_call>", body.getJSONArray("stop").getString(0))
    }

    @Test
    fun givenOnlyEmptyStopSequences_whenBuildingTheBody_thenNoStopIsSent() {
        val withStops = config().apply { stopSequences = listOf("") }
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "gpt-4o",
            stream = false,
            config = withStops,
            tuning = defaultTuning,
        )
        assertFalse(body.has("stop"))
    }

    @Test
    fun givenTools_whenBuildingTheBody_thenTheyAreDeclaredToTheServer() {
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "gpt-4o",
            stream = true,
            config = config(),
            tuning = defaultTuning,
            tools = listOf(ToolDefinition("read_file", "Read a file", emptyMap())),
        )

        val declared = body.getJSONArray("tools")
        assertEquals(1, declared.length())
        assertEquals(
            "read_file",
            declared.getJSONObject(0).getJSONObject("function").getString("name")
        )
    }

    @Test
    fun givenNoTools_whenBuildingTheBody_thenNoToolsFieldIsSent() {
        // Plain chat, and a server that rejects an empty `tools` array would 400 on the request.
        val body = OpenAiRequestBuilder.body(
            OpenAiRequestBuilder.messages(emptyList(), "Hi", null),
            "gpt-4o",
            stream = true,
            config = config(),
            tuning = defaultTuning,
        )

        assertFalse(body.has("tools"))
    }
}
