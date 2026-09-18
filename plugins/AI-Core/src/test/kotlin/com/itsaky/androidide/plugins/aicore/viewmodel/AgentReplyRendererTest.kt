package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolCallExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentReplyRenderer]. The bug these pin down was reported on Gemini: an `edit_file`
 * succeeded, then the last message read "(No response…)" because the terminal `respond` call was read
 * for a `message` key alone. A finished answer must not be thrown away over its key.
 */
class AgentReplyRendererTest {

    private companion object {
        const val TERMINAL = "respond"
        const val FAILED = "(action failed)"
        const val NO_RESPONSE = "(no response)"
        const val TRUNCATED = "(truncated)"
        const val MALFORMED = "(malformed)"
    }

    private fun render(
        rawText: String,
        toolCalls: List<ToolCall> = emptyList(),
        lastToolFailed: Boolean = false,
    ) = AgentReplyRenderer.render(
        rawText = rawText,
        toolCalls = toolCalls,
        terminalTool = TERMINAL,
        lastToolFailed = lastToolFailed,
        actionFailedText = FAILED,
        noResponseText = NO_RESPONSE,
        unparsedReplyText = {
            when (it) {
                ToolCallExtractor.UnparsedReply.TRUNCATED -> TRUNCATED
                ToolCallExtractor.UnparsedReply.MALFORMED -> MALFORMED
            }
        },
    )

    private fun respond(vararg args: Pair<String, Any?>) =
        listOf(ToolCall(TERMINAL, mapOf(*args)))

    @Test
    fun givenARespondCallWithAMessage_whenRendered_thenTheMessageIsShown() {
        val text = render("<tool_call>…</tool_call>", respond("message" to "All done."))

        assertEquals("All done.", text)
    }

    @Test
    fun givenARespondCallUnderAnAlternateKey_whenRendered_thenTheAnswerIsStillShown() {
        // Neither system prompt documents these, but models substitute them anyway.
        listOf("text", "response", "answer", "content").forEach { key ->
            assertEquals(
                "'$key' must be read as the answer",
                "All done.",
                render("<tool_call>…</tool_call>", respond(key to "All done.")),
            )
        }
    }

    @Test
    fun givenBothMessageAndAnAlternateKey_whenRendered_thenMessageWins() {
        val text = render("x", respond("text" to "second", "message" to "first"))

        assertEquals("first", text)
    }

    @Test
    fun givenAnEmptyRespondCallBesideProse_whenRendered_thenTheProseIsShown() {
        // The exact Gemini shape: the summary is written as prose and the envelope is empty.
        val raw = "I renamed count to itemCount in MainActivity.java.\n" +
            "<tool_call>{\"tool\":\"respond\",\"args\":{}}</tool_call>"

        val text = render(raw, respond())

        assertEquals("I renamed count to itemCount in MainActivity.java.", text)
    }

    @Test
    fun givenABlankRespondMessageBesideProse_whenRendered_thenTheProseIsShown() {
        val raw = "Done — the file now compiles.\n<tool_call>{\"tool\":\"respond\"}</tool_call>"

        val text = render(raw, respond("message" to "   "))

        assertEquals("Done — the file now compiles.", text)
    }

    @Test
    fun givenAnEmptyRespondCallAndNoProse_whenRendered_thenTheFallbackIsShown() {
        val text = render("<tool_call>{\"tool\":\"respond\",\"args\":{}}</tool_call>", respond())

        assertEquals(NO_RESPONSE, text)
    }

    @Test
    fun givenAnEmptyRespondCallAndOnlyBareJsonBeside_whenRendered_thenRawJsonIsNotShown() {
        // Showing the user an untagged tool call is worse than showing nothing.
        val raw = "{\"tool\":\"read_file\",\"args\":{\"file_path\":\"A.kt\"}}\n" +
            "<tool_call>{\"tool\":\"respond\",\"args\":{}}</tool_call>"

        val text = render(raw, respond())

        assertEquals(NO_RESPONSE, text)
    }

    @Test
    fun givenARespondCallAfterAFailedTool_whenRendered_thenTheFailureWinsOverTheClaim() {
        // The model asserting success after a tool failed must not be relayed as success.
        val text = render("x", respond("message" to "Done!"), lastToolFailed = true)

        assertEquals(FAILED, text)
    }

    @Test
    fun givenPlainProseAndNoToolCalls_whenRendered_thenTheProseIsShown() {
        assertEquals("Hi! What would you like to build?", render("Hi! What would you like to build?"))
    }

    @Test
    fun givenNothingAtAll_whenRendered_thenTheFallbackIsShown() {
        assertEquals(NO_RESPONSE, render("   "))
    }

    @Test
    fun givenACallBesideACapitalisedRespond_whenChecked_thenTheTurnIsNotSilent() {
        // The turn carries the answer, and this bubble is the only place that text is ever
        // rendered: dropping it ends the run "completed" with nothing on screen. `Respond` counts
        // as terminal by the loose match.
        val calls = listOf(
            ToolCall("read_file", mapOf("file_path" to "A.kt")),
            ToolCall("Respond", mapOf("message" to "Here is the answer")),
        )

        assertFalse(AgentReplyRenderer.isSilentTurn(calls, TERMINAL))
    }

    @Test
    fun givenOnlyRealCalls_whenChecked_thenTheTurnIsSilent() {
        // Work in progress: the activity line reports it, the transcript does not.
        val calls = listOf(
            ToolCall("read_file", mapOf("file_path" to "A.kt")),
            ToolCall("edit_file", mapOf("file_path" to "A.kt")),
        )

        assertTrue(AgentReplyRenderer.isSilentTurn(calls, TERMINAL))
    }

    @Test
    fun givenOnlyATerminalCall_whenChecked_thenTheAnswerIsNeverSilenced() {
        assertFalse(AgentReplyRenderer.isSilentTurn(respond("message" to "Done."), TERMINAL))
    }

    @Test
    fun givenNoToolCallsAtAll_whenChecked_thenTheProseTurnIsNotSilent() {
        // A plain answer parses to no calls; silencing it would drop the reply entirely.
        assertFalse(AgentReplyRenderer.isSilentTurn(emptyList(), TERMINAL))
    }

    @Test
    fun givenAReplyWhoseEnvelopeDidNotParse_whenRendered_thenTheAdviceReplacesTheRawJson() {
        val raw = """<tool_call>{"tool":"edit_file","args":{"new_string":"<View a="b"/>"}}</tool_call>"""

        val text = render(raw)

        // The bug this pins down: the unreadable call was pasted into the transcript verbatim.
        assertEquals(MALFORMED, text)
    }

    @Test
    fun givenAReplyCutOffMidEnvelope_whenRendered_thenTheTruncationAdviceIsShown() {
        val text = render("""<tool_call>{"tool":"edit_file","args":{"file_path":"A.kt",""")

        assertEquals(TRUNCATED, text)
    }

    @Test
    fun givenAnOrdinaryProseReply_whenRendered_thenItIsShownUnchanged() {
        assertEquals("Hi! What shall we build?", render("Hi! What shall we build?"))
    }
}
