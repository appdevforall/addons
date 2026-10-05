package com.itsaky.androidide.plugins.aiagentopenai.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The system prompt. The tool-call envelope is what the caller parses back out of the reply, so a
 * paraphrase of it would produce replies nothing reads. Also: its wording changes by editing
 * `assets/prompts/` alone, and a typo is caught, by file.
 */
class OpenAiSystemPromptTest {

    private fun build(request: SystemPromptRequest, config: OpenAiPromptConfig = shippedConfig) =
        OpenAiSystemPrompt.build(request, config)

    private fun request(
        syntax: String? = """<tool_call>{"tool":"NAME","args":{}}</tool_call>""",
        tools: List<ToolDefinition> = listOf(
            ToolDefinition("read_file", "Read a file", emptyMap()),
            ToolDefinition("respond", "Finish the task", emptyMap()),
        ),
        examplePath: String? = "app/src/main/java/com/example/MainActivity.kt",
    ) = SystemPromptRequest(tools, syntax, examplePath)

    @Test
    fun givenAToolCallSyntax_whenBuilt_thenItAppearsVerbatim() {
        val syntax = """@@CALL{"tool":"NAME"}@@"""
        assertTrue(build(request(syntax = syntax)).contains(syntax))
    }

    @Test
    fun givenTools_whenBuilt_thenEachNameAndDescriptionIsListed() {
        val prompt = build(request())
        assertTrue(prompt.contains("- read_file: Read a file"))
        assertTrue(prompt.contains("- respond: Finish the task"))
    }

    @Test
    fun givenAnExamplePath_whenBuilt_thenItIsUsedInTheExamples() {
        val prompt = build(request(examplePath = "src/Foo.kt"))
        assertTrue(prompt.contains(""""file_path":"src/Foo.kt""""))
        // The stem drives the search_project example.
        assertTrue(prompt.contains(""""query":"Foo""""))
    }

    @Test
    fun givenADotfileExamplePath_whenBuilt_thenTheStemIsItsWholeName() {
        assertTrue(build(request(examplePath = "app/.gitignore")).contains(""""query":".gitignore""""))
    }

    @Test
    fun givenNoTools_whenBuilt_thenThePromptStillBuilds() {
        val prompt = build(request(tools = emptyList()))
        assertTrue(prompt.contains("AVAILABLE TOOLS:"))
    }

    @Test
    fun givenNoToolCallSyntax_whenBuilt_thenTheEnvelopeIsNeverTaught() {
        // A null syntax means the caller parses no envelope; an example of one is a call that
        // would not run.
        val prompt = build(request(syntax = null))

        assertFalse(prompt.contains("<tool_call>"))
        assertTrue(prompt.contains("AVAILABLE TOOLS:"))
        assertTrue(prompt.contains("WORKFLOW — follow these steps only when"))
    }

    @Test
    fun givenEitherMode_whenBuilt_thenExactlyOneWayToCallAToolIsTaught() {
        // Teaching both (ADFA-5410) is how one call runs twice: the provider carries it and the
        // text copy is extracted as a second call.
        listOf(request(), request(syntax = null)).forEach { request ->
            assertEquals(1, build(request).split("TOOL CALL FORMAT").size - 1)
        }
    }

    @Test
    fun givenNoExamplePath_whenBuilt_thenTheExamplesStillCarryAConcretePath() {
        val prompt = build(request(examplePath = null))

        assertTrue(prompt.contains(""""file_path":"app/src/main/java/com/example/MainActivity.kt""""))
    }

    @Test
    fun givenAToolCallSyntax_whenBuilt_thenNativeFunctionCallingIsForbidden() {
        // In envelope mode nothing reads the provider's channel, so a model using it would hang.
        val prompt = build(request())
        assertTrue(prompt.contains("native function-calling channel"))
    }

    @Test
    fun givenNoToolCallSyntax_whenBuilt_thenTheFunctionCallingApiIsNamedInstead() {
        // The reverse of the rule above: the tools are declared, so the channel is the only way in
        // and forbidding it would leave the model no way to call anything.
        val prompt = build(request(syntax = null))

        assertTrue(prompt.contains("function-calling"))
        assertFalse(prompt.contains("Do NOT use your provider's native function-calling channel"))
    }

    @Test
    fun givenAnyRequest_whenBuilt_thenOneToolCallPerReplyIsRequired() {
        // Conditional on calling a tool at all: stated absolutely it contradicts the rule below
        // that a question is answered in the reply itself, which is most of the traffic now.
        val prompt = build(request())

        assertTrue(prompt.contains("When you call a tool, emit ONE per reply"))
        assertFalse(prompt.contains("Emit ONE tool call per reply"))
    }

    @Test
    fun givenEitherMode_whenBuilt_thenAnOffDomainRequestIsNeverDeclined() {
        // A prompt whose stated goal was only building Android apps left a general question no
        // legal path through it, and the model declined rather than answer (ADFA-6223).
        listOf(request(), request(syntax = null)).forEach { request ->
            val prompt = build(request)

            assertTrue(prompt.contains("SCOPE:"))
            assertTrue(
                prompt.contains("Never decline a request on the grounds that it is not about Android")
            )
        }
    }

    @Test
    fun givenEitherMode_whenBuilt_thenTheBuildWorkflowIsIntroducedConditionally() {
        // Every step presumes an app-build task, so stated unconditionally it is the refusal above.
        listOf(request(), request(syntax = null)).forEach { request ->
            val prompt = build(request)

            assertTrue(prompt.contains("WORKFLOW — follow these steps only when the user tells you to build"))
        }
    }

    @Test
    fun givenSeveralTools_whenBuilt_thenNoLineIsIndented() {
        // The Kotlin version interpolated the tool list into a raw string, which defeated
        // trimIndent and sent most of the prompt indented by eight spaces.
        listOf(request(), request(syntax = null)).forEach {
            assertFalse(build(it).lines().any { line -> line.startsWith(" ") })
        }
    }

    @Test
    fun givenNativeCalling_whenBuilt_thenTheFlagLeavesNoTrace() {
        assertFalse(build(request(syntax = null)).contains("{{"))
    }

    @Test
    fun givenTheShippedFiles_whenChecked_thenThePromptRendersForEveryRequest() {
        // A typo fails the render, so the shipped set must have none.
        assertEquals(emptyList<String>(), OpenAiSystemPrompt.problems(shippedConfig))
    }

    @Test
    fun givenATypo_whenChecked_thenItIsReportedByItsFileAndPathRatherThanDroppingText() {
        // The file-per-section design this replaced dropped a file with a typo silently.
        val config = shippedWith("rules.yml") {
            it.replace("Never fabricate tool output.", "Never fabricate {{TOOL_LIST}} output.")
        }

        assertEquals(
            listOf("rules.yml: rules[0].items[6]: unknown name {{TOOL_LIST}}"),
            OpenAiSystemPrompt.problems(config),
        )
    }

    @Test
    fun givenANewRule_whenBuilt_thenItIsSentAmongTheRulesWithNoCodeChange() {
        val config = shippedWith("rules.yml") { it + "      - NEW RULE.\n" }

        val prompt = build(request(), config)

        assertTrue(prompt.indexOf("RULES:") < prompt.indexOf("- NEW RULE."))
        assertTrue(prompt.indexOf("NEW RULE.") < prompt.indexOf("TOOL CALL FORMAT"))
    }

    @Test
    fun givenANewPriorityGroup_whenBuilt_thenItIsSentAsItsOwnBlockAfterTheRules() {
        val config = shippedWith("rules.yml") { it + "  - heading: OPTIONAL\n    items:\n      - Be brief.\n" }

        val prompt = build(request(), config)

        assertTrue(prompt.contains("\n\nOPTIONAL:\n- Be brief.\n\nTOOL CALL FORMAT"))
    }

    @Test
    fun givenANewIdentity_whenBuilt_thenTheToneChangesWithNoCodeChange() {
        val config = shippedWith("agent.yml") {
            it.replace(Regex("(?s)identity: >-\n.*?\n\n"), "identity: Eres el asistente de CodeOnTheGo.\n\n")
        }

        assertTrue(build(request(), config).startsWith("Eres el asistente de CodeOnTheGo.\n\nSCOPE:"))
    }

    @Test
    fun givenAReorderedLayout_whenBuilt_thenTheSectionsFollowIt() {
        // The order the model reads things in is config too, not code.
        val config = shippedWith("layout.yml") {
            it.replace("    {{IDENTITY}}\n\n", "    {{WORKFLOW_CLOSING}}\n    {{IDENTITY}}\n\n")
        }

        assertTrue(build(request(), config).startsWith("Skip every one of those steps"))
    }
}
