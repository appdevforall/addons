package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request shape, which is what the Messages API 400s over: turns that do not alternate, an
 * empty text block, or a parameter the model rejects.
 */
class ClaudeRequestBuilderTest {

    private fun config() = LlmConfig("claude")

    private fun roles(messages: JSONArray) =
        (0 until messages.length()).map { messages.getJSONObject(it).getString("role") }

    private fun contents(messages: JSONArray) =
        (0 until messages.length()).map { messages.getJSONObject(it).getString("content") }

    @Test
    fun givenAPlainPrompt_whenMapped_thenItIsOneUserTurnWithTheSystemPromptApart() {
        val conversation = ClaudeRequestBuilder.conversation(emptyList(), "Hi", "Be brief.")

        assertEquals("Be brief.", conversation.system)
        assertEquals(listOf("user"), roles(conversation.messages))
        assertEquals(listOf("Hi"), contents(conversation.messages))
    }

    @Test
    fun givenToolResultsAsUserTurns_whenMapped_thenConsecutiveUserTurnsAreMerged() {
        // AI Core records a native call's turn only when the model also wrote prose, so a tool
        // result routinely follows the user's own message, and the API wants roles to alternate.
        val history = listOf(
            ChatMessage(Role.USER, "Rename count"),
            ChatMessage(Role.USER, "<tool_response>read_file ok</tool_response>"),
        )
        val conversation = ClaudeRequestBuilder.conversation(history, "<tool_response>edit ok</tool_response>", null)

        assertEquals(listOf("user"), roles(conversation.messages))
        assertEquals(
            "Rename count\n\n<tool_response>read_file ok</tool_response>\n\n<tool_response>edit ok</tool_response>",
            contents(conversation.messages).single()
        )
    }

    @Test
    fun givenAToolRoleResult_whenMapped_thenItTravelsAsUserText() {
        // A tool_result block needs the assistant tool_use it answers, which history cannot carry.
        val history = listOf(
            ChatMessage(Role.USER, "List files"),
            ChatMessage(Role.ASSISTANT, "Listing."),
            ChatMessage.toolResult("toolu_1", "list_files", "app/ build.gradle.kts"),
        )
        val conversation = ClaudeRequestBuilder.conversation(history, "Thanks", null)

        assertEquals(listOf("user", "assistant", "user"), roles(conversation.messages))
        assertEquals("app/ build.gradle.kts\n\nThanks", contents(conversation.messages).last())
    }

    @Test
    fun givenBlankTurns_whenMapped_thenTheyAreDropped() {
        // The API rejects an empty text content block outright.
        val history = listOf(
            ChatMessage(Role.USER, "Hello"),
            ChatMessage(Role.ASSISTANT, "   "),
            ChatMessage(Role.USER, "Still there?"),
        )
        val conversation = ClaudeRequestBuilder.conversation(history, "Answer me", null)

        assertEquals(listOf("user"), roles(conversation.messages))
    }

    @Test
    fun givenASystemTurnInHistory_whenMapped_thenItJoinsTheSystemPrompt() {
        val history = listOf(ChatMessage(Role.SYSTEM, "The project uses Compose."))
        val conversation = ClaudeRequestBuilder.conversation(history, "Add a button", "You are an agent.")

        assertEquals("You are an agent.\n\nThe project uses Compose.", conversation.system)
        assertEquals(listOf("user"), roles(conversation.messages))
    }

    @Test
    fun givenNoSystemPrompt_whenMapped_thenNoneIsSent() {
        val conversation = ClaudeRequestBuilder.conversation(emptyList(), "Hi", "  ")

        assertNull(conversation.system)
        assertFalse(ClaudeRequestBuilder.body(conversation, "claude-opus-5-5", true, config()).has("system"))
    }

    @Test
    fun givenAHistoryOpeningOnTheAssistant_whenMapped_thenAUserTurnLeads() {
        val history = listOf(ChatMessage(Role.ASSISTANT, "Earlier answer"))
        val conversation = ClaudeRequestBuilder.conversation(history, "Follow up", null)

        assertEquals(listOf("user", "assistant", "user"), roles(conversation.messages))
    }

    @Test
    fun givenABlankPromptAfterAnAssistantTurn_whenMapped_thenItIsRefusedBeforeSending() {
        // Current models reject an assistant prefill, so a conversation ending on the assistant
        // would only come back as an opaque 400.
        val history = listOf(ChatMessage(Role.USER, "Hi"), ChatMessage(Role.ASSISTANT, "Hello"))

        assertThrows(IllegalArgumentException::class.java) {
            ClaudeRequestBuilder.conversation(history, "", null)
        }
    }

    private fun hi() = ClaudeRequestBuilder.conversation(emptyList(), "Hi", null)

    @Test
    fun givenAStreamedRequest_whenBuilt_thenTemperatureIsNeverSent() {
        // A 400 on current Opus and Sonnet models.
        val body = ClaudeRequestBuilder.body(hi(), "claude-opus-5-5", true, config())

        assertFalse(body.has("temperature"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("claude-opus-5-5", body.getString("model"))
    }

    @Test
    fun givenAnAgentTurnOnAnOpus4Model_whenBuilt_thenAdaptiveThinkingIsAskedFor() {
        // On Opus 4.x an omitted `thinking` means none: the agent would run without reasoning.
        val body = ClaudeRequestBuilder.body(hi(), "claude-opus-4-8", true, config())

        assertEquals("adaptive", body.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun givenAnAgentTurnOnHaiku_whenBuilt_thenNoThinkingIsSent() {
        // Haiku 4.5 thinks only with a budget, and adaptive is a 400 there.
        assertFalse(ClaudeRequestBuilder.body(hi(), "claude-haiku-4-5", true, config()).has("thinking"))
    }

    @Test
    fun givenASmallJob_whenBuilt_thenItGetsNoThinkingLowEffortAndTheCallersBudget() {
        // A 512-token chat title is not an agent turn; raising it to an agent's budget at high
        // effort made the next message wait behind it.
        val title = config().apply { maxTokens = 512 }
        val body = ClaudeRequestBuilder.body(hi(), "claude-opus-4-8", false, title)

        assertFalse(body.has("thinking"))
        assertEquals(ClaudeRequestBuilder.BLOCKING_EFFORT, body.getJSONObject("output_config").getString("effort"))
        assertEquals(512, body.getInt("max_tokens"))
    }

    @Test
    fun givenASmallJobOnAModelThatAlwaysThinks_whenBuilt_thenItGetsRoomToThinkFirst() {
        // Opus 5.5 thinks whatever it is asked; 512 tokens could be spent before the reply began.
        val title = config().apply { maxTokens = 512 }

        assertEquals(
            ClaudeRequestBuilder.BLOCKING_THINKING_FLOOR,
            ClaudeRequestBuilder.body(hi(), "claude-opus-5-5", false, title).getInt("max_tokens")
        )
    }

    @Test
    fun givenAnAgentTurn_whenBuilt_thenItGetsTheFullBudget() {
        // AI Core asks for 4096, which thinking alone can use up before a word of reply.
        assertEquals(
            ClaudeRequestBuilder.STREAMING_MAX_TOKENS,
            ClaudeRequestBuilder.body(hi(), "claude-opus-5-5", true, config()).getInt("max_tokens")
        )
    }

    @Test
    fun givenAModelWithASmallerCap_whenBuilt_thenTheBudgetIsHeldToIt() {
        // Opus 4 takes 32K; asking it for 64K is a 400 on every turn.
        assertEquals(32_000, ClaudeRequestBuilder.body(hi(), "claude-opus-4-20250514", true, config()).getInt("max_tokens"))
        val listed = ModelCapabilities(maxTokens = 8_192)
        assertEquals(
            8_192,
            ClaudeRequestBuilder.body(hi(), "claude-new-model", true, config(), known = listed).getInt("max_tokens")
        )
    }

    @Test
    fun givenWhatTheCatalogSaid_whenBuilt_thenItOverridesTheStaticRules() {
        // A model the static tables would give effort and thinking, listed as taking neither.
        val bare = ModelCapabilities(adaptiveThinking = false, effort = false)
        val body = ClaudeRequestBuilder.body(hi(), "claude-opus-4-8", true, config(), known = bare)

        assertFalse(body.has("thinking"))
        assertFalse(body.has("output_config"))
    }

    @Test
    fun givenAnOversizedTokenCap_whenBuilt_thenItIsHeldAtTheCeiling() {
        val conversation = ClaudeRequestBuilder.conversation(emptyList(), "Hi", null)
        val huge = config().apply { maxTokens = 1_000_000 }

        assertEquals(
            ClaudeRequestBuilder.STREAMING_MAX_TOKENS,
            ClaudeRequestBuilder.body(conversation, "claude-haiku-4-5", true, huge).getInt("max_tokens")
        )
    }

    @Test
    fun givenACurrentModel_whenBuilt_thenEffortAndFallbacksAreSentWithTheirBeta() {
        val body = ClaudeRequestBuilder.body(
            ClaudeRequestBuilder.conversation(emptyList(), "Hi", null), "claude-opus-5-5", true, config()
        )

        assertEquals(ClaudeRequestBuilder.STREAMING_EFFORT, body.getJSONObject("output_config").getString("effort"))
        assertEquals("default", body.getString("fallbacks"))
        assertEquals(listOf(ClaudeModelTraits.SERVER_FALLBACK_BETA), ClaudeRequestBuilder.betas("claude-opus-5-5"))
    }

    @Test
    fun givenHaiku_whenBuilt_thenNeitherEffortNorFallbacksAreSent() {
        // Effort is a 400 on Haiku 4.5, and so is a fallbacks parameter it does not list.
        val body = ClaudeRequestBuilder.body(
            ClaudeRequestBuilder.conversation(emptyList(), "Hi", null), "claude-haiku-4-5", true, config()
        )

        assertFalse(body.has("output_config"))
        assertFalse(body.has("fallbacks"))
        assertTrue(ClaudeRequestBuilder.betas("claude-haiku-4-5").isEmpty())
    }

    @Test
    fun givenTools_whenBuilt_thenTheyAreDeclared() {
        val tools = listOf(ToolDefinition("read_file", "Read a file", null))
        val body = ClaudeRequestBuilder.body(
            ClaudeRequestBuilder.conversation(emptyList(), "Hi", null), "claude-opus-5-5", true, config(), tools
        )

        assertEquals("read_file", body.getJSONArray("tools").getJSONObject(0).getString("name"))
    }

    @Test
    fun givenNoTools_whenBuilt_thenNoToolsKeyIsSent() {
        val body = ClaudeRequestBuilder.body(
            ClaudeRequestBuilder.conversation(emptyList(), "Hi", null), "claude-opus-5-5", true, config()
        )

        assertFalse(body.has("tools"))
    }

    @Test
    fun givenStopSequences_whenBuilt_thenOnlyTheNonEmptyOnesAreSent() {
        val withStops = config().apply { stopSequences = listOf("", "END") }
        val body = ClaudeRequestBuilder.body(
            ClaudeRequestBuilder.conversation(emptyList(), "Hi", null), "claude-opus-5-5", true, withStops
        )

        assertEquals(1, body.getJSONArray("stop_sequences").length())
        assertEquals("END", body.getJSONArray("stop_sequences").getString(0))
    }

    @Test
    fun givenAnyRequest_whenBuilt_thenThePrefixIsMarkedForCaching() {
        // An agent run re-sends the same system prompt and tool list on every turn.
        val body = ClaudeRequestBuilder.body(
            ClaudeRequestBuilder.conversation(emptyList(), "Hi", null), "claude-opus-5-5", true, config()
        )

        assertEquals("ephemeral", body.getJSONObject("cache_control").getString("type"))
    }
}
