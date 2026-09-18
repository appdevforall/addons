package com.itsaky.androidide.plugins.aiagentgemini.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini's half of the native function-calling protocol: tool schemas out, `functionCall` parts in.
 *
 * Pure and free of Android types, so the shapes that decide whether a tool call runs at all are
 * unit-testable without a device or a network — see [GeminiSystemPrompt] for the same reasoning.
 */
internal object GeminiToolProtocol {

    /** Gemini's `Type` for an object, which its enum spells in upper case. */
    private const val TYPE_OBJECT = "OBJECT"

    /** Gemini's `Type` for a string, the only shape a free-form object can be declared as. */
    private const val TYPE_STRING = "STRING"

    /** Appended when an object argument has to be declared as JSON text; see [declarable]. */
    private const val AS_JSON_TEXT = " Written as a JSON object."

    /**
     * One parsed stream chunk.
     *
     * @property text the chunk's text parts, concatenated.
     * @property calls the chunk's `functionCall` parts.
     * @property finishReason why generation stopped, on the chunk that carries it.
     */
    data class StreamChunk(
        val text: String,
        val calls: List<ToolCallRequest>,
        val finishReason: String?,
    ) {
        companion object {
            /** A chunk carrying nothing, for a payload that would not parse. */
            val EMPTY = StreamChunk("", emptyList(), null)
        }
    }

    /**
     * Splits the first candidate of [response] into text, tool calls, and a finish reason.
     *
     * @param response a generateContent response (or a single stream chunk)
     * @return what the chunk carried; [StreamChunk.EMPTY] when it has no candidate
     */
    fun parseChunk(response: JSONObject): StreamChunk {
        val candidates = response.optJSONArray("candidates") ?: return StreamChunk.EMPTY
        if (candidates.length() == 0) return StreamChunk.EMPTY
        val candidate = candidates.getJSONObject(0)
        val finishReason = candidate.optString("finishReason").takeIf { it.isNotEmpty() }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: return StreamChunk("", emptyList(), finishReason)

        val text = StringBuilder()
        val calls = mutableListOf<ToolCallRequest>()
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            val functionCall = part.optJSONObject("functionCall")
            if (functionCall != null) calls += toolCallOf(functionCall) else text.append(part.optString("text"))
        }
        return StreamChunk(text.toString(), calls, finishReason)
    }

    /**
     * Reads one `functionCall` part.
     *
     * Gemini pairs a `functionResponse` by name rather than by id, so a call with no `id` of its
     * own is identified by its name — never by a synthetic id the API would not recognise.
     *
     * @param functionCall the part's `functionCall` object
     * @return the call, with its arguments already structured
     */
    fun toolCallOf(functionCall: JSONObject): ToolCallRequest {
        val name = functionCall.optString("name")
        val args = mutableMapOf<String, Any>()
        functionCall.optJSONObject("args")?.let { declared ->
            for (key in declared.keys()) args[key] = declared.get(key)
        }
        return ToolCallRequest(functionCall.optString("id").ifEmpty { name }, name, args)
    }

    /**
     * The `functionDeclarations` array for [tools].
     *
     * @param tools the tools to declare
     * @return one declaration per tool, parameters omitted unless the tool names arguments
     */
    fun functionDeclarations(tools: List<ToolDefinition>): JSONArray {
        val declarations = JSONArray()
        for (tool in tools) {
            val declaration = JSONObject()
                .put("name", tool.name)
                .put("description", tool.description.orEmpty())
            val parameters = tool.parametersSchema?.takeIf { it.isNotEmpty() }?.let { schemaJson(it) }
            // Only when it names arguments: Gemini rejects an OBJECT with no properties outright
            // ("should be non-empty for OBJECT type"), which fails the whole request, every tool
            // in it included. A tool that names none is declared the way a no-arg tool is.
            if (parameters != null && namesProperties(parameters)) {
                declaration.put("parameters", parameters)
            }
            declarations.put(declaration)
        }
        return declarations
    }

    /** Whether [schema] declares at least one property, which an OBJECT must for Gemini. */
    private fun namesProperties(schema: JSONObject): Boolean =
        (schema.optJSONObject("properties")?.length() ?: 0) > 0

    /**
     * [schema] in a form Gemini will accept as one argument.
     *
     * An object whose keys are not known ahead of time cannot be declared as an OBJECT here at
     * all, so it is declared as the JSON text the model should write instead — which every caller
     * of this protocol already accepts for such an argument.
     *
     * @param schema one property's schema, already converted.
     * @return the schema to declare, unchanged unless it is a propertyless object.
     */
    private fun declarable(schema: JSONObject): JSONObject {
        if (schema.optString("type") != TYPE_OBJECT || namesProperties(schema)) return schema
        val description = schema.optString("description").trim()
        return JSONObject()
            .put("type", TYPE_STRING)
            .put("description", (description + AS_JSON_TEXT).trim())
    }

    /**
     * Converts a JSON Schema to the OpenAPI subset Gemini accepts.
     *
     * Only the keywords Gemini documents survive: anything else (`additionalProperties`, `$ref`,
     * `oneOf`) is rejected outright by the API, and a contributed tool is free to carry them.
     *
     * @param schema the tool's JSON Schema, as [ToolDefinition] carries it
     * @return the equivalent Gemini schema
     */
    fun schemaJson(schema: Map<*, *>): JSONObject {
        val json = JSONObject()
        // Gemini's Type is an enum, so its values are upper case; JSON Schema writes them lower.
        (schema["type"] as? String)?.let { json.put("type", it.uppercase()) }
        (schema["description"] as? String)?.let { json.put("description", it) }
        (schema["format"] as? String)?.let { json.put("format", it) }
        (schema["enum"] as? Collection<*>)?.let { values ->
            json.put("enum", JSONArray().apply { values.forEach { put(it.toString()) } })
        }
        // Through declarable() like a property: an array of free-form objects is as propertyless
        // as a free-form property, and Gemini answers the same 400.
        (schema["items"] as? Map<*, *>)?.let { json.put("items", declarable(schemaJson(it))) }
        (schema["properties"] as? Map<*, *>)?.let { properties ->
            val rendered = JSONObject()
            for ((name, value) in properties) {
                if (value is Map<*, *>) rendered.put(name.toString(), declarable(schemaJson(value)))
            }
            if (rendered.length() > 0) json.put("properties", rendered)
        }
        (schema["required"] as? Collection<*>)?.let { required ->
            if (required.isNotEmpty()) {
                json.put("required", JSONArray().apply { required.forEach { put(it.toString()) } })
            }
        }
        return json
    }
}
