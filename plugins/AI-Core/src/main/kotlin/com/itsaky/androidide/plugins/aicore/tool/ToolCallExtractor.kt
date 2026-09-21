package com.itsaky.androidide.plugins.aicore.tool

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import org.json.JSONObject

/**
 * Extracts tool calls from an LLM reply, by explicit `<tool_call>` envelope first and bare
 * `{"tool":...}` JSON second. Works for both the cloud and local backends.
 */
class ToolCallExtractor {

    /**
     * Why a reply that reads like a tool call produced none. Each state needs different advice,
     * so the caller can tell the user what to do rather than dump the raw reply.
     */
    enum class UnparsedReply {
        /** The envelope was never closed; the reply hit the model's output cap mid-call. */
        TRUNCATED,

        /** The envelope closed but its JSON would not parse, usually an unescaped quote. */
        MALFORMED,
    }

    companion object {
        private const val TAG = "$LOG_PREFIX.ToolCallExtractor"

        /** The `<tool_call>{…}</tool_call>` envelope both system prompts ask for. */
        private val TOOL_CALL_REGEX =
            Regex("""<tool_call>\s*(.+?)\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)

        /** Opening envelope tag, as [TOOL_CALL_REGEX] matches it and [renderEnvelope] writes it. */
        private const val OPEN_TAG = "<tool_call>"

        /** Closing envelope tag; see [OPEN_TAG]. */
        private const val CLOSE_TAG = "</tool_call>"

        /** The key that marks an object as a call. Scanned per character, so kept a plain string. */
        private const val TOOL_KEY = "\"tool\""

        /**
         * The `"tool"` key of a bare (unenveloped) call, as a JSON key rather than as the word.
         *
         * The colon is what makes it a key: a reply that merely says the word `"tool"` in quotes is
         * prose, and reading it as a broken call reports a failure that never happened.
         */
        private val BARE_TOOL_KEY_REGEX = Regex(""""tool"\s*:""")

        /**
         * Classifies a reply that [extractToolCalls] found nothing in.
         *
         * Only meaningful for such a reply: a parsed envelope matches these shapes too, so calling
         * it on a reply that yielded calls reports a failure that did not happen.
         *
         * @param text the model's raw reply.
         * @return the failure, or null when the reply is ordinary prose and nothing went wrong.
         */
        fun diagnoseUnparsedReply(text: String): UnparsedReply? {
            val opened = text.indexOf(OPEN_TAG)
            if (opened >= 0) {
                val closed = text.indexOf(CLOSE_TAG, opened + OPEN_TAG.length)
                return if (closed < 0) UnparsedReply.TRUNCATED else UnparsedReply.MALFORMED
            }
            // A `tool_code` block Strategy 3 could not read, e.g. one passing arguments positionally.
            if (ToolCodeParser.looksLikeToolCode(text)) return UnparsedReply.MALFORMED
            // No envelope at all, but a bare call the JSON strategy could not read.
            return if (containsBareToolCall(text)) UnparsedReply.MALFORMED else null
        }

        /**
         * Whether [text] holds something shaped like a bare `{"tool":…}` call.
         *
         * Requires the key to sit inside an object, so neither the diagnosis nor the prose filter
         * fires on a sentence that quotes the word.
         *
         * @param text the text to inspect.
         * @return true when a bare call is present.
         */
        private fun containsBareToolCall(text: String): Boolean {
            val key = BARE_TOOL_KEY_REGEX.find(text) ?: return false
            return text.lastIndexOf('{', key.range.first) >= 0
        }

        /**
         * Renders one call as the canonical envelope, escaping through [JSONObject].
         *
         * This is how a backend's native function call re-enters the text pipeline: encoding it
         * here rather than trusting the model to is what puts such a call out of
         * [UnparsedReply.MALFORMED]'s reach.
         *
         * @param name the tool's name.
         * @param args its arguments.
         * @return the envelope, ready to append to the reply text.
         */
        fun renderEnvelope(name: String, args: Map<String, Any?>): String {
            val argsJson = JSONObject()
            for ((key, value) in args) argsJson.put(key, value ?: JSONObject.NULL)
            val call = JSONObject().put("tool", name).put("args", argsJson)
            return "$OPEN_TAG$call$CLOSE_TAG"
        }

        /**
         * A tool result written by the model itself, as a ```tool_response fence or a
         * `<tool_response>` tag. Both system prompts forbid these and promise such output is
         * ignored; [beforeFabricatedResult] is where that promise is kept.
         */
        private val FABRICATED_RESULT_REGEX =
            Regex("""```+\s*tool_response|<tool_response>""", RegexOption.IGNORE_CASE)

        /**
         * [text] up to the first tool result the model wrote for itself.
         *
         * A model that answers its own tool call has stopped reporting and started role-playing
         * the rest of the conversation, so every call after that point belongs to an invented
         * transcript: one reply once yielded 13 calls against a project that did not exist. The
         * calls before it were still real, so the reply is truncated rather than discarded.
         *
         * @param text the model's raw reply.
         * @return the leading real portion, or [text] unchanged when nothing was fabricated.
         */
        internal fun beforeFabricatedResult(text: String): String {
            val match = FABRICATED_RESULT_REGEX.find(text) ?: return text
            AgentTrace.refusal(
                "PARSE",
                "fabricated tool result offset=${match.range.first} " +
                    "ignoredChars=${text.length - match.range.first}",
                "the reply answered its own tool call"
            )
            return text.substring(0, match.range.first)
        }

        /**
         * The prose left once the tool-call envelopes are removed. Worth showing when a `respond`
         * call carries no message, which usually means the model wrote the answer as prose and
         * emitted an empty envelope beside it.
         * @param text the model's raw reply.
         * @return the prose, or null when there is none, or when what remains is a bare
         *   (untagged) tool call rather than something meant for the user to read.
         */
        fun proseOutsideToolCalls(text: String): String? {
            val remainder = TOOL_CALL_REGEX.replace(beforeFabricatedResult(text), "\n").trim()
            if (remainder.isEmpty()) return null
            // A leftover bare call is not prose; raw JSON is worse than nothing.
            if (containsBareToolCall(remainder)) return null
            if (ToolCodeParser.looksLikeToolCode(remainder)) return null
            return remainder
        }

        /**
         * Extracts every tool call from [text], trying each strategy in turn.
         * @param text the model's raw reply.
         * @return the calls found, in the order they appear; empty when there are none.
         */
        fun extractToolCalls(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()

            Log.d(TAG, "Extracting tool calls from response (${text.length} chars)")
            Log.d(TAG, "Response preview: ${text.take(300)}")

            // Anything past a tool result the model wrote itself is an invented continuation.
            val body = beforeFabricatedResult(text)

            // Which strategy read the reply, for the trace: a call that arrives as `tool_code`
            // rather than `envelope` is the model ignoring the protocol, not this side failing.
            var strategy = "none"

            // Strategy 1: Explicit XML tags
            toolCalls.addAll(extractFromXmlTags(body))
            if (toolCalls.isNotEmpty()) strategy = "envelope"

            // Strategy 2: Bare JSON objects if no XML found
            if (toolCalls.isEmpty()) {
                toolCalls.addAll(extractFromJsonObjects(body))
                if (toolCalls.isNotEmpty()) strategy = "bare_json"
            }

            // Strategy 3: Gemini's own `default_api` dialect, which neither prompt asks for.
            if (toolCalls.isEmpty()) {
                val fromToolCode = ToolCodeParser.parse(body)
                if (fromToolCode.isNotEmpty()) {
                    Log.d(TAG, "Strategy 3 (tool_code): Found ${fromToolCode.size} matches")
                    toolCalls.addAll(fromToolCode)
                    strategy = "tool_code"
                }
            }

            AgentTrace.detail("EXTRACT", "strategy=$strategy calls=${toolCalls.size} chars=${body.length}")
            Log.d(TAG, "Extracted ${toolCalls.size} tool calls from response (${body.length} chars)")

            // Warn if we found incomplete tool calls
            if (body.contains("<tool_call>") && body.count { it == '<' } > body.count { it == '>' }) {
                Log.w(TAG, "WARNING: Found incomplete tool call tags in response. Response may have been truncated.")
                Log.w(TAG, "Full response: $text")
            }

            return toolCalls
        }

        /**
         * Strategy 1: Extract explicit tool calls from XML tags.
         * Format: <tool_call>{"tool":"name","args":{...}}</tool_call>
         */
        private fun extractFromXmlTags(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()
            val matches = TOOL_CALL_REGEX.findAll(text)

            Log.d(TAG, "Strategy 1 (XML tags): Found ${matches.count()} matches")

            for (match in matches) {
                val parsed = parseToolJson(match.groupValues[1].trim())
                if (parsed != null) {
                    toolCalls.add(parsed)
                }
            }

            return toolCalls
        }

        /**
         * Strategy 2: Extract tool calls from bare JSON objects.
         * Format: {"tool":"name","args":{...}}
         * Uses brace-balanced extraction to handle nested args objects.
         */
        private fun extractFromJsonObjects(text: String): List<ToolCall> {
            val toolCalls = mutableListOf<ToolCall>()
            var found = 0

            // Find JSON objects with balanced braces containing "tool" field.
            var i = 0
            while (i < text.length) {
                if (text[i] == '{') {
                    // Extract a balanced object, ignoring braces inside string values.
                    var braceCount = 0
                    var j = i
                    var hasToolField = false
                    var inString = false
                    var escaped = false

                    while (j < text.length) {
                        val c = text[j]
                        if (inString) {
                            when {
                                escaped -> escaped = false
                                c == '\\' -> escaped = true
                                c == '"' -> inString = false
                            }
                        } else {
                            when (c) {
                                '"' -> inString = true
                                '{' -> braceCount++
                                '}' -> braceCount--
                            }
                        }

                        // Check if this substring contains "tool"
                        if (!hasToolField && text.substring(i, minOf(j + 1, text.length)).contains(TOOL_KEY)) {
                            hasToolField = true
                        }

                        j++

                        if (!inString && braceCount == 0) {
                            // Found complete object
                            if (hasToolField) {
                                val jsonStr = text.substring(i, j)
                                val parsed = parseToolJson(jsonStr)
                                if (parsed != null) {
                                    toolCalls.add(parsed)
                                    found++
                                }
                            }
                            break
                        }
                    }

                    i = j
                } else {
                    i++
                }
            }

            Log.d(TAG, "Strategy 2 (JSON objects): Found $found matches")

            return toolCalls
        }

        /**
         * Parse tool JSON and extract tool name and arguments.
         */
        private fun parseToolJson(jsonStr: String): ToolCall? {
            return try {
                val json = JSONObject(jsonStr)
                val toolName = json.optString("tool").ifEmpty { json.optString("name") }
                if (toolName.isEmpty()) {
                    Log.w(TAG, "Tool JSON has neither 'tool' nor 'name': $jsonStr")
                    return null
                }
                val argsJson = json.optJSONObject("args") ?: json.optJSONObject("arguments") ?: JSONObject()

                val args = mutableMapOf<String, Any?>()
                val keys = argsJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    args[key] = argsJson.get(key)
                }

                Log.d(TAG, "Parsed tool call: $toolName with args: $args")
                ToolCall(toolName, args)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse tool JSON: $jsonStr", e)
                null
            }
        }
    }
}
