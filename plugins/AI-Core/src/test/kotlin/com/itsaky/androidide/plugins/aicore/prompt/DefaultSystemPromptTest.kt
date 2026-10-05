package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.services.LlmInferenceService.SystemPromptRequest
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the prompt the shipped prompt config renders, the one a backend that ships none of
 * its own gets, rendered exactly as [SystemPromptFactory] renders it.
 */
class DefaultSystemPromptTest {

    private val tools = listOf(
        ToolDefinition("read_file", "Read a file.", emptyMap()),
        ToolDefinition("respond", "Answer the user.", emptyMap()),
    )

    private fun request(syntax: String?) = SystemPromptRequest(tools, syntax, "app/Main.kt")

    @Test
    fun givenATextProtocol_whenBuilding_thenTheEnvelopeAndTheExamplePathAreTaught() {
        val prompt = build(request(SYNTAX), "respond")

        assertTrue(prompt.contains(SYNTAX))
        assertTrue(prompt.contains("app/Main.kt"))
    }

    @Test
    fun givenNativeToolCalling_whenBuilding_thenNoEnvelopeIsTaught() {
        // Teaching an envelope as well invites both, and the text one runs the tool a second time.
        val prompt = build(request(null), "respond")

        assertFalse(prompt.contains("TOOL CALL FORMAT"))
        assertFalse(prompt.contains("<tool_call>"))
    }

    @Test
    fun givenABlankEnvelope_whenBuilding_thenNoFormatSectionIsTaught() {
        // "emit EXACTLY this format:" with nothing after it teaches the model to emit nothing.
        val prompt = build(request("   "), "respond")

        assertFalse(prompt.contains("TOOL CALL FORMAT"))
    }

    @Test
    fun givenAnyProtocol_whenBuilding_thenEveryToolIsDescribed() {
        val prompt = build(request(SYNTAX), "respond")

        assertTrue(prompt.contains("- read_file: Read a file."))
        assertTrue(prompt.contains("- respond: Answer the user."))
    }

    @Test
    fun givenARenamedTerminalTool_whenBuilding_thenTheNewNameIsTheOneTaught() {
        // The name is the ViewModel's to choose; a prompt that hardcodes "respond" would teach a
        // tool the run does not offer.
        val prompt = build(request(SYNTAX), "answer")

        assertTrue(prompt.contains("\"answer\""))
    }

    @Test
    fun givenAnyProtocol_whenBuilding_thenTheIdentityIsFollowedByTheRulesThenTheCapabilities() {
        val prompt = build(request(SYNTAX), "respond")

        assertTrue(prompt.startsWith("You are a coding assistant inside CodeOnTheGo."))
        assertTrue(prompt.indexOf("CodeOnTheGo.") < prompt.indexOf("Reply with exactly ONE"))
        assertTrue(prompt.indexOf("Reply with exactly ONE") < prompt.indexOf("Tools:\n- read_file"))
        assertTrue(prompt.indexOf("- respond: Answer the user.") < prompt.indexOf("TOOL CALL FORMAT"))
    }

    @Test
    fun givenNativeToolCalling_whenBuilding_thenTheAbsentFormatLeavesNoTrailingBlankLines() {
        val prompt = build(request(null), "respond")

        // The session lines follow the tool list after exactly one blank line.
        assertTrue(prompt.contains("- respond: Answer the user.\n\nCurrent date and time"))
        assertFalse(prompt.contains("\n\n\n"))
    }

    @Test
    fun givenTheShippedRules_whenRead_thenThePrioritiesAreKnownAndInOrder() {
        // A heading the model has not been taught has no weight, and a lower one first misleads it.
        val headings = shippedConfig.rules.map { it.heading.template }

        assertTrue(headings.all { it in PRIORITIES })
        assertEquals(headings.sortedBy { PRIORITIES.indexOf(it) }, headings)
    }

    @Test
    fun givenSeveralPriorities_whenBuilding_thenOneBlankLineSeparatesThemAndNoneTrails() {
        val prompt = build(request(SYNTAX), "respond")

        assertTrue(prompt.contains("nothing else.\n- Never invent"))
        assertTrue(prompt.contains("through a tool.\n\nIMPORTANT:\n"))
        assertTrue(prompt.contains("use \"respond\".\n\nTools:\n"))
    }

    @Test
    fun givenAnyProtocol_whenBuilding_thenTheCriticalRulesComeFirst() {
        val prompt = build(request(SYNTAX), "respond")

        assertTrue(prompt.indexOf("CRITICAL:") < prompt.indexOf("- Reply with exactly ONE tool call"))
        assertTrue(prompt.indexOf("- Reply with exactly ONE tool call") < prompt.indexOf("IMPORTANT:"))
    }

    @Test
    fun givenAnOffDomainQuestion_whenBuilding_thenTheNonRefusalRuleIsStated() {
        // The regression ADFA-6223 fixed: without this the model refused anything non-Android.
        val prompt = build(request(SYNTAX), "respond")

        assertTrue(prompt.contains("Never refuse a question because it is not about Android"))
    }

    @Test
    fun givenAnyProtocol_whenBuilding_thenNoSentenceIsBrokenAcrossLines() {
        // Source line wraps once reached the model as literal newlines mid-sentence.
        val prompt = build(request(SYNTAX), "respond")

        assertFalse(prompt.contains("not\nonly Android"))
        assertTrue(prompt.contains("You answer anything the user asks, not only Android questions."))
    }

    @Test
    fun givenSeveralTools_whenBuilding_thenNoLineIsIndented() {
        // An interpolated multi-line tool list defeated trimIndent and left every line indented.
        val prompt = build(request(SYNTAX), "respond")

        assertFalse(prompt.lines().any { it.startsWith(" ") })
    }

    private fun build(request: SystemPromptRequest, terminalTool: String): String {
        return SystemPromptRenderer.render(shippedConfig, request, terminalTool, IdeContext.EMPTY, SESSION)
    }

    private companion object {
        val SESSION = SessionContext("Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)")

        /** The rule priorities, highest first; `rules.yml` may use only these headings. */
        val PRIORITIES = listOf("CRITICAL", "IMPORTANT", "MANDATORY", "OPTIONAL")

        const val SYNTAX = """<tool_call>{"tool":"TOOL_NAME","args":{"arg":"value"}}</tool_call>"""
    }
}
