package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentLoop] — the agentic tool-loop, tested in isolation from
 * Android/streaming via plain fakes for generation and tool execution.
 */
class AgentLoopTest {

    /** Records the turns it was given and returns scripted model responses in order. */
    private class ScriptedModel(private val responses: List<String>) {
        val turns = mutableListOf<List<ChatMessage>>()
        var calls = 0
        suspend fun generate(history: List<ChatMessage>): AgentLoop.ModelReply {
            turns += history
            val r = responses.getOrElse(calls) { responses.last() }
            calls++
            return AgentLoop.ModelReply(r)
        }
    }

    private fun toolCall(name: String) = """<tool_call>{"tool":"$name","args":{}}</tool_call>"""

    /** A call whose argument sets its signature apart from the same tool called on another path. */
    private fun toolCall(name: String, path: String) =
        """<tool_call>{"tool":"$name","args":{"path":"$path"}}</tool_call>"""

    @Test
    fun givenAModelThatCallsNoTools_whenTheLoopRuns_thenItStopsAfterOneTurn() = runTest {
        val model = ScriptedModel(listOf("All done — here is your answer."))
        val history = mutableListOf(ChatMessage(Role.USER, "hello"))
        var toolsInvoked = 0

        val result = AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { toolsInvoked++; emptyList() }
        )

        assertTrue(result.completed)
        assertEquals(1, result.turns)
        assertEquals(1, model.calls)
        assertEquals(0, toolsInvoked)
        // history now: user + one assistant turn
        assertEquals(2, history.size)
        assertEquals(Role.ASSISTANT, history[1].role)
    }

    @Test
    fun givenAModelThatCallsTheTerminalTool_whenTheLoopRuns_thenItFinishesViaOnFinalAnswerWithoutDispatchingIt() = runTest {
        // Terminal tool ("respond") finishes via onFinalAnswer and is never dispatched.
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"respond","args":{"message":"All set!"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "hi"))
        var toolsInvoked = 0
        var finalTurn = -1
        var finalMessage: String? = null

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { toolsInvoked++; emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onFinalAnswer(turn: Int, message: String) {
                    finalTurn = turn
                    finalMessage = message
                }
            }
        )

        assertTrue(result.completed)
        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertEquals(1, result.turns)
        assertEquals(0, toolsInvoked)          // terminal tool is NOT dispatched
        assertEquals(1, finalTurn)
        assertEquals("All set!", finalMessage)
    }

    @Test
    fun givenATerminalCallUnderAnAlternateKey_whenTheLoopRuns_thenTheAnswerStillReachesOnFinalAnswer() = runTest {
        // Models substitute "text" for "message", which dropped the finished answer entirely.
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"respond","args":{"text":"All set!"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "hi"))
        var finalMessage: String? = null

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onFinalAnswer(turn: Int, message: String) { finalMessage = message }
            }
        )

        assertTrue(result.completed)
        assertEquals("All set!", finalMessage)
    }

    @Test
    fun givenTheTerminalToolNamedInAnotherCase_whenTheLoopRuns_thenItStillEndsTheRun() = runTest {
        // A cloud backend ignores the GBNF, so `Respond` and `  respond ` both arrive. Matched by
        // equality they are ordinary tool calls, and an ordinary call gets routed — which is how a
        // contributed tool ends up being handed the user's final answer instead of the user.
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"Respond","args":{"message":"All set!"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "hi"))
        var toolsInvoked = 0
        var finalMessage: String? = null

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { toolsInvoked++; emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onFinalAnswer(turn: Int, message: String) { finalMessage = message }
            }
        )

        assertTrue(result.completed)
        assertEquals(0, toolsInvoked)
        assertEquals("All set!", finalMessage)
    }

    @Test
    fun givenAReplyWhoseHistoryTextDiffers_whenTheLoopRuns_thenTheTranscriptKeepsTheHistoryText() = runTest {
        // A natively-calling backend hands over the envelopes for extraction only: shown its own
        // calls as text, the model starts writing the next one in a format that does not run.
        val call = toolCall("read_file")
        val replies = listOf(
            AgentLoop.ModelReply(text = "Reading it. $call", historyText = "Reading it."),
            AgentLoop.ModelReply(text = "All done."),
        )
        var turn = 0
        val history = mutableListOf(ChatMessage(Role.USER, "read MainActivity.kt"))
        val executed = mutableListOf<List<ToolCall>>()

        val result = AgentLoop().run(
            history = history,
            generate = { replies[turn++] },
            executeTools = { calls ->
                executed += calls
                listOf(ToolResult.success("contents", "MainActivity.kt"))
            }
        )

        assertTrue(result.completed)
        // The call still ran, so extraction read the envelope...
        assertEquals(1, executed.size)
        assertEquals("read_file", executed[0][0].name)
        // ...but the stored turn carries neither it nor its envelope.
        assertEquals("Reading it.", history[1].content)
        assertFalse(history[1].content.contains("<tool_call>"))
    }

    @Test
    fun givenACallWithNoProseBesideIt_whenTheLoopRuns_thenNoEmptyTurnIsStored() = runTest {
        // Gemini rejects a content part with empty text, so the turn is left out rather than
        // stored blank; the tool results that follow name the call.
        val replies = listOf(
            AgentLoop.ModelReply(text = toolCall("read_file"), historyText = ""),
            AgentLoop.ModelReply(text = "All done."),
        )
        var turn = 0
        val history = mutableListOf(ChatMessage(Role.USER, "read MainActivity.kt"))

        AgentLoop().run(
            history = history,
            generate = { replies[turn++] },
            executeTools = { listOf(ToolResult.success("contents", "MainActivity.kt")) }
        )

        // user, user(tool results), assistant("All done.") — no blank turn between the first two.
        assertEquals(3, history.size)
        assertEquals(Role.USER, history[1].role)
        assertTrue(history[1].content.startsWith("<tool_response>"))
    }

    @Test
    fun givenAModelThatCallsAToolThenAnswers_whenTheLoopRuns_thenItChainsTheToolAndFinishes() = runTest {
        // Turn 1: model calls a tool. Turn 2: sees results, gives final answer.
        val model = ScriptedModel(
            listOf(
                "Let me look. ${toolCall("open_file")}",
                "Opened it. Done."
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "open MainActivity.java"))
        val executed = mutableListOf<List<ToolCall>>()

        val result = AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { calls ->
                executed += calls
                listOf(ToolResult.success("Opened file in editor", "path/to/MainActivity.java"))
            }
        )

        assertTrue(result.completed)
        assertEquals(2, result.turns)
        assertEquals(2, model.calls)
        assertEquals(1, executed.size)
        assertEquals("open_file", executed[0][0].name)

        // Turn 2 must get the fed-back results as their own USER turn, not folded into turn 1.
        val secondTurnInput = model.turns[1]
        assertEquals(3, secondTurnInput.size)
        assertEquals(Role.ASSISTANT, secondTurnInput[1].role)
        assertEquals(Role.USER, secondTurnInput[2].role)
        assertTrue(
            "2nd turn should include tool results",
            secondTurnInput[2].content.contains("<tool_response>") &&
                secondTurnInput[2].content.contains("MainActivity.java")
        )

        // history: user, assistant(turn1), user(tool results), assistant(turn2)
        assertEquals(4, history.size)
        assertEquals(Role.USER, history[2].role)
        assertTrue(history[2].content.startsWith("<tool_response>"))
    }

    @Test
    fun givenAModelThatKeepsCallingDistinctTools_whenTheLoopRuns_thenItStopsAtTheIterationCap() = runTest {
        // Distinct calls each turn so stuck-detection doesn't fire before the cap.
        val model = ScriptedModel(
            listOf(
                "step1 ${toolCall("list_files")}",
                "step2 ${toolCall("read_file")}",
                "step3 ${toolCall("search_project")}"
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "keep going"))
        var maxReachedTurns = -1
        var toolBatches = 0

        val result = AgentLoop(maxIterations = 3).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.success("ok")) },
            events = object : AgentLoop.Events {
                override suspend fun onMaxIterationsReached(turns: Int) { maxReachedTurns = turns }
            }
        )

        assertFalse(result.completed)
        assertEquals(AgentLoop.StopReason.MAX_ITERATIONS, result.reason)
        assertEquals(3, result.turns)
        assertEquals(3, model.calls)
        assertEquals(3, toolBatches)
        assertEquals(3, maxReachedTurns)
    }

    @Test
    fun givenAModelThatRepeatsAToolCallThatSucceeded_whenTheLoopRuns_thenItEndsWithoutRunningItAgain() = runTest {
        val model = ScriptedModel(listOf(toolCall("list_files")))  // same call every turn
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var repeatedTurns = -1
        var toolBatches = 0

        val result = AgentLoop(maxIterations = 8).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.success("ok")) },
            events = object : AgentLoop.Events {
                override suspend fun onRepeatedToolCalls(turns: Int) { repeatedTurns = turns }
            }
        )

        // The work already succeeded, so a re-request means "done" — not an error.
        assertTrue(result.completed)
        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertEquals(2, result.turns)
        assertEquals(1, toolBatches)     // the side effect runs exactly once
        assertEquals(-1, repeatedTurns)  // no "kept requesting the same action" warning
    }

    @Test
    fun givenAModelThatRepeatsAToolCallThatFailed_whenTheLoopRuns_thenItStopsAfterToleratingBoundedRepeats() = runTest {
        val model = ScriptedModel(listOf(toolCall("list_files")))  // same call every turn
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var repeatedTurns = -1
        var toolBatches = 0

        // A failed batch keeps the retry tolerance: one repeat allowed, the second aborts.
        val result = AgentLoop(maxIterations = 8).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.failure("nope")) },
            events = object : AgentLoop.Events {
                override suspend fun onRepeatedToolCalls(turns: Int) { repeatedTurns = turns }
            }
        )

        assertFalse(result.completed)
        assertEquals(AgentLoop.StopReason.REPEATED, result.reason)
        assertEquals(3, result.turns)   // turn1 + one tolerated repeat, abort on turn3
        assertEquals(3, repeatedTurns)
        assertEquals(2, toolBatches)    // executed twice, then stopped
    }

    @Test
    fun givenMaxConsecutiveRepeatsOfOne_whenAFailedCallIsRepeated_thenItStopsOnTheSecondTurn() = runTest {
        val model = ScriptedModel(listOf(toolCall("list_files")))
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var toolBatches = 0

        val result = AgentLoop(maxIterations = 8, maxConsecutiveRepeats = 1).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.failure("nope")) }
        )

        assertEquals(AgentLoop.StopReason.REPEATED, result.reason)
        assertEquals(2, result.turns)
        assertEquals(1, toolBatches)
    }

    @Test
    fun givenATurnCoEmittingRespondAndARealTool_whenTheLoopRuns_thenTheRealToolStillRuns() = runTest {
        // `respond` co-emitted with a real tool must not drop the real tool.
        val model = ScriptedModel(
            listOf(
                """<tool_call>{"tool":"open_file","args":{"file_path":"MainActivity.java"}}</tool_call>""" +
                    """<tool_call>{"tool":"respond","args":{"message":"Opening it."}}</tool_call>""",
                "Done."
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "open MainActivity"))
        val executed = mutableListOf<List<ToolCall>>()

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { calls ->
                executed += calls
                listOf(ToolResult.success("Opened file in editor", "path/MainActivity.java"))
            }
        )

        assertTrue(result.completed)
        assertEquals(1, executed.size)
        assertEquals(listOf("open_file"), executed[0].map { it.name })  // respond dropped, open_file kept
    }

    @Test
    fun givenATurnWithOnlyTheTerminalTool_whenTheLoopRuns_thenItFinishesImmediately() = runTest {
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"respond","args":{"message":"Hi!"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "hello"))
        var toolsInvoked = 0
        var finalMessage: String? = null

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { toolsInvoked++; emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onFinalAnswer(turn: Int, message: String) { finalMessage = message }
            }
        )

        assertTrue(result.completed)
        assertEquals(1, result.turns)
        assertEquals(0, toolsInvoked)
        assertEquals("Hi!", finalMessage)
    }

    @Test
    fun givenAToolThenAnswerRun_whenTheLoopRuns_thenEventsFireForEachModelTurnAndToolBatch() = runTest {
        val model = ScriptedModel(listOf(toolCall("read_file"), "done"))
        val history = mutableListOf(ChatMessage(Role.USER, "read it"))
        val modelTurns = mutableListOf<Int>()
        val toolTurns = mutableListOf<Int>()

        AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("contents")) },
            events = object : AgentLoop.Events {
                override suspend fun onModelTurn(turn: Int, text: String) { modelTurns += turn }
                override suspend fun onToolResults(turn: Int, calls: List<ToolCall>, results: List<ToolResult>) { toolTurns += turn }
            }
        )

        assertEquals(listOf(1, 2), modelTurns)
        assertEquals(listOf(1), toolTurns)
    }

    @Test
    fun givenAFailingTool_whenTheLoopRuns_thenTheResultsAreFedBackAsFAILED() = runTest {
        val model = ScriptedModel(listOf(toolCall("open_file"), "acknowledged"))
        val history = mutableListOf(ChatMessage(Role.USER, "open nope"))

        AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.failure("File not found", "does not exist")) }
        )

        val fedBack = history.first { it.role == Role.USER && it.content.startsWith("<tool_response>") }
        assertTrue(fedBack.content.contains("FAILED"))
        assertTrue(fedBack.content.contains("File not found"))
    }

    @Test
    fun givenLongToolOutput_whenTheLoopRuns_thenItIsTruncatedBeforeFeedingBack() = runTest {
        val big = "x".repeat(10_000)
        val model = ScriptedModel(listOf(toolCall("read_file"), "ok"))
        val history = mutableListOf(ChatMessage(Role.USER, "read big"))

        AgentLoop(toolOutputCharLimit = 500).run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("read", big)) }
        )

        val fedBack = history.first { it.content.startsWith("<tool_response>") }
        assertTrue(fedBack.content.contains("truncated"))
        assertFalse("full 10k output must not be fed back", fedBack.content.contains(big))
    }

    @Test
    fun givenASuccessfulToolResult_whenFormatToolResultsIsCalled_thenItBiasesTheModelToStop() {
        val loop = AgentLoop()
        val fedBack = loop.formatToolResults(
            listOf(ToolCall("open_file", emptyMap())),
            listOf(ToolResult.success("Opened file in editor", ".gitignore"))
        )
        // After success, finishing is the default and another tool call is discouraged.
        assertTrue(fedBack.contains("you are DONE"))
        assertTrue(fedBack.contains("respond"))
        assertTrue(fedBack.contains("Do NOT call another tool"))
    }

    @Test
    fun givenAFailedToolResult_whenFormatToolResultsIsCalled_thenItKeepsTheOpenEndedNextToolCue() {
        val loop = AgentLoop()
        val fedBack = loop.formatToolResults(
            listOf(ToolCall("open_file", emptyMap())),
            listOf(ToolResult.failure("File not found", "does not exist"))
        )
        assertTrue(fedBack.contains("FAILED"))
        assertTrue(fedBack.contains("call the next tool"))
    }

    @Test
    fun givenATranscript_whenRenderTranscriptIsCalled_thenItLabelsAssistantTurnsAndAddsNoTrailingCue() {
        val loop = AgentLoop()
        val transcript = loop.renderTranscript(
            listOf(
                ChatMessage(Role.USER, "hi"),
                ChatMessage(Role.ASSISTANT, "hello"),
                ChatMessage(Role.USER, "Tool results:\n[list_files] ok")
            )
        )
        assertTrue(transcript.contains("hi"))
        assertTrue(transcript.contains("Assistant: hello"))
        assertTrue(transcript.contains("Tool results:"))
        // Must not append a trailing "Assistant:" cue (the backend adds its own).
        assertFalse("must not append a trailing Assistant cue", transcript.trimEnd().endsWith("Assistant:"))
    }

    @Test
    fun givenAReplyWhoseToolCallCouldNotBeParsed_whenTheLoopRuns_thenItStopsUnparsableRatherThanCompleted() = runTest {
        // ADFA-5410: this returned COMPLETED, so a run that did nothing looked like a finished one.
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"edit_file","args":{"new_string":"<View a="b"/>"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "add a view"))
        var reported: ToolCallExtractor.UnparsedReply? = null

        val result = AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onUnparsedReply(turn: Int, reason: ToolCallExtractor.UnparsedReply) {
                    reported = reason
                }
            }
        )

        assertFalse(result.completed)
        assertEquals(AgentLoop.StopReason.UNPARSABLE, result.reason)
        assertEquals(ToolCallExtractor.UnparsedReply.MALFORMED, reported)
    }

    @Test
    fun givenAnOrdinaryProseReply_whenTheLoopRuns_thenItStillCompletesWithoutReportingAFailure() = runTest {
        val model = ScriptedModel(listOf("Hi! What shall we build?"))
        var reported = false

        val result = AgentLoop().run(
            history = mutableListOf(ChatMessage(Role.USER, "hi")),
            generate = model::generate,
            executeTools = { emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onUnparsedReply(turn: Int, reason: ToolCallExtractor.UnparsedReply) {
                    reported = true
                }
            }
        )

        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertFalse(reported)
    }

    @Test
    fun givenTheModelGivesUpAfterAFailedTool_whenTheLoopRuns_thenTheRunIsNotReportedComplete() =
        runTest {
            val model = ScriptedModel(listOf(toolCall("open_file"), "I could not open that file."))
            var abandonedTurn = 0

            val result = AgentLoop().run(
                history = mutableListOf(ChatMessage(Role.USER, "open nope")),
                generate = model::generate,
                executeTools = { listOf(ToolResult.failure("File not found", "does not exist")) },
                events = object : AgentLoop.Events {
                    override suspend fun onAbandonedAfterFailure(turn: Int) {
                        abandonedTurn = turn
                    }
                }
            )

            assertFalse("the request was never met", result.completed)
            assertEquals(AgentLoop.StopReason.ABANDONED, result.reason)
            assertEquals(2, abandonedTurn)
        }

    @Test
    fun givenTheModelStopsAfterASucceedingTool_whenTheLoopRuns_thenTheRunIsComplete() = runTest {
        // The other side of the rule above: nothing is outstanding, so this really is done.
        val model = ScriptedModel(listOf(toolCall("open_file"), "Opened it."))
        var abandoned = false

        val result = AgentLoop().run(
            history = mutableListOf(ChatMessage(Role.USER, "open A.kt")),
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("Opened", "A.kt")) },
            events = object : AgentLoop.Events {
                override suspend fun onAbandonedAfterFailure(turn: Int) {
                    abandoned = true
                }
            }
        )

        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertTrue(result.completed)
        assertFalse(abandoned)
    }

    @Test
    fun givenAModelRotatingBetweenSeenActions_whenTheLoopRuns_thenItStopsAsCyclingBeforeTheStepCap() = runTest {
        // Three reads in a cycle: never two identical turns in a row, so the repeat guard — which
        // only compares a turn against the one before it — never sees anything wrong.
        val model = ScriptedModel(
            listOf(
                toolCall("open_file"),
                toolCall("read_build_output"),
                toolCall("search_project"),
                toolCall("open_file"),
                toolCall("read_build_output"),
                toolCall("search_project"),
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "fix the build"))
        var cycledTurns = -1
        var maxReachedTurns = -1
        var toolBatches = 0

        val result = AgentLoop(maxIterations = 16).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.success("ok")) },
            events = object : AgentLoop.Events {
                override suspend fun onNoProgressCycle(turns: Int, staleTurns: Int) { cycledTurns = turns }
                override suspend fun onMaxIterationsReached(turns: Int) { maxReachedTurns = turns }
            }
        )

        assertFalse(result.completed)
        assertEquals(AgentLoop.StopReason.CYCLING, result.reason)
        assertEquals(6, result.turns)            // three new signatures, then three stale ones
        assertEquals(6, cycledTurns)
        assertEquals(-1, maxReachedTurns)        // the step budget is never reached
        assertEquals(5, toolBatches)             // the aborting turn runs nothing
    }

    @Test
    fun givenANewSignatureAfterTwoStaleTurns_whenTheLoopRuns_thenTheCounterResets() = runTest {
        // A run that is still finding new things to do must not be aborted for revisiting one.
        val model = ScriptedModel(
            listOf(
                toolCall("read_file", "A.kt"),
                toolCall("read_file", "B.kt"),
                toolCall("read_file", "A.kt"),
                toolCall("read_file", "B.kt"),
                toolCall("read_file", "C.kt"),   // genuinely new: the counter goes back to zero
                toolCall("read_file", "A.kt"),
                toolCall("read_file", "B.kt"),
                toolCall("read_file", "A.kt"),
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "read the sources"))

        val result = AgentLoop(maxIterations = 16).run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("ok")) }
        )

        assertEquals(AgentLoop.StopReason.CYCLING, result.reason)
        // Turn 5 would have been the third stale turn had the new call not reset the counter.
        assertEquals(8, result.turns)
    }

    @Test
    fun givenAFailedCallRepeatedIdentically_whenTheLoopRuns_thenTheRepeatGuardStillFiresFirst() = runTest {
        // Both guards count the same turns; the tighter one must win, or REPEATED never reports.
        val model = ScriptedModel(listOf(toolCall("list_files")))
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var cycledTurns = -1

        val result = AgentLoop(maxIterations = 16).run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.failure("nope")) },
            events = object : AgentLoop.Events {
                override suspend fun onNoProgressCycle(turns: Int, staleTurns: Int) { cycledTurns = turns }
            }
        )

        assertEquals(AgentLoop.StopReason.REPEATED, result.reason)
        assertEquals(3, result.turns)
        assertEquals(-1, cycledTurns)
    }

    @Test
    fun givenALongRunThatKeepsIntroducingNewActions_whenTheLoopRuns_thenItNeverAbortsAsCycling() = runTest {
        // Signatures carry arguments, so reading twelve different files is twelve distinct actions.
        val model = ScriptedModel(
            (1..12).map { toolCall("read_file", "File$it.kt") } + "Here is the summary."
        )
        val history = mutableListOf(ChatMessage(Role.USER, "summarise the sources"))
        var cycledTurns = -1

        val result = AgentLoop(maxIterations = 16).run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("ok")) },
            events = object : AgentLoop.Events {
                override suspend fun onNoProgressCycle(turns: Int, staleTurns: Int) { cycledTurns = turns }
            }
        )

        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertEquals(13, result.turns)
        assertEquals("a productive run must never be reported as cycling", -1, cycledTurns)
    }

    @Test
    fun givenARotatingRunThatEndsByRepeatingASuccessfulBatch_whenBothGuardsWouldFire_thenCyclingWins() = runTest {
        // The last turn repeats a batch that succeeded, which alone reads as "the work is done".
        // After three turns of circling it is not done, and reporting COMPLETED would say nothing
        // to the user at all — so the cycling check is evaluated first.
        val model = ScriptedModel(
            listOf(
                toolCall("read_file", "A.kt"),
                toolCall("read_file", "B.kt"),
                toolCall("read_file", "A.kt"),
                toolCall("read_file", "B.kt"),
                toolCall("read_file", "B.kt"),
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var repeatAfterSuccessTurn = -1
        var cycledTurns = -1

        val result = AgentLoop(maxIterations = 16).run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("ok")) },
            events = object : AgentLoop.Events {
                override suspend fun onRepeatAfterSuccess(turn: Int) { repeatAfterSuccessTurn = turn }
                override suspend fun onNoProgressCycle(turns: Int, staleTurns: Int) { cycledTurns = turns }
            }
        )

        assertEquals(AgentLoop.StopReason.CYCLING, result.reason)
        assertEquals(5, result.turns)
        assertEquals(5, cycledTurns)
        assertEquals("a circling run must not be reported as completed", -1, repeatAfterSuccessTurn)
    }
}
