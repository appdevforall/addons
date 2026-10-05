package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.ai.prompt.PromptText
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig.ToolText
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.sources.ContributedToolHandler
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition

/**
 * Words ai-core's own tools from `tool_descriptions.yml`: the code declares a built-in's name and
 * argument shape, the config says what each is for. A contributed tool brings its own description
 * and passes through untouched. Strict: an undescribed built-in or argument throws.
 */
object ToolDescriptions {

    /**
     * Fills in the wording of every built-in definition; the others are returned as they are.
     *
     * @param config the loaded prompt config.
     * @param terminalTool the name of the tool that ends a run by answering the user.
     * @param definitions the run's definitions, built-in ones carrying no wording yet.
     * @param builtInNames which of [definitions] are ai-core's own.
     * @return the definitions to hand the backend and describe in the prompt.
     */
    fun apply(
        config: AgentPromptConfig,
        terminalTool: String,
        definitions: List<ToolDefinition>,
        builtInNames: Set<String>,
    ): List<ToolDefinition> = definitions.map { definition ->
        if (definition.name in builtInNames) {
            describe(definition, builtIn(config, definition.name), terminalTool)
        } else {
            definition
        }
    }

    /**
     * The terminal tool's definition, worded by `terminal_tool`.
     *
     * @param config the loaded prompt config.
     * @param terminalTool the name the run gives it.
     * @param parametersSchema its argument shape, which the code owns.
     * @return the definition.
     */
    fun terminalTool(
        config: AgentPromptConfig,
        terminalTool: String,
        parametersSchema: Map<String, Any>,
    ): ToolDefinition =
        describe(ToolDefinition(terminalTool, "", parametersSchema), config.terminalTool, terminalTool)

    /**
     * What the approval dialog says a tool does: the same description the model reads.
     *
     * @param config the loaded prompt config.
     * @param terminalTool the name of the tool that ends a run by answering the user.
     * @param handler the tool being approved.
     * @return its description.
     */
    fun describe(config: AgentPromptConfig, terminalTool: String, handler: ToolHandler): String {
        if (handler is ContributedToolHandler) return handler.description
        return render(builtIn(config, handler.toolName).description, terminalTool)
    }

    /**
     * Checks the config against the built-in tools, to catch a missing, stray or misspelled entry.
     *
     * @param config the loaded prompt config.
     * @param builtIns ai-core's own handlers.
     * @return one message per problem; empty when every tool and argument is described.
     */
    fun problems(config: AgentPromptConfig, builtIns: List<ToolHandler>): List<String> {
        val names = builtIns.mapTo(mutableSetOf()) { it.toolName }
        val stray = (config.builtInTools.byName.keys - names).map { name ->
            "${config.builtInTools.label}.$name: no built-in tool is named $name"
        }
        val checks: List<() -> Any> = builtIns.map { handler ->
            { apply(config, CHECK_TERMINAL_TOOL, listOf(definitionOf(handler)), names) }
        } + { terminalTool(config, CHECK_TERMINAL_TOOL, PromptToolCatalog.TERMINAL_TOOL_SCHEMA) }
        val failures = checks.mapNotNull { check ->
            try {
                check()
                null
            } catch (e: IllegalArgumentException) {
                e.message
            }
        }
        return stray + failures
    }

    private fun definitionOf(handler: ToolHandler) =
        ToolDefinition(handler.toolName, handler.description, handler.parametersSchema)

    private fun builtIn(config: AgentPromptConfig, name: String): ToolText =
        requireNotNull(config.builtInTools.byName[name]) {
            "${config.builtInTools.label}.$name is missing; every built-in tool needs a description"
        }

    /** The definition with [text]'s wording; the argument shape stays as the code declared it. */
    private fun describe(
        definition: ToolDefinition,
        text: ToolText,
        terminalTool: String,
    ): ToolDefinition {
        val schema: Map<String, Any> = definition.parametersSchema.orEmpty()
        val properties = (schema["properties"] as? Map<*, *>).orEmpty()
        val declared = properties.keys.map { it.toString() }
        // The tool's own label, e.g. `tool_descriptions.yml: built_in_tools.read_file`.
        val toolLabel = text.description.label.substringBeforeLast('.')
        declared.firstOrNull { it !in text.arguments }?.let { argument ->
            throw IllegalArgumentException("$toolLabel.arguments.$argument is missing")
        }
        text.arguments.entries.firstOrNull { it.key !in declared }?.let { (argument, stray) ->
            throw IllegalArgumentException("${stray.label}: ${definition.name} takes no argument $argument")
        }
        val described = if (properties.isEmpty()) {
            schema
        } else {
            schema + ("properties" to describeArguments(properties, text, terminalTool))
        }
        return ToolDefinition(definition.name, render(text.description, terminalTool), described)
    }

    /** Each argument's schema with its description added, in the order the code declared them. */
    private fun describeArguments(
        properties: Map<*, *>,
        text: ToolText,
        terminalTool: String,
    ): Map<String, Any> = properties.entries.associate { (name, property) ->
        val description = render(text.arguments.getValue(name.toString()), terminalTool)
        name.toString() to ((property as Map<*, *>) + ("description" to description))
    }

    private fun render(text: PromptText, terminalTool: String): String =
        PromptTemplateEngine.render(text, mapOf(PromptVariables.TERMINAL_TOOL to terminalTool))

    private const val CHECK_TERMINAL_TOOL = "respond"
}
