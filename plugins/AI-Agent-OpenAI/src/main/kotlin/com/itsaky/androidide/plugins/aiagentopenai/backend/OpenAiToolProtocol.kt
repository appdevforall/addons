package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * This backend's half of the native function-calling protocol: `tools[]` out, `tool_calls` in.
 *
 * Pure and free of Android types, so the shapes that decide whether a tool call runs at all are
 * unit-testable without a device or a network — see [OpenAiSystemPrompt] for the same reasoning.
 */
internal object OpenAiToolProtocol {

    /**
     * Nesting a declared schema may carry. A contributed (MCP) schema is provider-supplied text,
     * and a pathologically deep one would otherwise recurse until the host process dies.
     */
    private const val MAX_SCHEMA_DEPTH = 12

    /**
     * One `tool_calls` fragment as it arrives on the stream.
     *
     * A call is spread across as many chunks as its arguments need, so no single fragment is a
     * call; [CallAccumulator] joins them.
     *
     * @property index the call's position in the turn, which is what fragments are joined on.
     * @property id the provider's call id, present on the first fragment only.
     * @property name the tool's name, likewise present once.
     * @property arguments this fragment's slice of the arguments JSON, possibly a partial token.
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val arguments: String,
    )

    /**
     * The `tools[]` array declaring [tools] to the server.
     *
     * @param tools the tools to declare.
     * @return one `{"type":"function","function":{…}}` entry per tool.
     */
    fun toolsArray(tools: List<ToolDefinition>): JSONArray {
        val declarations = JSONArray()
        for (tool in tools) {
            val function = JSONObject()
                .put("name", tool.name)
                .put("description", tool.description.orEmpty())
                .put("parameters", parametersJson(tool.parametersSchema))
            declarations.put(JSONObject().put("type", "function").put("function", function))
        }
        return declarations
    }

    /**
     * The `parameters` value for a tool.
     *
     * An empty schema becomes a bare object rather than being omitted: omitting `parameters`
     * declares a tool that takes none, and the model would then call it with nothing.
     *
     * @param schema the tool's JSON Schema, empty when it publishes none.
     * @return the schema to declare.
     */
    fun parametersJson(schema: Map<String, Any>?): JSONObject {
        if (schema.isNullOrEmpty()) return JSONObject().put("type", "object")
        return schemaJson(schema, MAX_SCHEMA_DEPTH)
    }

    /**
     * Converts a JSON Schema to JSON.
     *
     * Passed through keyword for keyword, unlike the Gemini transport's whitelist: this protocol
     * takes plain JSON Schema, which is the dialect a contributed tool already arrives in.
     *
     * @param schema the tool's JSON Schema.
     * @param depth how much further nesting to render; a deeper subtree is dropped.
     * @return the equivalent JSON.
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
     * The `tool_calls` fragments carried by one streamed `delta` (or one-shot `message`).
     *
     * @param delta the chunk's `delta` or `message` object, or null when it has neither.
     * @return the fragments, empty when the chunk carries no call.
     */
    fun toolCallDeltas(delta: JSONObject?): List<ToolCallDelta> {
        val calls = delta?.optJSONArray("tool_calls") ?: return emptyList()
        val deltas = mutableListOf<ToolCallDelta>()
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val function = call.optJSONObject("function")
            deltas += ToolCallDelta(
                // Absent on servers that send a whole call per chunk; position in the array then.
                index = if (call.has("index")) call.optInt("index") else i,
                id = call.optString("id").takeIf { it.isNotBlank() },
                name = function?.optString("name")?.takeIf { it.isNotBlank() },
                arguments = function?.optString("arguments").orEmpty(),
            )
        }
        return deltas
    }

    /**
     * Joins streamed [ToolCallDelta] fragments back into whole calls.
     *
     * Not thread-safe: it belongs to the one reader loop consuming a single response body.
     */
    class CallAccumulator {

        /** One call under construction, fed by every fragment carrying its index. */
        private class Entry(val id: String?, var name: String?) {
            val arguments = StringBuilder()
        }

        /** Every call this turn has begun, in arrival order. */
        private val entries = mutableListOf<Entry>()

        /** The call each index is still receiving fragments for. */
        private val open = HashMap<Int, Entry>()

        /**
         * Calls whose arguments never parsed, as of the last [requests] call.
         *
         * The diagnostic for a turn that asked for a tool and ran none: a cut-off stream leaves
         * arguments half-written, which is a truncated reply rather than an empty one.
         */
        var droppedCalls: Int = 0
            private set

        /**
         * Folds one chunk's fragments in.
         * @param deltas the fragments, in the order the chunk carried them.
         */
        fun accept(deltas: List<ToolCallDelta>) {
            for (delta in deltas) {
                val existing = open[delta.index]
                // A new id at a live index means a second call, not more of the first one.
                val entry = if (existing == null || (delta.id != null && delta.id != existing.id)) {
                    Entry(delta.id, delta.name).also { entries += it; open[delta.index] = it }
                } else {
                    existing.apply { if (name == null) name = delta.name }
                }
                entry.arguments.append(delta.arguments)
            }
        }

        /**
         * The calls accumulated so far, in the order the stream began them.
         *
         * A call whose arguments will not parse is left out and counted in [droppedCalls] rather
         * than reported with empty arguments, which would run the tool on nothing.
         *
         * @return the whole calls; empty when the turn carried none.
         */
        fun requests(): List<ToolCallRequest> {
            val requests = mutableListOf<ToolCallRequest>()
            var dropped = 0
            for (entry in entries) {
                val name = entry.name
                if (name.isNullOrBlank()) {
                    dropped++
                    continue
                }
                val args = argsOf(entry.arguments.toString())
                if (args == null) {
                    dropped++
                    continue
                }
                // Paired by name when the server sent no id, never by an id it would not recognise.
                requests += ToolCallRequest(entry.id ?: name, name, args)
            }
            droppedCalls = dropped
            return requests
        }
    }

    /**
     * Reads one call's `arguments` string.
     *
     * @param arguments the accumulated JSON; blank for a tool called with none.
     * @return the arguments, or null when the JSON is incomplete or malformed.
     */
    fun argsOf(arguments: String): Map<String, Any>? {
        val text = arguments.trim()
        if (text.isEmpty()) return emptyMap()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val args = mutableMapOf<String, Any>()
        // Values stay as org.json types, as the Gemini transport also hands them over.
        for (key in json.keys()) args[key] = json.get(key)
        return args
    }
}
