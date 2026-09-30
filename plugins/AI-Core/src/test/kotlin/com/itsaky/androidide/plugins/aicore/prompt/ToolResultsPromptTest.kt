package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolResultsPrompt], the turn after each tool batch: worded by `agent_loop.yml`,
 * so what the agent is told between steps changes with no code change.
 */
class ToolResultsPromptTest {

    private val openFile = listOf(ToolCall("open_file", emptyMap()))

    @Test
    fun givenTheShippedConfig_whenChecked_thenEveryBatchRenders() {
        assertEquals(emptyList<String>(), ToolResultsPrompt.problems(shippedConfig))
    }

    @Test
    fun givenASuccessfulBatch_whenRendering_thenTheResultIsEnvelopedAndTheModelStopsOnlyWhenDone() {
        val turn = render(openFile, listOf(ToolResult.success("Opened file in editor", ".gitignore")))

        assertEquals(
            "<tool_response>\n[open_file] Opened file in editor\n.gitignore\n</tool_response>\n\n" +
                "Treat the tool result(s) above as fact: report only what they actually say, and never " +
                "invent, assume, or contradict them. Where a result names a replacement, a removal or a " +
                "newer version, it overrides what you remember — in your prose and in every line of code " +
                "and every dependency you write — and an API a result calls deprecated or removed never " +
                "appears in your code. The parts of the request no tool covers — " +
                "explanation, design, code — you still write from your own knowledge. The action " +
                "succeeded. If that was the whole request, you are DONE — reply with the \"respond\" " +
                "tool briefly confirming what happened. If parts of the request are still undone, do " +
                "the next one now: call its tool, or write that part of the answer yourself. Never " +
                "reply only to say what you will do next, and do not call a tool for a step the user " +
                "did not ask for.",
            turn,
        )
    }

    @Test
    fun givenAFailedBatch_whenRendering_thenTheFailureIsMarkedAndTheNextToolCueIsOpenEnded() {
        val turn = render(openFile, listOf(ToolResult.failure("File not found", "does not exist")))

        assertTrue(turn.startsWith("<tool_response>\n[open_file] FAILED: File not found\ndoes not exist\n"))
        assertTrue(turn.endsWith("If the task is complete, give the user your final answer. Otherwise, call the next tool."))
        assertFalse(turn.contains("you are DONE"))
    }

    @Test
    fun givenAMixedBatch_whenRendering_thenTheSuccessCueIsNotGiven() {
        // One failure left unaddressed means the task is not done, however many others succeeded.
        val calls = listOf(ToolCall("read_file", emptyMap()), ToolCall("open_file", emptyMap()))

        val turn = render(calls, listOf(ToolResult.success("read"), ToolResult.failure("nope")))

        assertTrue(turn.contains("[read_file] read\n</tool_response>\n\n<tool_response>\n[open_file] FAILED: nope"))
        assertTrue(turn.endsWith("call the next tool."))
    }

    @Test
    fun givenALongResult_whenRendering_thenItIsCutAndTheCutIsStated() {
        val turn = ToolResultsPrompt.render(
            shippedConfig, "respond", 5, openFile, listOf(ToolResult.success("0123456789")),
        )

        assertTrue(turn.startsWith("<tool_response>\n[open_file] 01234\n…[truncated 5 chars]\n</tool_response>"))
    }

    @Test
    fun givenToolOutputThatLooksLikeATag_whenRendering_thenItReachesTheModelVerbatim() {
        // A file's contents are data: a `{{X}}` in them must not be read as a template tag.
        val turn = render(openFile, listOf(ToolResult.failure("{{KEPT}} {{#X}}")))

        assertTrue(turn.contains("FAILED: {{KEPT}} {{#X}}"))
    }

    @Test
    fun givenARenamedTerminalTool_whenRendering_thenTheSuccessCueNamesIt() {
        // The shipped text used to hardcode "respond", teaching a tool a renamed run lacks.
        val turn = ToolResultsPrompt.render(
            shippedConfig, "answer", 4000, openFile, listOf(ToolResult.success("ok")),
        )

        assertTrue(turn.contains("reply with the \"answer\" tool"))
    }

    @Test
    fun givenNewWordingInAgentLoopYml_whenRendering_thenItIsSentWithNoCodeChange() {
        val config = shippedWith("agent_loop.yml") {
            it.replace("failed: \"FAILED: {{MESSAGE}}\"", "failed: \"ERROR — {{MESSAGE}}\"")
                .replace(Regex("  after_failure: [^\n]*"), "  after_failure: Intenta con otra herramienta.")
        }

        val turn = render(openFile, listOf(ToolResult.failure("nope")), config)

        assertTrue(turn.contains("[open_file] ERROR — nope\n"))
        assertTrue(turn.endsWith("Intenta con otra herramienta."))
    }

    @Test
    fun givenATypoInAgentLoopYml_whenChecked_thenItIsReportedByItsFileAndPath() {
        val config = shippedWith("agent_loop.yml") { it.replace("{{COUNT}}", "{{CUONT}}") }

        val problems = ToolResultsPrompt.problems(config)

        assertEquals(listOf("agent_loop.yml: agent_loop.truncated: unknown name {{CUONT}}"), problems)
    }

    @Test
    fun givenAProvider_whenFormatting_thenTheCachedConfigIsWhatWordsTheTurn() {
        val prompt = ToolResultsPrompt({ shippedConfig }, "respond")

        val turn = runBlocking { prompt.format(openFile, listOf(ToolResult.success("ok"))) }

        assertEquals(render(openFile, listOf(ToolResult.success("ok"))), turn)
    }

    private fun render(
        calls: List<ToolCall>,
        results: List<ToolResult>,
        config: AgentPromptConfig = shippedConfig,
    ): String = ToolResultsPrompt.render(config, "respond", ToolResultsPrompt.DEFAULT_CHAR_LIMIT, calls, results)

    @Test
    fun givenTheShippedConfig_whenRenderingTheUnfinishedTurn_thenItNamesTheTerminalTool() {
        val turn = ToolResultsPrompt.renderUnfinished(shippedConfig, "answer")

        assertTrue(turn.contains("call the \"answer\" tool"))
        assertFalse(turn.contains("{{"))
    }

    @Test
    fun givenTheShippedConfig_whenRenderingTheRequiredToolTurn_thenItNamesTheToolAndNoTemplateIsLeft() {
        val turn = ToolResultsPrompt.renderRequiredTool(shippedConfig, "respond", "web_search")

        assertTrue(turn.contains("did not call \"web_search\""))
        assertTrue(turn.contains("Call \"web_search\" now"))
        assertFalse(turn.contains("{{"))
    }

    @Test
    fun givenASearchReportPastTheDefaultCap_whenRendering_thenItsSourcesAreNotCut() {
        val report = "x".repeat(ToolResultsPrompt.DEFAULT_CHAR_LIMIT) + "\n\nSources:\n- https://ktor.io/docs"

        val turn = render(listOf(ToolCall("web_search", emptyMap())), listOf(ToolResult.success("Searched", report)))

        assertTrue(turn.contains("- https://ktor.io/docs\n</tool_response>"))
    }

    @Test
    fun givenAProjectResultPastTheDefaultCap_whenRendering_thenItIsStillCut() {
        val turn = render(openFile, listOf(ToolResult.success("x".repeat(ToolResultsPrompt.DEFAULT_CHAR_LIMIT + 10))))

        assertTrue(turn.contains("[truncated"))
    }
}
