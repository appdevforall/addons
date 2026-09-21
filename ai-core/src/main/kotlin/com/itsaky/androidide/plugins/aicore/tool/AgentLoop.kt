package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role

/**
 * The agentic tool-loop: each turn renders the transcript into a prompt, generates a
 * reply, and runs any tool calls, looping until the model stops or a limit is hit.
 * Free of Android/coroutine/UI deps so it unit-tests with plain fakes.
 */
class AgentLoop(
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val toolOutputCharLimit: Int = DEFAULT_TOOL_OUTPUT_CHAR_LIMIT,
    private val maxConsecutiveRepeats: Int = DEFAULT_MAX_CONSECUTIVE_REPEATS,
    private val maxTurnsWithoutProgress: Int = DEFAULT_MAX_TURNS_WITHOUT_PROGRESS,
    private val extractToolCalls: (String) -> List<ToolCall> = ToolCallExtractor::extractToolCalls,
    private val diagnoseUnparsedReply: (String) -> ToolCallExtractor.UnparsedReply? =
        ToolCallExtractor::diagnoseUnparsedReply,
    private val terminalTool: String? = null,
) {

    companion object {
        /**
         * Max model turns per user message, as a backstop against a model that never stops calling
         * tools. At 8 a two-line rename ran out mid-way and left the file half-edited; the
         * repeated-call guard and per-write approval are the tighter limits.
         */
        const val DEFAULT_MAX_ITERATIONS = 16

        /** Per-tool-result cap fed back into the prompt, so big outputs don't blow a local model's context. */
        const val DEFAULT_TOOL_OUTPUT_CHAR_LIMIT = 4000

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
     * @param events UI/state callbacks.
     * @return the run [Result].
     */
    suspend fun run(
        history: MutableList<ChatMessage>,
        generate: suspend (turns: List<ChatMessage>) -> ModelReply,
        executeTools: suspend (List<ToolCall>) -> List<ToolResult>,
        pathsOf: (ToolCall) -> Set<String> = { emptySet() },
        changesPaths: (ToolCall) -> Boolean = { false },
        events: Events = object : Events {},
    ): Result {
        var turn = 0
        val progress = ToolCallProgressGuard(
            maxConsecutiveRepeats,
            maxTurnsWithoutProgress,
            pathsOf,
            changesPaths,
        )
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
                    return Result(turn, StopReason.UNPARSABLE)
                }
                // Prose after a failed batch is the model giving up, not finishing: the run ends
                // with the user's request unmet, so reporting it COMPLETED overstates the outcome.
                if (progress.lastBatchFailed) {
                    events.onAbandonedAfterFailure(turn)
                    return Result(turn, StopReason.ABANDONED)
                }
                return Result(turn, StopReason.COMPLETED)
            }

            // Terminal tool alone ends the loop; if co-emitted with real tools, run those first.
            val realCalls = terminalTool?.let { tt ->
                calls.filterNot { isTerminalToolName(it.name, tt) }
            } ?: calls
            terminalTool?.let { tt ->
                val terminal = calls.firstOrNull { isTerminalToolName(it.name, tt) }
                if (terminal != null && realCalls.isEmpty()) {
                    events.onFinalAnswer(turn, respondMessageOf(terminal.args).orEmpty())
                    return Result(turn, StopReason.COMPLETED)
                }
            }

            when (progress.inspect(realCalls)) {
                ToolCallProgressGuard.Verdict.CYCLING -> {
                    events.onNoProgressCycle(turn, progress.staleTurns)
                    return Result(turn, StopReason.CYCLING)
                }
                ToolCallProgressGuard.Verdict.ASSUME_COMPLETE -> {
                    events.onRepeatAfterSuccess(turn)
                    return Result(turn, StopReason.COMPLETED)
                }
                ToolCallProgressGuard.Verdict.REPEATED -> {
                    events.onRepeatedToolCalls(turn)
                    return Result(turn, StopReason.REPEATED)
                }
                ToolCallProgressGuard.Verdict.PROCEED -> Unit
            }

            val results = executeTools(realCalls)
            progress.recordResults(results)
            events.onToolResults(turn, realCalls, results)
            history.add(ChatMessage(Role.USER, formatToolResults(realCalls, results)))
        }

        events.onMaxIterationsReached(turn)
        return Result(turn, StopReason.MAX_ITERATIONS)
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

    /**
     * Renders tool results for the next prompt, capping each body and wrapping it in
     * `<tool_response>` tags that chat-tuned models are trained to read. Handed the same content as
     * bare prose, a small model tends to re-issue the call it already ran.
     * @param calls the tool calls that ran.
     * @param results their results, positionally aligned with [calls].
     * @return the formatted results block.
     */
    fun formatToolResults(calls: List<ToolCall>, results: List<ToolResult>): String {
        val sb = StringBuilder()
        results.forEachIndexed { index, result ->
            val name = calls.getOrNull(index)?.name ?: "tool"
            val body = if (result.success) {
                buildString {
                    append(result.message)
                    result.data?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                }
            } else {
                buildString {
                    append("FAILED: ").append(result.message)
                    result.error_details?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                }
            }
            sb.append("<tool_response>\n")
                .append("[").append(name).append("] ").append(truncate(body)).append("\n")
                .append("</tool_response>\n\n")
        }
        sb.append(
            "Base your reply strictly on the tool result(s) above — report only what they actually say; " +
                "do not invent, assume, or contradict them. "
        )
        if (results.isNotEmpty() && results.all { it.success }) {
            sb.append(
                "The action succeeded. If this satisfies the user's request, you are DONE — reply with the " +
                    "\"respond\" tool briefly confirming what happened. Do NOT call another tool unless the " +
                    "request clearly needs a further step."
            )
        } else {
            sb.append("If the task is complete, give the user your final answer. Otherwise, call the next tool.")
        }
        return sb.toString()
    }

    private fun truncate(text: String): String =
        if (text.length <= toolOutputCharLimit) text
        else text.take(toolOutputCharLimit) + "\n…[truncated ${text.length - toolOutputCharLimit} chars]"
}
