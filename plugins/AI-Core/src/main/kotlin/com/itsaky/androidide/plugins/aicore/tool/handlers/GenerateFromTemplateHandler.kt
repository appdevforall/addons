package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.services.IdeTemplateService
import org.json.JSONObject

private const val TAG = "$LOG_PREFIX.GenerateFromTemplateHandler"

/**
 * Handler for generating files from Pebble templates.
 */
class GenerateFromTemplateHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "generate_from_template"
    override val parametersSchema = ToolSchema.objectOf(
        "template_name" to ToolSchema.string("Name of the registered template to generate from."),
        "variables" to ToolSchema.freeform(
            "Template variables, as a flat object of name to value."
        ),
        required = listOf("template_name"),
    )
    override val description = "Generate files from Pebble templates with variable substitution"

    /**
     * A tool that generates files into the project asks first. Declared true although [execute]
     * today only locates the template and writes nothing — do not flip it back once it does.
     */
    override val requiresApproval = true

    /**
     * Reads the `variables` argument, whatever shape the backend delivered it in.
     *
     * A nested object reaches a handler as [JSONObject], never as a [Map] — both the envelope
     * parser and a native call hand org.json's own types over — and a backend that cannot declare
     * a free-form object (Gemini) sends its JSON as text instead.
     *
     * @param value the raw argument.
     * @return the variables, empty when there are none or the text will not parse.
     */
    internal fun variablesOf(value: Any?): Map<String, Any?> = when {
        value == null -> emptyMap()
        value is JSONObject -> value.keys().asSequence().associateWith { value.get(it) }
        value is Map<*, *> -> value.entries.associate { (key, entry) -> key.toString() to entry }
        else -> runCatching { JSONObject(value.toString()) }
            .onFailure { Log.w(TAG, "variables is not a JSON object: $value") }
            .getOrNull()
            ?.let { json -> json.keys().asSequence().associateWith { json.get(it) } }
            ?: emptyMap()
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val templateName = args["template_name"]?.toString()?.trim()
        if (templateName.isNullOrBlank()) {
            return ToolResult.failure(
                "template_name is required",
                "Provide a template name like 'activity', 'fragment', 'recycler_item', etc."
            )
        }

        val variables = variablesOf(args["variables"])

        Log.d(TAG, "Generating from template: $templateName with ${variables.size} variables")

        return try {
            val templateService = pluginContext.services.get(IdeTemplateService::class.java)
            if (templateService == null) {
                Log.w(TAG, "IdeTemplateService not available")
                return ToolResult.failure(
                    "Template service not available",
                    "The IDE template service is not available. Templates may not be registered."
                )
            }

            // Get available templates
            val registeredTemplates = templateService.getRegisteredTemplates()
            Log.d(TAG, "Available templates: $registeredTemplates")

            if (registeredTemplates.isEmpty()) {
                return ToolResult.failure(
                    "No templates available",
                    "No Pebble templates are registered. Check template configuration."
                )
            }

            // Look for matching template file
            val matchingTemplate = registeredTemplates.firstOrNull { it.contains(templateName, ignoreCase = true) }
            if (matchingTemplate == null) {
                return ToolResult.failure(
                    "Template not found: $templateName",
                    "Available templates: ${registeredTemplates.joinToString(", ")}"
                )
            }

            Log.d(TAG, "Found template: $matchingTemplate")

            // Note: Actual template execution requires CgtTemplateBuilder integration
            // For now, return a note that user should use the template directly from Plugin Manager
            ToolResult.success(
                message = "Template located: $matchingTemplate",
                data = buildString {
                    append("Template '$templateName' is available.\n\n")
                    append("To use this template:\n")
                    append("1. Go to Plugin Manager → Templates\n")
                    append("2. Select template: $matchingTemplate\n")
                    append("3. Provide variables:\n")
                    variables.forEach { (k, v) ->
                        append("   - $k = $v\n")
                    }
                    append("\nAlternatively, use create_file to generate content directly.")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error generating from template", e)
            ToolResult.failure(
                "Error with template",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
