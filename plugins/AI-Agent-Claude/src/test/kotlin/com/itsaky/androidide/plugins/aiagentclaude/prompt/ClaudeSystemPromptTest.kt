package com.itsaky.androidide.plugins.aiagentclaude.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.aiagentclaude.prompt.config.ClaudePromptConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ClaudeSystemPrompt]. Focus: the prompt teaches exactly one way to call a tool;
 * teaching both (ADFA-5410) is how a call ends up written as text that nothing runs. Also: its
 * wording changes by editing `assets/prompts/` alone, and a typo is caught, by file.
 */
class ClaudeSystemPromptTest {

    private companion object {
        /** The rule priorities, highest first; `rules.yml` may use only these headings. */
        val PRIORITIES = listOf("CRITICAL", "IMPORTANT", "MANDATORY", "OPTIONAL")

        const val SYNTAX = """<tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>"""
    }

    private val tools = listOf(ToolDefinition("read_file", "Read a file", emptyMap()))

    private fun prompt(
        toolCallSyntax: String?,
        tools: List<ToolDefinition> = this.tools,
        config: ClaudePromptConfig = shippedConfig,
        examplePath: String = "app/src/main/java/com/example/MainActivity.kt",
    ) = ClaudeSystemPrompt.build(
        SystemPromptRequest(tools, toolCallSyntax, examplePath),
        config,
    )

    @Test
    fun givenAnEnvelopeSyntax_whenBuilding_thenItIsReproducedVerbatim() {
        assertTrue(prompt(SYNTAX).contains(SYNTAX))
    }

    @Test
    fun givenNoEnvelopeSyntax_whenBuilding_thenTheEnvelopeIsNeverTaught() {
        // The caller parses no envelope here, so an example of one is a call that would not run.
        assertFalse(prompt(null).contains("<tool_call>"))
    }

    @Test
    fun givenNoEnvelopeSyntax_whenBuilding_thenTheFunctionCallingApiIsNamedInstead() {
        assertTrue(prompt(null).contains("function-calling"))
    }

    @Test
    fun givenEitherMode_whenBuilding_thenTheToolsAreAlwaysListed() {
        listOf(SYNTAX, null).forEach { syntax ->
            assertTrue("tools must be listed either way", prompt(syntax).contains("read_file"))
        }
    }

    @Test
    fun givenEitherMode_whenBuilding_thenAnOffDomainRequestIsNeverDeclined() {
        // A prompt whose stated goal was only building Android apps left a general question no
        // legal path through it, and the model declined rather than answer (ADFA-6223).
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(syntax)

            assertTrue(prompt.contains("SCOPE:"))
            assertTrue(
                prompt.contains("Never decline a request on the grounds that it is not about Android")
            )
        }
    }

    @Test
    fun givenEitherMode_whenBuilding_thenTheBuildWorkflowIsIntroducedConditionally() {
        // Every step presumes an app-build task, so stated unconditionally it is the refusal above.
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(syntax)

            assertTrue(prompt.contains("WORKFLOW — follow these steps only when the user tells you to build"))
        }
    }

    @Test
    fun givenAnyRequest_whenBuilding_thenOneToolCallPerReplyIsRequiredOnlyWhenAToolIsCalled() {
        // Stated absolutely it contradicts the rule below that a question is answered in the reply
        // itself, which is most of the traffic now.
        val prompt = prompt(SYNTAX)

        assertTrue(prompt.contains("When you call a tool, emit ONE per reply"))
        assertFalse(prompt.contains("Emit ONE tool call per reply"))
    }

    @Test
    fun givenEitherMode_whenBuilding_thenTheWorkflowDoesNotContradictTheRuleAgainstWalkingTheTree() {
        // WORKFLOW step 2 used to say "List files to understand the project structure", against a
        // RULE forbidding exactly that. A run followed the workflow and spent 7 of 16 turns on it.
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(syntax)

            assertFalse(prompt.contains("2. List files"))
            assertTrue(prompt.contains("Never walk the tree with repeated list_files calls"))
        }
    }

    @Test
    fun givenSeveralTools_whenBuilding_thenNoLineIsIndented() {
        // The Kotlin version interpolated the tool list into a raw string, which defeated
        // trimIndent and sent 25 of 44 lines indented by eight spaces.
        val many = tools + ToolDefinition("respond", "Answer the user", emptyMap())

        listOf(SYNTAX, null).forEach { syntax ->
            assertFalse(prompt(syntax, many).lines().any { it.startsWith(" ") })
        }
    }

    @Test
    fun givenNativeCalling_whenBuilding_thenOnlyTheNativeFormatIsSentAndNoTagLeaksThrough() {
        val prompt = prompt(null)

        assertTrue(prompt.contains("TOOL CALL FORMAT — the tools above are declared to you"))
        assertFalse(prompt.contains("{{"))
    }

    @Test
    fun givenTheExamplePath_whenBuilding_thenTheSearchExampleUsesItsFileStem() {
        assertTrue(prompt(SYNTAX).contains("""{"query":"MainActivity"}"""))
    }

    @Test
    fun givenADotfileExamplePath_whenBuilding_thenTheSearchExampleUsesItsWholeName() {
        assertTrue(prompt(SYNTAX, examplePath = "app/.gitignore").contains("""{"query":".gitignore"}"""))
    }

    @Test
    fun givenTheShippedRules_whenRead_thenThePrioritiesAreKnownAndInOrder() {
        // A heading the model has not been taught has no weight, and a lower one first misleads it.
        val headings = shippedConfig.rules.map { it.heading.template }

        assertTrue(headings.all { it in PRIORITIES })
        assertEquals(headings.sortedBy { PRIORITIES.indexOf(it) }, headings)
    }

    @Test
    fun givenEitherMode_whenBuilding_thenTheCriticalRulesComeFirst() {
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(syntax)

            assertTrue(prompt.indexOf("CRITICAL:") < prompt.indexOf("- When you call a tool, emit ONE"))
            assertTrue(prompt.indexOf("- Never fabricate tool output") < prompt.indexOf("IMPORTANT:"))
        }
    }

    @Test
    fun givenTheShippedLayout_whenBuilding_thenOnlyOneBlankLineSeparatesTheRuleGroups() {
        // The engine sets FIRST per list item, so {{^FIRST}} adds no blank line above the first group.
        val prompt = prompt(SYNTAX)

        assertTrue(prompt.contains("\n\nCRITICAL:"))
        assertFalse(prompt.contains("\n\n\nCRITICAL:"))
        assertTrue(prompt.contains("\n\nIMPORTANT:"))
        assertFalse(prompt.contains("\n\n\nIMPORTANT:"))
    }

    @Test
    fun givenTheShippedFiles_whenChecked_thenThePromptRendersForEveryRequest() {
        // A typo fails the render, so the shipped set must have none.
        assertEquals(emptyList<String>(), ClaudeSystemPrompt.problems(shippedConfig))
    }

    @Test
    fun givenARuleWithATypo_whenChecked_thenItIsReportedByItsFileAndPath() {
        val config = shippedWith("rules.yml") {
            it.replace("Never fabricate tool output.", "Never fabricate {{TOOL_LIST}} output.")
        }

        assertEquals(
            listOf("rules.yml: rules[0].items[1]: unknown name {{TOOL_LIST}}"),
            ClaudeSystemPrompt.problems(config),
        )
    }

    @Test
    fun givenATypoBehindTheTextProtocol_whenChecked_thenItIsStillReported() {
        // The check renders under both protocols, so the one a run rarely takes is covered too.
        val config = shippedWith("tools.yml") { it.replace("{{EXAMPLE_FILE_STEM}}", "{{EXAMPLE_STEM}}") }

        assertEquals(
            listOf("tools.yml: tool_call_format.text.examples[2].call: unknown name {{EXAMPLE_STEM}}"),
            ClaudeSystemPrompt.problems(config),
        )
    }

    @Test
    fun givenANewRule_whenBuilding_thenItIsSentAmongTheRulesWithNoCodeChange() {
        val config = shippedWith("rules.yml") {
            it.replace("  - heading: IMPORTANT\n    items:\n", "  - heading: IMPORTANT\n    items:\n      - NEW RULE.\n")
        }

        val prompt = prompt(SYNTAX, config = config)

        assertTrue(prompt.contains("IMPORTANT:\n- NEW RULE.\n- To locate a file"))
        assertTrue(prompt.indexOf("NEW RULE.") < prompt.indexOf("TOOL CALL FORMAT"))
    }

    @Test
    fun givenANewWorkflowStep_whenBuilding_thenTheStepsAreRenumbered() {
        val config = shippedWith("workflow.yml") {
            it.replace("    - Understand the user's request\n", "    - Understand the user's request\n    - Ask if unsure\n")
        }

        val prompt = prompt(SYNTAX, config = config)

        assertTrue(prompt.contains("1. Understand the user's request\n2. Ask if unsure\n3. Locate"))
        assertTrue(prompt.contains("8. Report success and what was built"))
    }

    @Test
    fun givenANewIdentity_whenBuilding_thenTheToneChangesWithNoCodeChange() {
        val config = shippedWith("agent.yml") {
            it.replace(Regex("(?s)identity: >-\n.*?\n\n"), "identity: Eres el asistente de CodeOnTheGo.\n\n")
        }

        assertTrue(prompt(null, config = config).startsWith("Eres el asistente de CodeOnTheGo.\n\nSCOPE:"))
    }

    @Test
    fun givenAReorderedLayout_whenBuilding_thenTheSectionsFollowIt() {
        // The order the model reads things in is config too, not code.
        val config = shippedWith("layout.yml") {
            it.replace("    {{IDENTITY}}\n\n", "    {{WORKFLOW_CLOSING}}\n    {{IDENTITY}}\n\n")
        }

        assertTrue(prompt(null, config = config).startsWith("Skip every one of those steps"))
    }

    @Test
    fun givenEitherMode_whenBuilding_thenExactlyOneWayToCallAToolIsTaught() {
        // Teaching both (ADFA-5410) is how one call runs twice: the provider carries it and the
        // text copy is extracted as a second call.
        listOf(SYNTAX, null).forEach { syntax ->
            assertEquals(1, prompt(syntax).split("TOOL CALL FORMAT").size - 1)
        }
    }

    @Test
    fun givenAnEnvelopeSyntax_whenBuilding_thenNativeFunctionCallingIsForbidden() {
        // In envelope mode nothing reads the provider's channel, so a model using it would hang.
        assertTrue(prompt(SYNTAX).contains("Do NOT use your provider's native function-calling channel"))
    }

    @Test
    fun givenNativeCalling_whenBuilding_thenTheNativeChannelIsNotForbidden() {
        // The tools are declared, so the channel is the only way in; forbidding it leaves none.
        assertFalse(prompt(null).contains("Do NOT use your provider's native function-calling channel"))
    }

    @Test
    fun givenNoExamplePath_whenBuilding_thenTheExamplesStillCarryAConcretePath() {
        val prompt = ClaudeSystemPrompt.build(SystemPromptRequest(tools, SYNTAX, null), shippedConfig)

        assertTrue(prompt.contains(""""file_path":"app/src/main/java/com/example/MainActivity.kt""""))
    }

    @Test
    fun givenNoTools_whenBuilding_thenThePromptStillBuilds() {
        assertTrue(prompt(SYNTAX, tools = emptyList()).contains("AVAILABLE TOOLS:"))
    }
}
