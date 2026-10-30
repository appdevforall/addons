package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role

/**
 * The agentic tool-loop: each turn renders the transcript into a prompt, generates a
 * reply, and runs any tool calls, looping until the model stops or a limit is hit.
 * Free of Android/coroutine/UI deps so it unit-tests with plain fakes; knows no wording.
 *
 * @param formatToolResults words each batch's results as the next user turn.
 * @param unfinishedTurn words the turn sent when a run that has used tools ends a reply without
 *   [terminalTool]; null ends the run on that reply instead.
 * @param requiredToolTurn words the turn sent when a run told to call a tool first tries to finish
 *   without it; receives the tool's name. Null never asks.
 * @param unappliedWriteTurn words the turn sent when a run answers while a file change it attempted
 *   failed and has not been redone. Sent once; if the run still ends with the change outstanding,
 *   however it ends, the user is told it was not made. Null turns the check off.
 */
class AgentLoop(
    private val formatToolResults: ToolResultsFormatter,
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val maxConsecutiveRepeats: Int = DEFAULT_MAX_CONSECUTIVE_REPEATS,
    private val maxTurnsWithoutProgress: Int = DEFAULT_MAX_TURNS_WITHOUT_PROGRESS,
    private val extractToolCalls: (String) -> List<ToolCall> = ToolCallExtractor::extractToolCalls,
    private val diagnoseUnparsedReply: (String) -> ToolCallExtractor.UnparsedReply? =
        ToolCallExtractor::diagnoseUnparsedReply,
    private val terminalTool: String? = null,
    private val unfinishedTurn: (suspend () -> String)? = null,
    private val requiredToolTurn: (suspend (String) -> String)? = null,
    private val unappliedWriteTurn: (suspend () -> String)? = null,
) {

    companion object {
        /**
         * Max model turns per user message, as a backstop against a model that never stops calling
         * tools. At 8 a two-line rename ran out mid-way and left the file half-edited; the
         * repeated-call guard and per-write approval are the tighter limits.
         */
        const val DEFAULT_MAX_ITERATIONS = 16

        /**
         * Consecutive identical tool-call batches tolerated before aborting as
         * [StopReason.REPEATED]; a truncated result can make one repeat legitimate.
         */
        const val DEFAULT_MAX_CONSECUTIVE_REPEATS = 2

        /**
         * Turns that may introduce no tool-call signature the run has not already used, before
         * aborting as [StopReason.CYCLING]. Rotating between a handful of reads is the shape this
         * catches; [DEFAULT_MAX_CONSECUTIVE_REPEATS] only ever compares a turn against the one
         * before it, so a rotation runs the step budget out instead.
         */
        const val DEFAULT_MAX_TURNS_WITHOUT_PROGRESS = 3

        /** The key [UnappliedWrites] keeps for a failed write whose call named no file. */
        private const val UNNAMED = ""
    }

    /**
     * One model turn, as the loop reads it and as the transcript keeps it.
     *
     * The two differ for a natively-calling backend: [text] carries the provider's calls rendered
     * as `<tool_call>` envelopes so extraction reads them by the one path a text-mode call takes,
     * while [historyText] is what the model actually wrote. Storing the envelopes would show the
     * model its own calls in a format the system prompt says is not read, which invites it to write
     * the next one as text.
     *
     * @property text the reply tool extraction reads.
     * @property historyText the ASSISTANT turn to store; the same text unless the caller says
     *   otherwise.
     */
    data class ModelReply(val text: String, val historyText: String = text)

    /** Callbacks so the caller can drive UI/state; all no-ops by default. */
    interface Events {
        /**
         * A model turn finished.
         * @param turn 1-based turn index.
         * @param text the model's reply, already streamed into the UI.
         */
        suspend fun onModelTurn(turn: Int, text: String) {}

        /**
         * A tool batch was executed; the caller renders it.
         * @param turn 1-based turn index.
         * @param calls the tool calls that ran.
         * @param results their results, positionally aligned with [calls].
         */
        suspend fun onToolResults(turn: Int, calls: List<ToolCall>, results: List<ToolResult>) {}

        /**
         * The loop stopped after hitting the iteration cap while still calling tools.
         * @param turns total turns run.
         */
        suspend fun onMaxIterationsReached(turns: Int) {}

        /**
         * The loop stopped after the model repeated identical tool calls.
         * @param turns total turns run.
         */
        suspend fun onRepeatedToolCalls(turns: Int) {}

        /**
         * The loop stopped because several turns in a row introduced no action the run had not
         * already taken — a rotation the consecutive-repeat guard cannot see.
         * @param turns total turns run.
         * @param staleLimit the configured no-progress limit the run hit; the same number every
         *   time, since the guard stops the moment the count reaches it.
         */
        suspend fun onNoProgressCycle(turns: Int, staleLimit: Int) {}

        /**
         * The model re-issued the batch it had just run successfully, which the loop reads as the
         * work being finished rather than as a repeat to abort on.
         *
         * The only completion that reaches the user with nothing said: it fires instead of
         * [onFinalAnswer], because the model never called the terminal tool.
         *
         * @param turn 1-based turn index.
         */
        suspend fun onRepeatAfterSuccess(turn: Int) {}

        /**
         * The model called the terminal tool to finish.
         * @param turn 1-based turn index.
         * @param message the model's final answer.
         */
        suspend fun onFinalAnswer(turn: Int, message: String) {}

        /**
         * The reply read like a tool call but none could be parsed out of it, so nothing ran.
         * @param turn 1-based turn index.
         * @param reason why the call could not be read.
         */
        suspend fun onUnparsedReply(turn: Int, reason: ToolCallExtractor.UnparsedReply) {}

        /**
         * The model stopped calling tools with the last batch's failure unaddressed.
         *
         * Distinct from [onFinalAnswer]: the task did not finish, so a run that ends here must not
         * be reported as completed.
         *
         * @param turn 1-based turn index.
         */
        suspend fun onAbandonedAfterFailure(turn: Int) {}

        /**
         * A run that has used tools replied without the terminal tool, so it was asked to finish
         * or carry on rather than being ended on that reply.
         *
         * @param turn 1-based turn index.
         */
        suspend fun onUnfinishedReply(turn: Int) {}

        /**
         * The run was told to call [tool] before answering and tried to finish without it, so it
         * was asked to call it; a backend that forces the call never reaches this.
         *
         * @param turn 1-based turn index.
         * @param tool the tool the run had to call.
         */
        suspend fun onRequiredToolSkipped(turn: Int, tool: String) {}

        /**
         * The run tried to finish, by answering or by repeating a look it had just taken, while a
         * file change it attempted had failed and had not been redone, so it was told the change
         * was not made rather than being ended on a reply that probably says it was.
         *
         * @param turn 1-based turn index.
         */
        suspend fun onUnappliedWriteAnswer(turn: Int) {}

        /**
         * The run ended with a failed file change still outstanding, so whatever the last reply
         * says, the change was not made. Fires after the event for how the run ended, if it has one.
         *
         * @param turn 1-based turn index.
         */
        suspend fun onChangeNotMade(turn: Int) {}
    }

    /** Why the loop stopped. */
    enum class StopReason {
        /** The model ended the run itself, with nothing outstanding. */
        COMPLETED,

        /** The step budget ran out first. */
        MAX_ITERATIONS,

        /** The model kept re-issuing a batch that was not working. */
        REPEATED,

        /** The model kept re-using actions it had already taken, without introducing a new one. */
        CYCLING,

        /** A reply meant to call a tool and no call could be read out of it. */
        UNPARSABLE,

        /** The model gave up: it stopped calling tools with a failed one unaddressed. */
        ABANDONED,
    }

    /**
     * Outcome of a run; [completed] is true when the model ended on its own with nothing left
     * outstanding, which is not the same as the loop simply having stopped.
     * @property turns model turns executed.
     * @property reason why the loop stopped.
     */
    data class Result(val turns: Int, val reason: StopReason) {
        val completed: Boolean get() = reason == StopReason.COMPLETED
    }

    /**
     * Runs the tool loop until the model stops calling tools or a limit is hit.
     * @param history transcript, mutated in place; seed it with the user message.
     * @param generate renders one model turn from the transcript so far. Receives the turns
     *   structurally rather than pre-flattened, so a backend that speaks a real chat format can
     *   emit one turn per message; flattening callers can use [renderTranscript]. Returns a
     *   [ModelReply], whose two texts a natively-calling caller sets apart.
     * @param executeTools runs a batch of tool calls.
     * @param pathsOf the project paths a call names; the progress guard counts an earlier look at
     *   one a later call rewrote as a new action again rather than as a repeat.
     * @param changesPaths whether a call rewrites what it names.
     * @param writesFiles whether a call writes a file's content. Only these calls' failures count as a
     *   change not made, and only their successes as one redone: a shell command also changes the
     *   project, but its success says nothing about a failed edit.
     * @param writtenFilesOf the files a [writesFiles] call writes, each as one key however the call
     *   spelled its path. A key that is a bare file name, for a path that named no existing file, is
     *   also matched by a later write to any path ending in that name.
     * @param canonicalName the registered name a call's tool resolves to, e.g. `Run_Shell_Command` to
     *   `run_shell_command`; the name itself when none does. Everything keyed by tool name reads it.
     * @param requiredTool a tool the run must call before it may finish, e.g. a web search before a
     *   code review; asked for once through [requiredToolTurn]. Null requires nothing.
     * @param events UI/state callbacks.
     * @return the run [Result].
     */
    suspend fun run(
        history: MutableList<ChatMessage>,
        generate: suspend (turns: List<ChatMessage>) -> ModelReply,
        executeTools: suspend (List<ToolCall>) -> List<ToolResult>,
        pathsOf: (ToolCall) -> Set<String> = { emptySet() },
        changesPaths: (ToolCall) -> Boolean = { false },
        writesFiles: (ToolCall) -> Boolean = { false },
        writtenFilesOf: (ToolCall) -> Set<String> = pathsOf,
        canonicalName: (String) -> String = { it },
        requiredTool: String? = null,
        events: Events = object : Events {},
    ): Result {
        var turn = 0
        val progress = ToolCallProgressGuard(
            maxConsecutiveRepeats,
            maxTurnsWithoutProgress,
            pathsOf,
            changesPaths,
        )
        // Once a tool has run, only the terminal tool finishes the run; see [unfinishedTurn].
        var toolsRan = false
        var askedToFinish = false
        val unapplied = UnappliedWrites(progress)
        // Cleared once the tool runs or has been asked for, so a model that refuses it still stops.
        var requiredPending = requiredTool != null && requiredToolTurn != null
        while (turn < maxIterations) {
            turn++

            val reply = generate(history.toList())
            val text = reply.text
            // A native call with no prose beside it leaves nothing to record, and an empty turn is
            // not harmless: Gemini rejects a content part whose text is empty. The tool results
            // that follow name the call anyway.
            if (reply.historyText.isNotBlank()) {
                history.add(ChatMessage(Role.ASSISTANT, reply.historyText))
            }
            events.onModelTurn(turn, text)

            val calls = extractToolCalls(text)
            if (calls.isEmpty()) {
                // A reply with no call is an ordinary answer; one that meant to call and failed to
                // is a silent dead end, and reporting it COMPLETED is what hid it from the user.
                val unparsed = diagnoseUnparsedReply(text)
                if (unparsed != null) {
                    events.onUnparsedReply(turn, unparsed)
                    return unapplied.stop(turn, StopReason.UNPARSABLE, events)
                }
                if (requiredPending) {
                    requiredPending = false
                    askForRequiredTool(turn, requiredTool!!, history, events)
                    continue
                }
                // A prose answer can claim a failed change as readily as a terminal-tool one.
                if (unapplied.warn(turn, history, events)) continue
                // Asked once per stretch of prose, so a model that will not call it still stops. Also
                // after the warning: prose then may be the model saying how it will redo the change.
                if (toolsRan && !askedToFinish && unfinishedTurn != null) {
                    askedToFinish = true
                    events.onUnfinishedReply(turn)
                    history.add(ChatMessage(Role.USER, unfinishedTurn.invoke()))
                    continue
                }
                if (unapplied.outstanding) return unapplied.stop(turn, StopReason.ABANDONED, events)
                // Prose after a failed batch is the model giving up, not finishing: the run ends
                // with the user's request unmet, so reporting it COMPLETED overstates the outcome.
                if (progress.lastBatchFailed) {
                    events.onAbandonedAfterFailure(turn)
                    return Result(turn, StopReason.ABANDONED)
                }
                return Result(turn, StopReason.COMPLETED)
            }

            // Terminal tool alone ends the loop; if co-emitted with real tools, run those first.
            // Renamed once here: the progress guard and the result caps look tools up by exact name.
            val realCalls = (terminalTool?.let { tt ->
                calls.filterNot { isTerminalToolName(it.name, tt) }
            } ?: calls).map { it.copy(name = canonicalName(it.name)) }
            // Only the terminal tool: an answer, which a run owing its required tool may not give yet.
            if (realCalls.isEmpty() && requiredPending) {
                requiredPending = false
                askForRequiredTool(turn, requiredTool!!, history, events)
                continue
            }
            val terminal = terminalTool?.let { tt -> calls.firstOrNull { isTerminalToolName(it.name, tt) } }
            if (terminal != null && realCalls.isEmpty()) {
                if (unapplied.warn(turn, history, events)) continue
                // Told once and answered again: the user's change is still not made.
                if (unapplied.outstanding) return unapplied.stop(turn, StopReason.ABANDONED, events)
                events.onFinalAnswer(turn, respondMessageOf(terminal.args).orEmpty())
                return Result(turn, StopReason.COMPLETED)
            }

            when (progress.inspect(realCalls)) {
                ToolCallProgressGuard.Verdict.CYCLING -> {
                    events.onNoProgressCycle(turn, progress.staleTurns)
                    return unapplied.stop(turn, StopReason.CYCLING, events)
                }
                ToolCallProgressGuard.Verdict.ASSUME_COMPLETE -> {
                    // A repeated look is not completion while the change the user asked for failed.
                    if (unapplied.warn(turn, history, events)) continue
                    if (unapplied.outstanding) return unapplied.stop(turn, StopReason.ABANDONED, events)
                    events.onRepeatAfterSuccess(turn)
                    return Result(turn, StopReason.COMPLETED)
                }
                ToolCallProgressGuard.Verdict.REPEATED -> {
                    events.onRepeatedToolCalls(turn)
                    return unapplied.stop(turn, StopReason.REPEATED, events)
                }
                ToolCallProgressGuard.Verdict.PROCEED -> Unit
            }

            val results = executeTools(realCalls)
            if (realCalls.any { it.name == requiredTool }) requiredPending = false
            toolsRan = true
            askedToFinish = false
            progress.recordResults(results)
            unapplied.record(realCalls.zip(results).filter { (call, _) -> writesFiles(call) }, writtenFilesOf)
            events.onToolResults(turn, realCalls, results)
            history.add(ChatMessage(Role.USER, formatToolResults.format(realCalls, results)))
        }

        events.onMaxIterationsReached(turn)
        return unapplied.stop(turn, StopReason.MAX_ITERATIONS, events)
    }

    /**
     * The file changes a run attempted that failed and have not been redone, keyed by file so a
     * success on one file does not hide a failure on another, and what an answer or an ending does
     * while one is outstanding. Inert when [unappliedWriteTurn] is null.
     *
     * @param progress the run's guard, told when the run is sent back to look at the file again.
     */
    private inner class UnappliedWrites(private val progress: ToolCallProgressGuard) {
        private val failed = mutableSetOf<String>()
        // Every write that has worked this run: the same call again fails because it made the change.
        private val applied = mutableSetOf<ToolCall>()
        private var warned = false

        /** Whether a failed change is outstanding, with the check on. */
        val outstanding: Boolean get() = unappliedWriteTurn != null && failed.isNotEmpty()

        /**
         * Records one batch's file writes.
         *
         * A success, or a call the user refused or corrected at the approval dialog, settles
         * failures on its file from earlier batches only: the model wrote the batch before it saw
         * any of its results, so a success beside a failure on the same file is another change, not
         * the failed one redone. A later success on that file is taken for the redo. Which change a
         * retry redoes cannot be read off its text, and taking it for another change would report a
         * change that was made as not made. A call that names no file cannot be matched, so any
         * later success settles it. A call that already worked this run, issued again, fails
         * because it made the change, so it is not counted.
         *
         * @param writes the batch's file-writing calls with their results.
         * @param filesOf the keys of the files a call writes.
         */
        fun record(writes: List<Pair<ToolCall, ToolResult>>, filesOf: (ToolCall) -> Set<String>) {
            // Keys are looked up only while something is outstanding: an edit's can walk the project.
            if (failed.isNotEmpty()) {
                for ((call, result) in writes) {
                    if (!result.success && !result.notApproved) continue
                    val files = filesOf(call)
                    // A resolved key also settles a failure keyed by the file's bare name. A key with
                    // none (`app/`) must not settle the failure of a call that names no file.
                    files.forEach { file ->
                        failed.remove(file)
                        file.substringAfterLast('/').takeIf { it.isNotEmpty() }?.let(failed::remove)
                    }
                    if (files.isEmpty() || result.success) failed.remove(UNNAMED)
                }
            }
            writes.filter { (_, result) -> result.success }.mapTo(applied) { (call, _) -> call }
            for ((call, result) in writes) {
                if (result.success || result.notApproved || call in applied) continue
                failed.addAll(filesOf(call).ifEmpty { setOf(UNNAMED) })
            }
            // Re-armed once nothing is outstanding, so a later failure gets its own warning.
            if (failed.isEmpty()) warned = false
        }

        /**
         * Tells a run that answers over an [outstanding] failure that the change was not made; once,
         * until it is re-armed.
         * @return true when the run was told and goes on; false when nothing is outstanding or the
         *   run was told already.
         */
        suspend fun warn(turn: Int, history: MutableList<ChatMessage>, events: Events): Boolean {
            if (!outstanding || warned) return false
            warned = true
            events.onUnappliedWriteAnswer(turn)
            history.add(ChatMessage(Role.USER, unappliedWriteTurn!!.invoke()))
            // The turn asks for a fresh read, which must not end the run as a look repeated.
            progress.allowRepeatOfLastBatch()
            return true
        }

        /** Ends the run for [reason], first telling the user when a failed change is [outstanding]. */
        suspend fun stop(turn: Int, reason: StopReason, events: Events): Result {
            if (outstanding) events.onChangeNotMade(turn)
            return Result(turn, reason)
        }
    }

    /** Records the reply as not finishing the run and asks for [tool] in the next user turn. */
    private suspend fun askForRequiredTool(
        turn: Int,
        tool: String,
        history: MutableList<ChatMessage>,
        events: Events,
    ) {
        events.onRequiredToolSkipped(turn, tool)
        history.add(ChatMessage(Role.USER, requiredToolTurn!!.invoke(tool)))
    }

    /**
     * Flattens the transcript into one prompt string, with no trailing "Assistant:" cue, which the
     * backend appends itself. Only for backends whose transport carries a single string; one that
     * renders real conversation turns must be handed the [ChatMessage] list instead.
     * @param history the conversation so far.
     * @return the rendered prompt.
     */
    fun renderTranscript(history: List<ChatMessage>): String {
        val sb = StringBuilder()
        for ((index, message) in history.withIndex()) {
            if (index > 0) sb.append("\n\n")
            when (message.role) {
                Role.ASSISTANT -> sb.append("Assistant: ").append(message.content)
                else -> sb.append(message.content)
            }
        }
        return sb.toString()
    }
}
