package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * This backend's half of the Messages API tool protocol: `tools[]` out, `tool_use` blocks in.
 *
 * Pure and free of Android types, so the shapes that decide whether a tool call runs at all are
 * unit-testable without a device or a network.
 */
internal object ClaudeToolProtocol {

    /**
     * Nesting a declared schema may carry. A contributed (MCP) schema is provider-supplied text,
     * and a pathologically deep one would otherwise recurse until the host process dies.
     */
    private const val MAX_SCHEMA_DEPTH = 12

    /**
     * The `tools[]` array declaring [tools] to the API.
     *
     * Not `strict`: strict mode needs every schema closed with `additionalProperties: false`, which
     * a contributed MCP schema rarely is, and the API answers an unconforming one with a 400.
     *
     * @param tools the tools to declare.
     * @return one `{"name", "description", "input_schema"}` entry per tool.
     */
    fun toolsArray(tools: List<ToolDefinition>): JSONArray {
        val declarations = JSONArray()
        for (tool in tools) {
            declarations.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description.orEmpty())
                    .put("input_schema", inputSchemaJson(tool.parametersSchema))
            )
        }
        return declarations
    }

    /**
     * The `input_schema` value for a tool.
     *
     * The API requires an object schema, so an empty one becomes a bare `{"type":"object"}` and a
     * schema that names no type gets one; any other declared type is passed through for the API to
     * judge, rather than silently rewritten into a contract the tool did not offer.
     *
     * @param schema the tool's JSON Schema, empty when it publishes none.
     * @return the schema to declare.
     */
    fun inputSchemaJson(schema: Map<String, Any>?): JSONObject {
        if (schema.isNullOrEmpty()) return JSONObject().put("type", "object")
        val json = schemaJson(schema, MAX_SCHEMA_DEPTH)
        if (!json.has("type")) json.put("type", "object")
        return json
    }

    /**
     * Converts a JSON Schema to JSON, keyword for keyword: the Messages API takes plain JSON
     * Schema, which is the dialect a contributed tool already arrives in.
     *
     * @param schema the tool's JSON Schema.
     * @param depth how much further nesting to render; a deeper subtree is dropped.
     */
    private fun schemaJson(schema: Map<*, *>, depth: Int): JSONObject {
        val json = JSONObject()
        if (depth <= 0) return json
        for ((key, value) in schema) {
            val name = key as? String ?: continue
            json.put(name, jsonValue(value, depth))
        }
        return json
    }

    /** One schema value: a nested schema, a list of them, or a scalar as it stands. */
    private fun jsonValue(value: Any?, depth: Int): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> schemaJson(value, depth - 1)
        is Collection<*> -> JSONArray().apply { value.forEach { put(jsonValue(it, depth)) } }
        else -> value
    }

    /**
     * Joins a stream's `tool_use` blocks back into whole calls.
     *
     * A block opens with its id and name (`content_block_start`) and its input follows as
     * `input_json_delta` fragments, each a slice of one JSON object that only parses once the block
     * is complete. Fragments are joined on the block's index, which is unique within one message.
     *
     * Not thread-safe: it belongs to the one reader loop consuming a single response body.
     */
    class CallAccumulator {

        /** One call under construction, at content-block [index]. */
        private class Entry(val index: Int, val id: String, val name: String) {
            val input = StringBuilder()
        }

        /** Every call this message has begun, in arrival order. */
        private val entries = mutableListOf<Entry>()

        /** The call each content-block index is receiving fragments for. */
        private val open = HashMap<Int, Entry>()

        /**
         * Calls whose input never parsed, as of the last [requests] call.
         *
         * The diagnostic for a turn that asked for a tool and ran none: a stream cut off by
         * `max_tokens` leaves the input half-written, which is a truncated reply, not an empty one.
         */
        var droppedCalls: Int = 0
            private set

        /**
         * Opens the `tool_use` block at [index].
         *
         * @param id the API's `toolu_...` id, carried back to correlate the result.
         * @param name the tool the model chose.
         */
        fun start(index: Int, id: String, name: String) {
            val entry = Entry(index, id, name)
            entries += entry
            open[index] = entry
        }

        /**
         * Forgets every call begun so far. For a server-side fallback: the declined model's calls
         * are not passed to the model that takes over, so running one would run a call nobody
         * stands behind, and the new model may well make the same call again.
         *
         * @return how many calls were forgotten
         */
        fun discardAll(): Int {
            val count = entries.size
            entries.clear()
            open.clear()
            return count
        }

        /**
         * Drops the call at content-block [index], if there is one: the block a `max_tokens` stop
         * cut off. Counted in [droppedCalls], since it is a truncated call; its input may even
         * look complete, as an empty one does.
         *
         * @return true when a call was dropped
         */
        fun dropAt(index: Int): Boolean {
            val entry = open.remove(index) ?: return false
            entries.remove(entry)
            truncated++
            return true
        }

        /** Calls dropped by [dropAt]; folded into [droppedCalls]. */
        private var truncated = 0

        /**
         * Appends one `input_json_delta` fragment to the block at [index]. A fragment for an index
         * no `tool_use` block opened belongs to some other block type and is ignored.
         */
        fun appendInput(index: Int, partialJson: String) {
            open[index]?.input?.append(partialJson)
        }

        /**
         * The calls accumulated so far, in the order the stream began them.
         *
         * A call whose input will not parse is left out and counted in [droppedCalls] rather than
         * reported with empty arguments, which would run the tool on nothing.
         *
         * @return the whole calls; empty when the message carried none.
         */
        fun requests(): List<ToolCallRequest> {
            val requests = mutableListOf<ToolCallRequest>()
            var dropped = 0
            for (entry in entries) {
                val args = argsOf(entry.input.toString())
                if (entry.name.isBlank() || args == null) {
                    dropped++
                    continue
                }
                requests += ToolCallRequest(entry.id.ifBlank { entry.name }, entry.name, args)
            }
            droppedCalls = dropped + truncated
            return requests
        }
    }

    /**
     * Reads one call's accumulated input.
     *
     * Parsed with org.json rather than matched as text: the API may escape the same string
     * differently from one model to the next (unicode, forward slashes).
     *
     * @param input the accumulated JSON; blank for a tool called with none.
     * @return the arguments, or null when the JSON is incomplete, malformed, or not an object.
     */
    fun argsOf(input: String): Map<String, Any>? {
        val text = input.trim()
        if (text.isEmpty()) return emptyMap()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val args = mutableMapOf<String, Any>()
        // Values stay as org.json types, as the other transports also hand them over.
        for (key in json.keys()) args[key] = json.get(key)
        return args
    }
}
