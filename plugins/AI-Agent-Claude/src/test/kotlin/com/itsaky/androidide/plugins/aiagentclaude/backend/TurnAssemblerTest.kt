package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeFailure
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a stream becomes a turn: which text reaches the transcript, which tool calls run, and what an
 * empty turn is reported as. Every input is a `data:` line in the shape the Messages API streams.
 */
class TurnAssemblerTest {

    private val shown = StringBuilder()

    private fun feed(vararg lines: String): TurnAssembler {
        val turn = TurnAssembler { shown.append(it) }
        for (line in lines) {
            if (!turn.accept(ClaudeStreamEvent.parse("data: $line"))) break
        }
        return turn
    }

    private fun textStart(index: Int) =
        """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}"""

    private fun text(index: Int, text: String) =
        """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":"$text"}}"""

    private fun toolStart(index: Int, id: String, name: String) =
        """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}"""

    private fun toolInput(index: Int, json: String) =
        """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${org.json.JSONObject.quote(json)}}}"""

    private fun stop(reason: String) =
        """{"type":"message_delta","delta":{"stop_reason":"$reason"},"usage":{"output_tokens":1}}"""

    private val messageStop = """{"type":"message_stop"}"""

    @Test
    fun givenTextAndACall_whenTheTurnEnds_thenBothAreReportedAndTheTextWasShownLive() {
        val turn = feed(
            textStart(0), text(0, "Reading it."),
            toolStart(1, "toolu_1", "read_file"), toolInput(1, """{"file_path":"a.kt"}"""),
            stop("tool_use"), messageStop,
        )

        val result = turn.finish() as TurnAssembler.Result.Reply
        assertEquals("Reading it.", result.text)
        assertEquals("Reading it.", shown.toString())
        assertEquals(listOf("read_file"), result.calls.map { it.name })
        assertEquals(1, turn.chunks)
    }

    @Test
    fun givenARefusal_whenTheTurnEnds_thenItIsRefusedAndNoCallRuns() {
        // A declined turn's partial output is not an answer, and a call inside it is not one the
        // model stands behind.
        val turn = feed(
            toolStart(0, "toolu_1", "edit_file"), toolInput(0, """{"file_path":"a.kt"}"""),
            stop("refusal"), messageStop,
        )

        assertEquals(TurnAssembler.Result.Refused, turn.finish())
    }

    @Test
    fun givenAFallbackMidTurn_whenTheTurnEnds_thenTheDeclinedModelsCallIsDropped() {
        // The fallback model never saw the call, so running it runs a call nobody stands behind,
        // and the new model may make the same call again: the tool would run twice.
        val turn = feed(
            toolStart(0, "toolu_old", "edit_file"), toolInput(0, """{"file_path":"a.kt"}"""),
            """{"type":"content_block_start","index":1,"content_block":{"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-4-8"}}}""",
            toolStart(2, "toolu_new", "edit_file"), toolInput(2, """{"file_path":"a.kt"}"""),
            stop("tool_use"), messageStop,
        )

        val result = turn.finish() as TurnAssembler.Result.Reply
        assertEquals(listOf("toolu_new"), result.calls.map { it.callId })
        assertEquals(1, turn.discardedAtFallback)
        assertEquals("claude-opus-4-8", turn.servedBy)
    }

    @Test
    fun givenACallCutOffByMaxTokensBeforeAnyInput_whenTheTurnEnds_thenItDoesNotRunWithNoArguments() {
        // Its empty input parses as {}, which is a legitimate call to a tool that takes none.
        val turn = feed(toolStart(0, "toolu_1", "edit_file"), stop("max_tokens"), messageStop)

        assertEquals(TurnAssembler.Result.Empty(ClaudeFailure.TruncatedBeforeReply), turn.finish())
        assertEquals(1, turn.droppedCalls)
    }

    @Test
    fun givenMaxTokensWhileWritingText_whenTheTurnEnds_thenAnEarlierCompleteCallStillRuns() {
        // Only the block open at the stop was cut off.
        val turn = feed(
            toolStart(0, "toolu_1", "list_files"), toolInput(0, "{}"),
            textStart(1), text(1, "Now I will"),
            stop("max_tokens"), messageStop,
        )

        val result = turn.finish() as TurnAssembler.Result.Reply
        assertEquals(listOf("list_files"), result.calls.map { it.name })
        assertEquals(0, turn.droppedCalls)
    }

    @Test
    fun givenOnlyThinking_whenTheTurnEnds_thenItIsReportedAsReasoningOnly() {
        val turn = feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            stop("end_turn"), messageStop,
        )

        assertEquals(TurnAssembler.Result.Empty(ClaudeFailure.ReasoningOnly), turn.finish())
    }

    @Test
    fun givenNothingUsable_whenTheTurnEnds_thenItIsAnEmptyReplyCountingTheBadLines() {
        val turn = feed("{not json", stop("end_turn"), messageStop)

        assertEquals(TurnAssembler.Result.Empty(ClaudeFailure.EmptyReply(1)), turn.finish())
    }

    @Test
    fun givenAnErrorEvent_whenAccepted_thenItIsRaisedAsTheHttpFailureItStandsFor() {
        val error = assertThrows(ClaudeHttpException::class.java) {
            feed("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }
        assertEquals(529, error.statusCode)
    }

    @Test
    fun givenMessageStop_whenAccepted_thenTheReaderIsToldToStop() {
        val turn = TurnAssembler {}
        assertTrue(turn.accept(ClaudeStreamEvent.parse("data: ${text(0, "a")}")))
        assertFalse(turn.accept(ClaudeStreamEvent.parse("data: $messageStop")))
    }
}
