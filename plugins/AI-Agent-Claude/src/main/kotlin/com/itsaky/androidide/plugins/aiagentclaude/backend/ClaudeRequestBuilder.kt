package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds Messages API request bodies.
 *
 * Pure — no Android types and no network — so the request shape, which is the thing the API 400s
 * over, is unit-testable.
 */
internal object ClaudeRequestBuilder {

    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"

    /**
     * Stands in for a conversation that would otherwise open on an assistant turn, which the API
     * refuses. Only reachable from a history the caller trimmed mid-exchange.
     */
    private const val LEADING_USER_TURN = "(Earlier conversation omitted.)"

    /**
     * Output budget for a streamed turn. Thinking is always on for current models and counts
     * against `max_tokens`, so the 2048 an [LlmConfig] carries by default would cut a turn off
     * mid-thought; 64K is the ceiling every current model accepts.
     */
    const val STREAMING_MAX_TOKENS = 64_000

    /** Output budget for a turn that is not streamed: kept under the HTTP read timeout. */
    const val BLOCKING_MAX_TOKENS = 16_000

    /**
     * Effort for every request to a model that takes it. Claude Opus 5.5 defaults to `medium`;
     * an agent that edits and builds a project is the workload `high` exists for.
     */
    const val EFFORT = "high"

    /**
     * The conversation split into what the Messages API takes: a top-level `system` string and a
     * `messages[]` array of alternating turns.
     *
     * @property system the system prompt plus any SYSTEM turns from the history, or null
     * @property messages the turns, opening on `user` and alternating
     */
    data class Conversation(val system: String?, val messages: JSONArray)

    /**
     * Maps the conversation onto the Messages API's shape.
     *
     * The caller hands tool results back as ordinary text — a USER turn wrapping each result, or
     * a TOOL turn — and never the assistant `tool_use` block they answer, so they are sent as user
     * text rather than as `tool_result` blocks, which the API rejects without a matching call.
     * That leaves runs of same-role turns, which are merged into one; blank turns are dropped,
     * since the API refuses an empty text block. SYSTEM turns join the top-level system prompt:
     * the API has no system role inside `messages[]` that every model accepts.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn, appended last
     * @param systemPrompt the system prompt, or null to send none
     */
    fun conversation(
        history: List<ChatMessage>,
        prompt: String,
        systemPrompt: String?,
    ): Conversation {
        val system = StringBuilder(systemPrompt?.trim().orEmpty())
        val turns = mutableListOf<Pair<String, StringBuilder>>()

        fun append(role: String, text: String) {
            if (text.isBlank()) return
            val last = turns.lastOrNull()
            if (last != null && last.first == role) {
                last.second.append("\n\n").append(text)
            } else {
                turns += role to StringBuilder(text)
            }
        }

        for (entry in history) {
            when (entry.role) {
                ChatMessage.Role.USER, ChatMessage.Role.TOOL -> append(ROLE_USER, entry.content)
                ChatMessage.Role.ASSISTANT -> append(ROLE_ASSISTANT, entry.content)
                ChatMessage.Role.SYSTEM -> if (entry.content.isNotBlank()) {
                    if (system.isNotEmpty()) system.append("\n\n")
                    system.append(entry.content.trim())
                }
            }
        }
        append(ROLE_USER, prompt)

        if (turns.firstOrNull()?.first == ROLE_ASSISTANT) {
            turns.add(0, ROLE_USER to StringBuilder(LEADING_USER_TURN))
        }
        // A conversation must end on the user: current models reject an assistant prefill. Only
        // a blank prompt after an assistant turn reaches this, and that is a caller asking for
        // nothing, so the request fails here with the reason rather than as an opaque 400.
        require(turns.lastOrNull()?.first == ROLE_USER) { "nothing to send: the last turn is not the user's" }

        val messages = JSONArray()
        for ((role, text) in turns) {
            messages.put(JSONObject().put("role", role).put("content", text.toString()))
        }
        return Conversation(system.toString().takeIf { it.isNotBlank() }, messages)
    }

    /**
     * Builds the request body for [conversation].
     *
     * Sends no `thinking` and no `temperature`: an omitted `thinking` runs each model's default,
     * and both of the alternatives are 400s on a current model — see [ClaudeModelTraits]. The
     * caller's `required_tool` is not honoured either: forcing a call with `tool_choice` is a 400
     * on current models, and the contract lets a backend that cannot force one ignore it.
     *
     * @param model the model id to request
     * @param stream true to ask for the event stream
     * @param config supplies the token cap and stop sequences
     * @param tools the tools to declare; omitted from the body when empty
     */
    fun body(
        conversation: Conversation,
        model: String,
        stream: Boolean,
        config: LlmConfig,
        tools: List<ToolDefinition> = emptyList(),
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens(config, stream))
            .put("messages", conversation.messages)
        if (stream) body.put("stream", true)
        conversation.system?.let { body.put("system", it) }

        // Declared, not described in the prompt: the input then arrives already structured, so a
        // file whose contents carry quotes or newlines can no longer break the call (ADFA-5410).
        if (tools.isNotEmpty()) body.put("tools", ClaudeToolProtocol.toolsArray(tools))

        // Caches everything up to the last turn, so an agent run re-sends its system prompt and
        // tool list at cache-read price. A prefix too short to cache is simply not cached.
        body.put("cache_control", JSONObject().put("type", "ephemeral"))

        if (ClaudeModelTraits.supportsEffort(model)) {
            body.put("output_config", JSONObject().put("effort", EFFORT))
        }
        if (ClaudeModelTraits.supportsServerFallback(model)) {
            // A safety classifier can decline a benign coding request; this re-runs it on the
            // model the API picks for that category instead of ending the turn.
            body.put("fallbacks", "default")
        }

        config.stopSequences
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { body.put("stop_sequences", JSONArray(it)) }

        return body
    }

    /**
     * The `anthropic-beta` header values [model]'s requests need, empty when none.
     *
     * Paired with [body] on purpose: a parameter sent without its beta is a 400, and so is a beta
     * named for a model that does not take it.
     */
    fun betas(model: String): List<String> =
        if (ClaudeModelTraits.supportsServerFallback(model)) {
            listOf(ClaudeModelTraits.SERVER_FALLBACK_BETA)
        } else {
            emptyList()
        }

    /**
     * The caller's cap, raised to room for thinking and held at the ceiling every model accepts.
     */
    private fun maxTokens(config: LlmConfig, stream: Boolean): Int {
        val floor = if (stream) STREAMING_MAX_TOKENS else BLOCKING_MAX_TOKENS
        return config.maxTokens.coerceIn(floor, STREAMING_MAX_TOKENS)
    }
}
