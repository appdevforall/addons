package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.aicore.tool.AgentTools
import com.itsaky.androidide.plugins.aicore.tool.ToolApprovalManager
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.BuiltInToolHandlers
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolDescriptions]: what each built-in tool is for, as the model and the approval
 * dialog read it, is `tool_descriptions.yml`'s, so rewording a tool is a config edit.
 */
class ToolDescriptionsTest {

    /** The catalogue the chat registers, not a copy of it, so a new built-in is checked too. */
    private val builtIns: List<ToolHandler> =
        BuiltInToolHandlers.create(mockk<PluginContext>(relaxed = true))

    private val tools =
        AgentTools.build(builtIns, ToolSourceStore(), ToolApprovalManager({ shippedConfig }), terminalTool = "respond")

    @Test
    fun givenTheShippedConfig_whenChecked_thenEveryBuiltInAndEveryArgumentIsDescribed() {
        assertEquals(emptyList<String>(), ToolDescriptions.problems(shippedConfig, builtIns))
    }

    @Test
    fun givenTheBuiltInHandlers_whenRead_thenNoneCarriesWordingOfItsOwn() {
        // The wording is the config's; a handler that kept its own would silently be overridden.
        for (handler in builtIns) {
            assertEquals("${handler.toolName} description", "", handler.description)
            argumentsOf(handler.parametersSchema).forEach { (name, property) ->
                assertTrue("${handler.toolName}.$name", "description" !in property)
            }
        }
    }

    @Test
    fun givenTheShippedConfig_whenListingTools_thenEachDefinitionCarriesItsDescriptionAndArguments() {
        val readFile = definitions().first { it.name == "read_file" }

        assertEquals("Read the contents of a file", readFile.description)
        assertEquals(
            mapOf("type" to "string", "description" to "Project-relative path of the file to read."),
            argumentsOf(readFile.parametersSchema)["file_path"],
        )
        assertEquals(listOf("file_path"), readFile.parametersSchema!!["required"])
    }

    @Test
    fun givenNewWording_whenListingTools_thenItIsSentWithNoCodeChange() {
        val config = shippedWith("tool_descriptions.yml") {
            it.replace("    description: Read the contents of a file", "    description: Lee el contenido de un archivo")
                .replace("file_path: Project-relative path of the file to read.", "file_path: Ruta del archivo.")
        }

        val readFile = definitions(config).first { it.name == "read_file" }

        assertEquals("Lee el contenido de un archivo", readFile.description)
        assertEquals("Ruta del archivo.", argumentsOf(readFile.parametersSchema)["file_path"]!!["description"])
    }

    @Test
    fun givenARenamedTerminalTool_whenListingTools_thenItsDescriptionNamesIt() {
        val answer = PromptToolCatalog.definitions(tools, "answer", shippedConfig).last()

        assertEquals("answer", answer.name)
        assertTrue(answer.description.contains("calling answer with no \"message\""))
    }

    @Test
    fun givenABuiltInHandler_whenApproving_thenTheDialogShowsTheDescriptionTheModelReads() {
        val runApp = builtIns.first { it.toolName == "run_app" }

        val shown = ToolDescriptions.describe(shippedConfig, "respond", runApp)

        assertEquals(definitions().first { it.name == "run_app" }.description, shown)
    }

    @Test
    fun givenAContributedDefinition_whenApplying_thenItPassesThroughUntouched() {
        // Another plugin's tool brings its own wording; the config never rewrites it.
        val contributed = ToolDefinition("mcp_files_search", "Search a remote index.", emptyMap())

        val out = ToolDescriptions.apply(shippedConfig, "respond", listOf(contributed), setOf("read_file"))

        assertSame(contributed, out.single())
    }

    @Test
    fun givenABuiltInMissingFromTheConfig_whenListingTools_thenTheFailureNamesTheFileAndTool() {
        val config = shippedWith("tool_descriptions.yml") {
            it.replace(Regex("(?s)  gradle_sync:\n.*?\n(?=  generate_from_template:)"), "")
        }

        val error = assertThrows(IllegalArgumentException::class.java) { definitions(config) }

        assertEquals(
            "tool_descriptions.yml: built_in_tools.gradle_sync is missing; every built-in tool needs a description",
            error.message,
        )
    }

    @Test
    fun givenAnUndescribedArgument_whenChecked_thenItIsNamed() {
        val config = shippedWith("tool_descriptions.yml") {
            it.replace("      content: The file's full contents.\n", "")
        }

        assertEquals(
            listOf("tool_descriptions.yml: built_in_tools.create_file.arguments.content is missing"),
            ToolDescriptions.problems(config, builtIns),
        )
    }

    @Test
    fun givenAMisspelledArgument_whenChecked_thenTheRealArgumentIsReportedMissing() {
        val config = shippedWith("tool_descriptions.yml") {
            it.replace("      replace_all: Replace", "      replace_al: Replace")
        }

        val problems = ToolDescriptions.problems(config, builtIns)

        assertEquals(
            listOf("tool_descriptions.yml: built_in_tools.edit_file.arguments.replace_all is missing"),
            problems,
        )
    }

    @Test
    fun givenAnArgumentTheToolDoesNotTake_whenChecked_thenItIsReported() {
        // Describing an argument the code never declares teaches the model one it cannot pass.
        val config = shippedWith("tool_descriptions.yml") {
            it.replace(
                "      file_path: Project-relative path of the file to read.\n",
                "      file_path: Project-relative path of the file to read.\n      encoding: Text encoding.\n",
            )
        }

        assertEquals(
            listOf("tool_descriptions.yml: built_in_tools.read_file.arguments.encoding: read_file takes no argument encoding"),
            ToolDescriptions.problems(config, builtIns),
        )
    }

    @Test
    fun givenAToolNoBuiltInHas_whenChecked_thenTheStrayEntryIsReported() {
        // A misspelled tool name would otherwise describe nothing while the real tool goes unworded.
        val config = shippedWith("tool_descriptions.yml") {
            it + "  delete_file:\n    description: Delete a file\n"
        }

        assertEquals(
            listOf("tool_descriptions.yml: built_in_tools.delete_file: no built-in tool is named delete_file"),
            ToolDescriptions.problems(config, builtIns),
        )
    }

    @Test
    fun givenATypoInADescription_whenChecked_thenItIsReportedByItsPath() {
        val config = shippedWith("tool_descriptions.yml") {
            it.replace("calling {{TERMINAL_TOOL}}", "calling {{TERMINAL_TOLL}}")
        }

        assertEquals(
            listOf("tool_descriptions.yml: terminal_tool.description: unknown name {{TERMINAL_TOLL}}"),
            ToolDescriptions.problems(config, builtIns),
        )
    }

    private fun definitions(config: AgentPromptConfig = shippedConfig) =
        PromptToolCatalog.definitions(tools, "respond", config)

    @Suppress("UNCHECKED_CAST")
    private fun argumentsOf(schema: Map<String, Any>?): Map<String, Map<String, Any>> =
        (schema?.get("properties") as? Map<String, Map<String, Any>>).orEmpty()
}
