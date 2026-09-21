package com.itsaky.androidide.plugins.aiagentgemini.prompt

import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GeminiSystemPrompt]. Focus: the prompt teaches exactly one way to call a tool.
 * Teaching both (ADFA-5410) is how a call ends up written as text that nothing runs.
 */
class GeminiSystemPromptTest {

    private companion object {
        const val SYNTAX = """<tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>"""
    }

    private fun prompt(toolCallSyntax: String?) = GeminiSystemPrompt.build(
        SystemPromptRequest(
            listOf(ToolDefinition("read_file", "Read a file", emptyMap())),
            toolCallSyntax,
            "app/src/main/java/com/example/MainActivity.kt",
        )
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
    fun givenEitherMode_whenBuilding_thenTheWorkflowDoesNotContradictTheRuleAgainstWalkingTheTree() {
        // WORKFLOW step 2 used to say "List files to understand the project structure", against a
        // RULE forbidding exactly that. A run followed the workflow and spent 7 of 16 turns on it.
        listOf(SYNTAX, null).forEach { syntax ->
            val prompt = prompt(syntax)

            assertFalse(prompt.contains("2. List files"))
            assertTrue(prompt.contains("Never walk the tree with repeated list_files calls"))
        }
    }
}
