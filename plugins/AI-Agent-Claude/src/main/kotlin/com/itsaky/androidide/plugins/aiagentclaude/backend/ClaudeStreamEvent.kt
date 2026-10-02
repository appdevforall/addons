package com.itsaky.androidide.plugins.aiagentclaude.backend

import org.json.JSONException
import org.json.JSONObject

/**
 * One line of a Messages API event stream, reduced to what the backend acts on.
 *
 * The stream frames each event as an `event:` line and a `data:` line; the `data` JSON repeats the
 * event name as its `type`, so only `data:` lines are read and the rest are [Ignored]. Pure, so the
 * shapes that decide what reaches the transcript are unit-testable from captured lines.
 */
internal sealed interface ClaudeStreamEvent {

    /** `message_start`: names the model actually serving the turn, which a fallback can change. */
    data class Started(val model: String?) : ClaudeStreamEvent

    /** A `text_delta`: reply text, shown as it arrives. */
    data class Text(val text: String) : ClaudeStreamEvent

    /** A `tool_use` block opened at [index]; its input follows as [ToolInput] fragments. */
    data class ToolStart(val index: Int, val id: String, val name: String) : ClaudeStreamEvent

    /** One `input_json_delta` slice of the `tool_use` block at [index]. */
    data class ToolInput(val index: Int, val partialJson: String) : ClaudeStreamEvent

    /**
     * Any block other than `tool_use` or `fallback` opened at [index]: `text`, `thinking`, or a type
     * added later. Tracked so the turn knows which block a `max_tokens` stop cut off; a `thinking`
     * block is never shown, but is proof the model was working when a turn ends with no text.
     *
     * @property type the block's `type`
     * @property text text the block opened with, which for a `text` block is reply text
     */
    data class BlockOpened(val index: Int, val type: String, val text: String = "") : ClaudeStreamEvent

    /**
     * A `fallback` block at [index]: a safety classifier declined the requested model and the
     * server moved the turn to [toModel]. Text already streamed stays valid; a tool call the
     * declined model began does not.
     */
    data class FallbackSwitch(val index: Int, val toModel: String?) : ClaudeStreamEvent

    /** `message_delta` carrying why generation stopped (`end_turn`, `tool_use`, `max_tokens`, ...). */
    data class Stop(val reason: String) : ClaudeStreamEvent

    /** `message_stop`: the stream is over. */
    data object Done : ClaudeStreamEvent

    /**
     * An `error` event inside a 200: the stream started, then failed (`overloaded_error` is the
     * common one). Carries the API's own error type so it classifies like an HTTP failure.
     */
    data class Failure(val errorType: String?, val message: String?) : ClaudeStreamEvent

    /** A line with nothing to act on: framing, `ping`, block stops, signatures, usage. */
    data object Ignored : ClaudeStreamEvent

    /** A `data:` payload that is not the JSON it should be. */
    data class Malformed(val detail: String) : ClaudeStreamEvent

    companion object {

        /** Longest slice of a bad payload carried into a log line. */
        private const val MAX_DETAIL = 120

        /**
         * Reads one line of the stream.
         *
         * @param line a raw line, `data:` prefix included
         * @return what the line means for the turn
         */
        fun parse(line: String): ClaudeStreamEvent {
            if (!line.startsWith("data:")) return Ignored
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) return Ignored
            val json = try {
                JSONObject(payload)
            } catch (e: JSONException) {
                return Malformed(payload.take(MAX_DETAIL))
            }
            return when (json.optString("type")) {
                "message_start" ->
                    Started(json.optJSONObject("message")?.optString("model")?.takeIf { it.isNotBlank() })

                "content_block_start" -> blockStart(json)
                "content_block_delta" -> blockDelta(json)

                "message_delta" ->
                    json.optJSONObject("delta")?.optString("stop_reason")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?.let(::Stop)
                        ?: Ignored

                "message_stop" -> Done

                "error" -> json.optJSONObject("error").let { error ->
                    Failure(
                        errorType = error?.optString("type")?.takeIf { it.isNotBlank() },
                        message = error?.optString("message")?.takeIf { it.isNotBlank() },
                    )
                }

                // ping, content_block_stop, and event types added after this was written.
                else -> Ignored
            }
        }

        /** A `content_block_start`, which only matters for the block types the backend tracks. */
        private fun blockStart(json: JSONObject): ClaudeStreamEvent {
            val index = json.optInt("index", -1)
            val block = json.optJSONObject("content_block") ?: return Malformed("block start without content_block")
            return when (block.optString("type")) {
                "tool_use" -> {
                    val name = block.optString("name")
                    if (index < 0 || name.isBlank()) {
                        Malformed("tool_use block without index or name")
                    } else {
                        ToolStart(index, block.optString("id"), name)
                    }
                }

                "fallback" -> FallbackSwitch(
                    index,
                    block.optJSONObject("to")?.optString("model")?.takeIf { it.isNotBlank() },
                )

                // A text block can open with text already in it; every one seen so far is empty.
                else -> BlockOpened(index, block.optString("type"), block.optString("text"))
            }
        }

        /** A `content_block_delta`, by the kind of delta it carries. */
        private fun blockDelta(json: JSONObject): ClaudeStreamEvent {
            val delta = json.optJSONObject("delta") ?: return Malformed("block delta without delta")
            return when (delta.optString("type")) {
                "text_delta" -> delta.optString("text").takeIf { it.isNotEmpty() }?.let(::Text) ?: Ignored
                "input_json_delta" -> ToolInput(json.optInt("index", -1), delta.optString("partial_json"))
                // thinking_delta, signature_delta, citations_delta: nothing the transcript shows.
                else -> Ignored
            }
        }
    }
}
