package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds `chat/completions` request bodies.
 *
 * Pure — no Android types and no network — so the request shape, which is the thing a server 400s
 * over, is unit-testable. `chat/completions` and not `responses`: it is the protocol every
 * compatible server implements, which is the whole point of one backend for all of them.
 */
internal object OpenAiRequestBuilder {

    private const val ROLE_SYSTEM = "system"
    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"

    /**
     * Maps the conversation onto a real `messages[]` array.
     *
     * The system prompt leads as its own `system` turn — unlike the Gemini transport, which has no
     * system role and fakes one with a user turn.
     *
     * @param history the conversation so far, oldest first
     * @param prompt the current user turn, appended last
     * @param systemPrompt the system prompt, or null to send none
     * @return the `messages[]` array
     */
    fun messages(
        history: List<ChatMessage>,
        prompt: String,
        systemPrompt: String?,
    ): JSONArray {
        val messages = JSONArray()
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            messages.put(message(ROLE_SYSTEM, it))
        }
        for (entry in history) {
            val role = when (entry.role) {
                ChatMessage.Role.USER -> ROLE_USER
                ChatMessage.Role.ASSISTANT -> ROLE_ASSISTANT
                ChatMessage.Role.SYSTEM -> ROLE_SYSTEM
                // A `tool` role is only legal after an assistant turn carrying the matching
                // `tool_calls`, which ChatMessage gives the assistant turn no way to hold.
                ChatMessage.Role.TOOL -> ROLE_USER
            }
            messages.put(message(role, entry.content))
        }
        messages.put(message(ROLE_USER, prompt))
        return messages
    }

    /**
     * Builds the request body for [messages].
     *
     * @param model the model id to request
     * @param stream true to ask for the SSE token stream
     * @param config supplies the token cap and temperature
     * @param tuning decides which optional parameters are sent at all
     * @param tools the tools to declare; omitted from the body when empty
     * @return the request JSON
     */
    fun body(
        messages: JSONArray,
        model: String,
        stream: Boolean,
        config: LlmConfig,
        tuning: RequestTuning,
        tools: List<ToolDefinition> = emptyList(),
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", stream)

        // Declared, not described in the prompt: the arguments then arrive already structured, so
        // a file whose contents carry quotes or newlines can no longer break the call (ADFA-5410).
        if (tools.isNotEmpty()) {
            body.put("tools", OpenAiToolProtocol.toolsArray(tools))
        }

        if (config.maxTokens > 0) {
            body.put(tuning.tokenParam, config.maxTokens)
        }
        if (tuning.sendTemperature) {
            body.put(RequestTuning.TEMPERATURE, config.temperature.toDouble())
        }
        config.stopSequences
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { body.put("stop", JSONArray(it)) }

        return body
    }

    /** One `{role, content}` turn. */
    private fun message(role: String, content: String): JSONObject =
        JSONObject().put("role", role).put("content", content)
}
