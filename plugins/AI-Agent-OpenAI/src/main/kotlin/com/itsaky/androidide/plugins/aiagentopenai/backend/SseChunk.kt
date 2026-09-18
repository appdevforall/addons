package com.itsaky.androidide.plugins.aiagentopenai.backend

import org.json.JSONObject

/**
 * Reads one line of an OpenAI-compatible SSE stream.
 *
 * Pure, so the framing — which is where a stream silently truncates or throws — is unit-testable
 * without a server. Every compatible server emits the same `data: {json}` / `data: [DONE]` shape,
 * but they disagree about what a chunk may carry beyond `delta.content`, and a chunk this parser
 * does not understand is a reply the user never sees.
 */
internal object SseChunk {

    private const val DATA_PREFIX = "data:"
    private const val DONE_PAYLOAD = "[DONE]"

    /** Longest slice of an unrecognised payload kept for the log; bodies can be large. */
    private const val MAX_LOGGED_PAYLOAD = 200

    /** What one SSE line means to the reader loop. */
    sealed interface Event {

        /** Visible reply text to append and hand to the caller. */
        data class Token(val text: String) : Event

        /**
         * Fragments of one or more native tool calls, for [OpenAiToolProtocol.CallAccumulator].
         *
         * @property deltas the chunk's `tool_calls` fragments.
         * @property text prose the same chunk carried, which a call riding along must not drop.
         */
        data class ToolCalls(
            val deltas: List<OpenAiToolProtocol.ToolCallDelta>,
            val text: String,
        ) : Event

        /**
         * Thinking text, which is **not** part of the reply.
         *
         * Tracked rather than discarded: a model that spends its whole token budget reasoning
         * produces a stream that is empty of content but far from empty, and saying "the request
         * failed" there sends the user looking for a network problem that does not exist.
         */
        data class Reasoning(val text: String) : Event

        /**
         * The server reported a failure inside a 200 response.
         *
         * LM Studio and several others answer `stream: true` with HTTP 200 and then put the real
         * error in the stream — context overflow, model unloaded. Without this the stream just
         * ends empty.
         */
        data class Failure(val message: String) : Event

        /** The turn ended for [reason], e.g. `length` when the token cap truncated it. */
        data class Finish(val reason: String) : Event

        /** The server said the stream is over; stop reading. */
        data object Done : Event

        /** Nothing to do: a comment, a keep-alive, a blank line, or an empty delta. */
        data object Ignored : Event

        /**
         * The payload could not be used. [detail] is for the log, never for the transcript.
         *
         * Covers both unparseable JSON and a well-formed chunk in a shape this parser does not
         * recognise — the second is what makes a silently empty reply diagnosable.
         */
        data class Malformed(val detail: String) : Event
    }

    /**
     * Classifies [line].
     *
     * A malformed payload is reported rather than thrown: one bad chunk must not abort a stream
     * that is otherwise producing tokens.
     *
     * @param line one raw line from the response body
     * @return what the reader loop should do with it
     */
    fun parse(line: String): Event {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith(DATA_PREFIX)) return Event.Ignored

        val payload = trimmed.substringAfter(DATA_PREFIX).trim()
        if (payload.isEmpty()) return Event.Ignored
        if (payload == DONE_PAYLOAD) return Event.Done

        val json = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return Event.Malformed("unparseable payload: ${e.message}")
        }

        // Checked before choices: an error chunk carries no usable content.
        errorMessageOf(json)?.let { return Event.Failure(it) }

        val choices = json.optJSONArray("choices")
        // A usage-only or otherwise choice-less chunk is normal; an unrecognised one is not, and
        // being told about it is the difference between diagnosing an empty reply and guessing.
        if (choices == null) {
            return if (json.has("usage")) {
                Event.Ignored
            } else {
                Event.Malformed("no choices in payload: ${payload.take(MAX_LOGGED_PAYLOAD)}")
            }
        }

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<OpenAiToolProtocol.ToolCallDelta>()
        var finishReason: String? = null
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            // `message` covers a server that ignores stream:true and answers in one shot.
            val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message")
            content.append(delta?.optString("content").orEmpty())
            reasoning.append(reasoningOf(delta))
            toolCalls += OpenAiToolProtocol.toolCallDeltas(delta)
            choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
                ?.let { finishReason = it }
        }

        return when {
            // Ahead of the text, which rides along: a call delta carries no `content`, so a chunk
            // of them was `Ignored` before native calling and the turn came back empty.
            toolCalls.isNotEmpty() -> Event.ToolCalls(toolCalls, content.toString())
            content.isNotEmpty() -> Event.Token(content.toString())
            reasoning.isNotEmpty() -> Event.Reasoning(reasoning.toString())
            finishReason != null -> Event.Finish(finishReason)
            else -> Event.Ignored
        }
    }

    /**
     * Thinking text from whichever field this server uses.
     *
     * `reasoning_content` is the DeepSeek/LM Studio spelling and `reasoning` the OpenRouter one;
     * both appear in the wild on the same endpoint shape.
     */
    private fun reasoningOf(delta: JSONObject?): String {
        if (delta == null) return ""
        return delta.optString("reasoning_content").ifEmpty { delta.optString("reasoning") }
    }

    /**
     * The server's error text, when the chunk is an error rather than a completion.
     *
     * Accepts both `{"error":{"message":…}}` and a bare `{"error":"…"}`, which compatible servers
     * use interchangeably.
     */
    private fun errorMessageOf(json: JSONObject): String? {
        if (!json.has("error")) return null
        json.optJSONObject("error")?.let { error ->
            return error.optString("message").takeIf { it.isNotBlank() }
                ?: error.toString().take(MAX_LOGGED_PAYLOAD)
        }
        return json.optString("error").takeIf { it.isNotBlank() }
            ?: "the server reported an unspecified error"
    }
}
