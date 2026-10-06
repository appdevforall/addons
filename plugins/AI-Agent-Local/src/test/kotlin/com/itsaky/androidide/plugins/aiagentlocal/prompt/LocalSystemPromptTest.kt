package com.itsaky.androidide.plugins.aiagentlocal.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LocalSystemPrompt]. The 1–3B models this prompt is written for are the ones most
 * likely to refuse an off-domain question, and the least likely to recover from a mangled envelope.
 * Also: its wording changes by editing `assets/prompts/` alone, and a typo is caught, by file.
 */
class LocalSystemPromptTest {

    private companion object {
        const val SYNTAX = """<tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>"""
    }

    private fun prompt(
        toolCallSyntax: String? = SYNTAX,
        examplePath: String? = "app/src/main/java/com/example/MainActivity.kt",
        config: LocalPromptConfig = shippedConfig,
    ) = LocalSystemPrompt.build(
        SystemPromptRequest(
            listOf(
                ToolDefinition("read_file", "Read a file", emptyMap()),
                ToolDefinition("respond", "Finish the task", emptyMap()),
            ),
            toolCallSyntax,
            examplePath,
        ),
        config,
    )

    @Test
    fun givenAnEnvelopeSyntax_whenBuilding_thenItIsReproducedVerbatim() {
        assertTrue(prompt().contains(SYNTAX))
    }

    @Test
    fun givenNoEnvelopeSyntax_whenBuilding_thenTheEnvelopeIsNeverTaught() {
        // The caller parses no envelope here, so an example of one is a call that would not run.
        assertFalse(prompt(toolCallSyntax = null).contains("<tool_call>"))
    }

    @Test
    fun givenEitherMode_whenBuilding_thenEachToolIsListed() {
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(toolCallSyntax = syntax)

            assertTrue(prompt.contains("- read_file: Read a file"))
            assertTrue(prompt.contains("- respond: Finish the task"))
        }
    }

    @Test
    fun givenEitherMode_whenBuilding_thenAnOffDomainRequestIsNeverDeclined() {
        // A prompt that named only Android left a general question no legal path through it, and
        // the model declined rather than answer (ADFA-6223).
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(toolCallSyntax = syntax)

            assertTrue(prompt.contains("You answer anything the user asks, not only Android questions"))
            assertTrue(
                prompt.contains("Never refuse a question because it is not about Android")
            )
        }
    }

    @Test
    fun givenEitherMode_whenBuilding_thenTheNonRefusalRuleSaysHowToAnswer() {
        // "Reply with exactly ONE tool call" leaves no way to answer in prose, so a rule that only
        // forbids refusing without naming "respond" asks for a reply the loop cannot deliver.
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(toolCallSyntax = syntax)
            val rule = prompt.lineSequence().first { it.contains("Never refuse a question") }

            assertTrue(rule, rule.contains(""""respond""""))
        }
    }

    @Test
    fun givenNoExamplePath_whenBuilding_thenTheExamplesStillCarryAConcretePath() {
        assertTrue(
            prompt(examplePath = null)
                .contains(""""file_path":"app/src/main/java/com/example/MainActivity.kt"""")
        )
    }

    @Test
    fun givenAnExamplePath_whenBuilding_thenTheBareNameAndStemAreDerivedFromIt() {
        val prompt = prompt(examplePath = "src/Foo.kt")

        assertTrue(prompt.contains(""""file_path":"Foo.kt""""))
        assertTrue(prompt.contains(""""query":"Foo""""))
    }

    @Test
    fun givenADotfileExamplePath_whenBuilding_thenTheStemIsItsWholeName() {
        assertTrue(prompt(examplePath = "app/.gitignore").contains(""""query":".gitignore""""))
    }

    @Test
    fun givenEitherMode_whenBuilding_thenExactlyOneWayToCallAToolIsTaught() {
        // Teaching both (ADFA-5410) is how one call runs twice: the provider carries it and the
        // text copy is extracted as a second call.
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(toolCallSyntax = syntax)

            assertEquals(
                if (syntax == null) 0 else 1,
                prompt.split("TOOL CALL FORMAT").size - 1,
            )
        }
    }

    @Test
    fun givenSeveralTools_whenBuilding_thenNoLineIsIndented() {
        // The Kotlin version interpolated the tool list into a raw string, which defeated
        // trimIndent and sent the rules indented by eight spaces.
        listOf(SYNTAX, null).forEach { syntax ->
            assertFalse(prompt(toolCallSyntax = syntax).lines().any { it.startsWith(" ") })
        }
    }

    @Test
    fun givenTheShippedFiles_whenChecked_thenThePromptRendersForEveryRequest() {
        // A typo fails the render, so the shipped set must have none.
        assertEquals(emptyList<String>(), LocalSystemPrompt.problems(shippedConfig))
    }

    @Test
    fun givenATypo_whenChecked_thenItIsReportedByItsFileAndPathRatherThanDroppingText() {
        // The file-per-section design this replaced dropped a file with a typo silently.
        val config = shippedWith("tools.yml") { it.replace("{{EXAMPLE_FILE_NAME}}", "{{EXAMPLE_NAME}}") }

        assertEquals(
            listOf("tools.yml: tool_call_format.text.examples[1].call: unknown name {{EXAMPLE_NAME}}"),
            LocalSystemPrompt.problems(config),
        )
    }

    @Test
    fun givenANewRule_whenBuilding_thenItIsSentAmongTheRulesWithNoCodeChange() {
        val config = shippedWith("rules.yml") { it + "      - NEW RULE.\n" }

        val prompt = prompt(config = config)

        assertTrue(prompt.indexOf("Rules:") < prompt.indexOf("- NEW RULE."))
        assertTrue(prompt.indexOf("- NEW RULE.") < prompt.indexOf("Tools:"))
    }

    @Test
    fun givenANewIdentity_whenBuilding_thenTheToneChangesWithNoCodeChange() {
        val config = shippedWith("agent.yml") {
            it.replace(Regex("(?s)identity: >-\n.*?\n\n"), "identity: Eres un asistente de programación.\n\n")
        }

        assertTrue(prompt(config = config).startsWith("Eres un asistente de programación.\n\nRules:"))
    }

    @Test
    fun givenAReorderedLayout_whenBuilding_thenTheSectionsFollowIt() {
        // The order the model reads things in is config too, not code.
        val config = shippedWith("layout.yml") {
            it.replace("    {{IDENTITY}}\n\n", "    {{TOOLS_HEADING}}!\n    {{IDENTITY}}\n\n")
        }

        assertTrue(prompt(config = config).startsWith("Tools!\nYou are a coding assistant"))
    }
}
