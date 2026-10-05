package com.itsaky.androidide.plugins.aicore.tool

/**
 * Builders for the JSON Schema a tool publishes as [ToolHandler.parametersSchema].
 *
 * Plain JSON Schema, the dialect contributed (MCP) tools already arrive in, so a backend needs one
 * conversion rather than two. Adapting it to a provider's dialect belongs to that backend.
 */
object ToolSchema {

    /**
     * An object schema over [properties].
     *
     * `required` is omitted when empty rather than sent as an empty array: a provider that
     * validates the schema is entitled to reject the empty form.
     *
     * @param properties the arguments, each built by [string], [boolean] or [freeform].
     * @param required the arguments a call must carry.
     * @return the schema.
     */
    fun objectOf(
        vararg properties: Pair<String, Map<String, Any>>,
        required: List<String> = emptyList(),
    ): Map<String, Any> = buildMap {
        put("type", "object")
        put("properties", properties.toMap())
        if (required.isNotEmpty()) put("required", required)
    }

    /**
     * A string argument.
     * @param description what it means, as the model reads it; null for a built-in tool, whose
     *   argument wording is `tool_descriptions.yml`'s.
     * @return the property schema.
     */
    fun string(description: String? = null): Map<String, Any> = property("string", description)

    /**
     * A boolean argument.
     * @param description what it means, as the model reads it; null for a built-in tool.
     * @return the property schema.
     */
    fun boolean(description: String? = null): Map<String, Any> = property("boolean", description)

    /**
     * An argument holding an object whose keys are not known ahead of time.
     *
     * A backend whose provider cannot declare one (Gemini rejects an object with no properties)
     * degrades it to JSON text, so a handler reading such an argument must accept either shape.
     *
     * @param description what it means, as the model reads it; null for a built-in tool.
     * @return the property schema.
     */
    fun freeform(description: String? = null): Map<String, Any> = property("object", description)

    private fun property(type: String, description: String?): Map<String, Any> =
        if (description == null) mapOf("type" to type) else mapOf("type" to type, "description" to description)
}
