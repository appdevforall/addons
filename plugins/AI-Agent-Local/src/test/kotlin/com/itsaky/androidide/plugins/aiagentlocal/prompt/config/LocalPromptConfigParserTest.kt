package com.itsaky.androidide.plugins.aiagentlocal.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LocalPromptConfigParser]: a mistake in a prompt file is refused naming that file
 * and the key, rather than reaching the model as a prompt with a hole in it.
 */
class LocalPromptConfigParserTest {

    @Test
    fun givenTheShippedFiles_whenParsing_thenEveryTextIsLabelledWithItsOwnFileAndPath() {
        assertEquals("agent.yml: identity", shippedConfig.identity.label)
        assertEquals("rules.yml: rules[0].items[2]", shippedConfig.rules[0].items[2].label)
        assertEquals(
            "tools.yml: tool_call_format.text.examples[1].call",
            shippedConfig.toolCallFormat.text.examples[1].call.label,
        )
        assertEquals("layout.yml: layout.system_prompt", shippedConfig.layout.systemPrompt.label)
    }

    @Test
    fun givenAFoldedScalar_whenParsing_thenItsLinesAreJoinedIntoOneSentence() {
        // Source line wraps must not reach the model as newlines mid-sentence.
        val purpose = shippedConfig.toolCallFormat.text.examples[4].purpose.template

        assertTrue(purpose.endsWith("(NOT one call per line)"))
        assertTrue('\n' !in purpose)
    }

    @Test
    fun givenAMissingNestedKey_whenParsing_thenItIsNamedWithItsFileAndPath() {
        assertRefused("tools.yml: tool_call_format.text.examples_heading is missing", "tools.yml") {
            it.replace(Regex("(?m)^    examples_heading: .*\n"), "")
        }
    }

    @Test
    fun givenAMissingTopLevelKey_whenParsing_thenItIsReportedAgainstTheEntryFile() {
        // No file holds it, so the entry file, which decides what is read, is the one to fix.
        assertRefused("agent.yml: rules is missing", "rules.yml") { "other: x\n" }
    }

    @Test
    fun givenANativeFormat_whenParsing_thenItIsRefusedAsUnknown() {
        // This backend only calls through the text protocol; a native format would never be sent.
        assertRefused("tools.yml: tool_call_format: unknown key native; expected text", "tools.yml") {
            it.replace("tool_call_format:\n", "tool_call_format:\n  native: Call natively.\n")
        }
    }

    @Test
    fun givenAnUnquotedNumber_whenParsing_thenItIsRefusedAsNotText() {
        assertRefused("tools.yml: tools.heading expected text; quote it", "tools.yml") {
            it.replace("  heading: Tools", "  heading: 42")
        }
    }

    @Test
    fun givenAGroupWithNoRules_whenParsing_thenItIsRefused() {
        assertRefused("rules.yml: rules[0].items is empty", "rules.yml") {
            it.replace(Regex("(?s)    items:\n.*"), "    items: []\n")
        }
    }

    @Test
    fun givenANewerSchemaVersion_whenParsing_thenItIsRefusedNamingBoth() {
        assertRefused("agent.yml: schema_version is 2, but this local-model plugin reads 1", "agent.yml") {
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
