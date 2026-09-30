package com.itsaky.androidide.plugins.aicore.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentPromptConfigParser]: a mistake in a prompt file is refused naming that file
 * and the key, rather than reaching the model as a prompt with a hole in it.
 */
class AgentPromptConfigParserTest {

    @Test
    fun givenTheShippedFiles_whenParsing_thenEveryTextIsLabelledWithItsOwnFileAndPath() {
        assertEquals("agent.yml: identity", shippedConfig.identity.label)
        assertEquals("rules.yml: rules[0].items[1]", shippedConfig.rules[0].items[1].label)
        assertEquals("tools.yml: tool_call_format.example", shippedConfig.toolCallFormat.example.label)
        assertEquals("layout.yml: layout.system_prompt", shippedConfig.layout.systemPrompt.label)
    }

    @Test
    fun givenABlockScalar_whenParsing_thenItsTrailingNewlineIsDropped() {
        assertTrue(shippedConfig.layout.systemPrompt.template.endsWith("{{LAYOUT_IDE_CONTEXT}}"))
    }

    @Test
    fun givenAFoldedScalar_whenParsing_thenItsLinesAreJoinedIntoOneSentence() {
        // Source line wraps must not reach the model as newlines mid-sentence.
        assertTrue(shippedConfig.identity.template.contains("not only Android questions."))
        assertTrue('\n' !in shippedConfig.identity.template)
    }

    @Test
    fun givenAMissingNestedKey_whenParsing_thenItIsNamedWithItsFileAndPath() {
        assertRefused("tools.yml: tool_call_format.example_heading is missing", "tools.yml") {
            it.replace(Regex("(?m)^  example_heading: .*\n"), "")
        }
    }

    @Test
    fun givenAMissingTopLevelKey_whenParsing_thenItIsReportedAgainstTheEntryFile() {
        // No file holds it, so the entry file, which decides what is read, is the one to fix.
        assertRefused("agent.yml: rules is missing", "rules.yml") { "other: x\n" }
    }

    @Test
    fun givenAMisspelledKey_whenParsing_thenTheRealKeyIsReportedMissing() {
        val error = refused("rules.yml") { it.replace("    items:\n      - Reply", "    itmes:\n      - Reply") }

        assertTrue(error.message!!.startsWith("rules.yml: rules[0].items is missing"))
    }

    @Test
    fun givenAnExtraNestedKey_whenParsing_thenItIsRefusedAsUnknown() {
        assertRefused("tools.yml: tools: unknown key tone; expected heading", "tools.yml") {
            it.replace("tools:\n  heading: Tools", "tools:\n  heading: Tools\n  tone: friendly")
        }
    }

    @Test
    fun givenAnExtraTopLevelKey_whenParsing_thenTheFileHoldingItIsNamed() {
        val error = refused("layout.yml") { "$it\ntone: friendly\n" }

        assertTrue(error.message!!.startsWith("layout.yml: unknown key tone; expected "))
    }

    @Test
    fun givenAnUnquotedNumber_whenParsing_thenItIsRefusedAsNotText() {
        assertRefused("tools.yml: tools.heading expected text; quote it", "tools.yml") {
            it.replace("  heading: Tools", "  heading: 42")
        }
    }

    @Test
    fun givenAPriorityWithNoRules_whenParsing_thenItIsRefused() {
        assertRefused("rules.yml: rules[1].items is empty", "rules.yml") {
            it.replace(Regex("(?s)(- heading: IMPORTANT\n    items:).*?(\n  - heading)"), "$1 []$2")
        }
    }

    @Test
    fun givenANewerSchemaVersion_whenParsing_thenItIsRefusedNamingBoth() {
        assertRefused("agent.yml: schema_version is 3, but this ai-core reads 2", "agent.yml") {
            it.replace("schema_version: 2", "schema_version: 3")
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
