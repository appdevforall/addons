package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the behaviour/integration split: the agent's wording, tone, rules and layout change
 * by editing the `assets/prompts/` files alone, and a typo is caught, by file, before a turn hits it.
 */
class SystemPromptConfigTest {

    private val tools = listOf(ToolDefinition("read_file", "Read a file.", emptyMap()))

    /** A backend with no prompt of its own, so the general prompt is what gets sent. */
    private val noPrompt = object : BackendPrompts {
        override fun callsToolsNatively(): Boolean = false
        override fun systemPrompt(request: SystemPromptRequest): String? = null
    }

    /** A backend with its own prompt, so only the IDE CONTEXT block is added. */
    private val ownPrompt = object : BackendPrompts {
        override fun callsToolsNatively(): Boolean = true
        override fun systemPrompt(request: SystemPromptRequest): String = "I am Gemini."
    }

    @Test
    fun givenTheShippedConfig_whenChecked_thenEveryLayoutRenders() {
        // A typo fails every chat turn, so the shipped file must render with every section open.
        assertEquals(emptyList<String>(), SystemPromptRenderer.problems(shippedConfig))
    }

    @Test
    fun givenANewRule_whenCreating_thenItIsSentUnderItsPriority() {
        val config = shippedWith("rules.yml") {
            it.replace(
                "      - After a tool call, stop and wait",
                "      - NEW RULE for {{TERMINAL_TOOL}}.\n      - After a tool call, stop and wait",
            )
        }

        val prompt = create(config, noPrompt)

        assertTrue(prompt.contains("IMPORTANT:\n- NEW RULE for respond.\n- After a tool call"))
    }

    @Test
    fun givenANewPriority_whenCreating_thenItIsSentAsItsOwnGroupAfterTheOthers() {
        val config = shippedWith("rules.yml") { it + "  - heading: OPTIONAL\n    items:\n      - Be brief.\n" }

        val prompt = create(config, noPrompt)

        assertTrue(prompt.contains("\"respond\".\n\nOPTIONAL:\n- Be brief.\n\nTools:"))
    }

    @Test
    fun givenANewIdentity_whenCreating_thenTheToneChangesWithNoCodeChange() {
        val config = shippedWith("agent.yml") {
            it.replace(Regex("(?s)identity: >-\n.*?\n\n"), "identity: Eres un asistente de programación.\n\n")
        }

        val prompt = create(config, noPrompt)

        assertTrue(prompt.startsWith("Eres un asistente de programación.\n\nCRITICAL:"))
    }

    @Test
    fun givenATranslatedHeading_whenCreating_thenTheTranslationIsSent() {
        // Language support is an edit to the file: every word the model reads comes from it.
        val config = shippedWith("tools.yml") { it.replace("  heading: Tools", "  heading: Herramientas") }

        val prompt = create(config, noPrompt)

        assertTrue(prompt.contains("Herramientas:\n- read_file: Read a file."))
    }

    @Test
    fun givenAReorderedLayout_whenCreating_thenTheSectionsFollowIt() {
        val config = shippedWith("layout.yml") {
            it.replace("    {{IDENTITY}}\n", "    {{TOOLS_HEADING}}!\n    {{IDENTITY}}\n")
        }

        val prompt = create(config, noPrompt)

        assertTrue(prompt.startsWith("Tools!\nYou are a coding assistant"))
    }

    @Test
    fun givenANewRule_whenTheBackendHasItsOwnPrompt_thenItIsNotSent() {
        // The general prompt is the fallback only; the backend's wording is not mixed with it.
        val config = shippedWith("rules.yml") {
            it.replace("      - After a tool call", "      - NEW RULE.\n      - After a tool call")
        }

        val prompt = create(config, ownPrompt)

        assertTrue(prompt.startsWith("I am Gemini.\n\n"))
        assertFalse(prompt.contains("NEW RULE."))
    }

    @Test
    fun givenAnIdeContext_whenTheBackendHasItsOwnPrompt_thenTheContextBlockIsAppended() {
        val context = IdeContext("app/Main.kt", emptyList(), emptyList())

        val prompt = create(shippedConfig, ownPrompt, context)

        assertTrue(prompt.startsWith("I am Gemini.\n\nCurrent date and time"))
        assertTrue(prompt.contains("\n\nIDE CONTEXT"))
    }

    @Test
    fun givenAnIdeContext_whenTheGeneralPromptIsUsed_thenItEndsWithTheContextBlock() {
        val context = IdeContext("app/Main.kt", emptyList(), emptyList())

        val prompt = create(shippedConfig, noPrompt, context)

        assertTrue(prompt.contains("</tool_call>\n\nCurrent date and time"))
        assertTrue(prompt.contains("\n\nIDE CONTEXT"))
        assertTrue(prompt.endsWith("do not guess a different folder or extension."))
    }

    @Test
    fun givenARuleWithATypo_whenChecked_thenItIsReportedByItsPathAndName() {
        val config = shippedWith("rules.yml") {
            it.replace("use \"{{TERMINAL_TOOL}}\".", "use \"{{TERMINAL_TOLL}}\".")
        }

        val problems = SystemPromptRenderer.problems(config)

        assertEquals(listOf("rules.yml: rules[2].items[2]: unknown name {{TERMINAL_TOLL}}"), problems)
    }

    @Test
    fun givenATypoInASectionThatIsUsuallyClosed_whenChecked_thenItIsStillReported() {
        // The check opens every section, so a branch a run rarely takes is still covered.
        val config = shippedWith("ide_context.yml") { it.replace("{{LAYOUT_DIR}}", "{{LAYOUTDIR}}") }

        val problems = SystemPromptRenderer.problems(config)

        assertEquals(listOf("ide_context.yml: ide_context.module_layout_dir: unknown name {{LAYOUTDIR}}"), problems)
    }

    @Test
    fun givenATypoBehindAnInvertedSection_whenChecked_thenItIsStillReported() {
        val config = shippedWith("layout.yml") {
            it.replace("    {{^FIRST}}\n\n", "    {{^FIRST}}\n    {{SEPARATR}}\n")
        }

        val problems = SystemPromptRenderer.problems(config)

        assertEquals(listOf("layout.yml: layout.system_prompt: unknown name {{SEPARATR}}"), problems)
    }

    private fun create(
        config: AgentPromptConfig,
        backend: BackendPrompts,
        context: IdeContext = IdeContext.EMPTY,
    ): String = runBlocking {
        SystemPromptFactory({ config }, { context }, backend, { SESSION }, "respond", SYNTAX).create(tools)
    }

    private companion object {
        val SESSION = SessionContext("Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)")
        const val SYNTAX = """<tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>"""
    }
}
