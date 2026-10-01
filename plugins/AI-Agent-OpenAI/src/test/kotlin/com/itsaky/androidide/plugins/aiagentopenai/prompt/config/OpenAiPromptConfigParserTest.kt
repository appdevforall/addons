package com.itsaky.androidide.plugins.aiagentopenai.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OpenAiPromptConfigParser]: a mistake in a prompt file is refused naming that file
 * and the key, rather than reaching the model as a prompt with a hole in it.
 */
class OpenAiPromptConfigParserTest {

    @Test
    fun givenTheShippedFiles_whenParsing_thenEveryTextIsLabelledWithItsOwnFileAndPath() {
        assertEquals("agent.yml: identity", shippedConfig.identity.label)
        assertEquals("scope.yml: scope.items[1]", shippedConfig.scope.items[1].label)
        assertEquals("rules.yml: rules[0].items[2]", shippedConfig.rules[0].items[2].label)
        assertEquals("workflow.yml: workflow.steps[1]", shippedConfig.workflow.steps[1].label)
        assertEquals(
            "tools.yml: tool_call_format.text.examples[2].call",
            shippedConfig.toolCallFormat.text.examples[2].call.label,
        )
        assertEquals("layout.yml: layout.system_prompt", shippedConfig.layout.systemPrompt.label)
    }

    @Test
    fun givenAFoldedScalar_whenParsing_thenItsLinesAreJoinedIntoOneSentence() {
        // Source line wraps must not reach the model as newlines mid-sentence.
        val rule = shippedConfig.rules[0].items[0].template

        assertTrue(rule.startsWith("When you call a tool, emit ONE per reply, then stop and wait."))
        assertTrue('\n' !in rule)
    }

    @Test
    fun givenALiteralBlock_whenParsing_thenItsLineBreaksAreKept() {
        val native = shippedConfig.toolCallFormat.native.template

        assertEquals(2, native.lines().size)
    }

    @Test
    fun givenAMissingNestedKey_whenParsing_thenItIsNamedWithItsFileAndPath() {
        assertRefused("tools.yml: tool_call_format.text.no_native_channel is missing", "tools.yml") {
            it.replace(Regex("(?m)^    no_native_channel: >-\n(      .*\n)+"), "")
        }
    }

    @Test
    fun givenAMissingTopLevelKey_whenParsing_thenItIsReportedAgainstTheEntryFile() {
        // No file holds it, so the entry file, which decides what is read, is the one to fix.
        assertRefused("agent.yml: scope is missing", "scope.yml") { "other: x\n" }
    }

    @Test
    fun givenAnExtraNestedKey_whenParsing_thenItIsRefusedAsUnknown() {
        assertRefused("tools.yml: tools: unknown key tone; expected heading", "tools.yml") {
            it.replace("tools:\n  heading: AVAILABLE TOOLS", "tools:\n  heading: AVAILABLE TOOLS\n  tone: friendly")
        }
    }

    @Test
    fun givenAnExampleWithoutItsCall_whenParsing_thenTheExampleIsNamed() {
        assertRefused("tools.yml: tool_call_format.text.examples[0].call is missing", "tools.yml") {
            it.replace(Regex("(?m)^        call: '<tool_call>\\{\"tool\":\"respond\".*\n"), "")
        }
    }

    @Test
    fun givenAnUnquotedNumber_whenParsing_thenItIsRefusedAsNotText() {
        assertRefused("scope.yml: scope.heading expected text; quote it", "scope.yml") {
            it.replace("  heading: SCOPE", "  heading: 42")
        }
    }

    @Test
    fun givenAWorkflowWithNoSteps_whenParsing_thenItIsRefused() {
        assertRefused("workflow.yml: workflow.steps is empty", "workflow.yml") {
            it.replace(Regex("(?s)  steps:\n.*?(?=  closing:)"), "  steps: []\n")
        }
    }

    @Test
    fun givenANewerSchemaVersion_whenParsing_thenItIsRefusedNamingBoth() {
        assertRefused("agent.yml: schema_version is 2, but this OpenAI plugin reads 1", "agent.yml") {
            it.replace("schema_version: 1", "schema_version: 2")
        }
    }

    @Test
    fun givenBrokenYaml_whenParsing_thenTheFileNameAndPositionAreReported() {
        val error = refused("rules.yml") { "rules: [unclosed" }

        assertTrue(error.message!!.startsWith("rules.yml: "))
        assertTrue(error.message!!.contains("line"))
    }

    @Test
    fun givenADuplicateKeyInOneFile_whenParsing_thenItIsRefused() {
        // YAML would otherwise keep the second silently, and an edit to the first would do nothing.
        refused("agent.yml") { "$it\nidentity: again\n" }
    }

    private fun refused(file: String, edit: (String) -> String): PromptConfigException =
        assertThrows(PromptConfigException::class.java) { shippedWith(file, edit) }

    private fun assertRefused(message: String, file: String, edit: (String) -> String) =
        assertEquals(message, refused(file, edit).message)
}
