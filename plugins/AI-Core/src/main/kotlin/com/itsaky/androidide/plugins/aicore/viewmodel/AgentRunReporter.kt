package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.AgentLoop
import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolCallExtractor

/**
 * Traces a run turn by turn and, for the stops a user can act on, asks [notices] to say so.
 * Split out of the chat because narrating a run is not the chat's job: the trace vocabulary is one
 * thing to read here, and the wording of a stop stays with the screen that shows it.
 *
 * @param notices where a user-visible stop is announced.
 */
internal class AgentRunReporter(private val notices: Notices) : AgentLoop.Events {

    /**
     * The stops worth telling the user about, and nothing else the chat can do. The reporter
     * decides *that* the user is told; the implementation decides how it is worded and shown.
     */
    interface Notices {

        /**
         * The run used its whole step budget with tools still outstanding.
         * @param turns turns run.
         */
        suspend fun stepBudgetExhausted(turns: Int)

        /** The model kept re-issuing one batch that was not working. */
        suspend fun repeatedCalls()

        /** The model kept circling actions it had already taken. */
        suspend fun noProgress()
    }

    // Numbers the turn between its reply and the tools it runs, so the step budget a run spent on
    // one tool is counted off the trace, not guessed.
    override suspend fun onModelTurn(turn: Int, text: String) {
        AgentTrace.stage("TURN", "turn=$turn chars=${text.length}")
    }

    override suspend fun onToolResults(
        turn: Int,
        calls: List<ToolCall>,
        results: List<ToolResult>,
    ) {
        calls.forEachIndexed { index, call ->
            val result = results.getOrNull(index)
            AgentTrace.stage(
                "RESULT",
                "turn=$turn ${call.name} success=${result?.success}",
                AgentTrace.preview(result?.message),
            )
        }
    }

    override suspend fun onFinalAnswer(turn: Int, message: String) {
        AgentTrace.stage(
            "ANSWER",
            "turn=$turn chars=${message.length}",
            AgentTrace.preview(message),
        )
    }

    override suspend fun onRepeatAfterSuccess(turn: Int) {
        AgentTrace.stage("LOOP", "turn=$turn assumed-complete=repeat-after-success")
    }

    // No notice: AgentReplyRenderer already puts this same advice in the turn's own bubble.
    override suspend fun onUnparsedReply(turn: Int, reason: ToolCallExtractor.UnparsedReply) {
        AgentTrace.refusal("PARSE", "turn=$turn reason=$reason", "reply carried no readable tool call")
    }

    // No notice: the reply itself already carries the model's account of the failure.
    override suspend fun onAbandonedAfterFailure(turn: Int) {
        AgentTrace.refusal("LOOP", "turn=$turn", "stopped with a failed tool unaddressed")
    }

    override suspend fun onMaxIterationsReached(turns: Int) {
        AgentTrace.refusal("LOOP", "turns=$turns", "iteration cap reached")
        notices.stepBudgetExhausted(turns)
    }

    override suspend fun onRepeatedToolCalls(turns: Int) {
        AgentTrace.refusal("LOOP", "turns=$turns", "identical tool calls repeated")
        notices.repeatedCalls()
    }

    override suspend fun onNoProgressCycle(turns: Int, staleLimit: Int) {
        AgentTrace.refusal(
            "LOOP",
            "turns=$turns",
            "no new tool action for the configured $staleLimit turns",
        )
        notices.noProgress()
    }
}
