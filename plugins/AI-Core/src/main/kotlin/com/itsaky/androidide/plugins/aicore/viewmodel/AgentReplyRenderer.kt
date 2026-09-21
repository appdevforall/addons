package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolCallExtractor
import com.itsaky.androidide.plugins.aicore.tool.isTerminalToolName
import com.itsaky.androidide.plugins.aicore.tool.respondMessageOf

/**
 * Decides what a model turn looks like in the transcript. Pure and string-injected so the precedence
 * is testable: it exists to stop a finished answer becoming "(no response)" because the model filed
 * it under an odd key, or wrote it as prose beside an empty `respond` envelope.
 */
object AgentReplyRenderer {

    /**
     * Whether this turn leaves no bubble behind.
     *
     * A turn that only asks for tools is work in progress, not something to read: the run reports
     * it on the single activity line ([AgentActivity]), which is replaced as each call starts, and
     * a failure still gets its own message. Before that line existed every such turn appended a
     * badge bubble and every result another, which is what buried the answer.
     *
     * A turn carrying the terminal call always keeps its bubble: that is the only place the answer
     * is rendered.
     *
     * @param toolCalls the calls parsed out of this turn.
     * @param terminalTool the name of the answer-carrying pseudo-tool (`respond`).
     * @return true when the turn should not reach the transcript.
     */
    fun isSilentTurn(toolCalls: List<ToolCall>, terminalTool: String): Boolean {
        if (toolCalls.isEmpty()) return false
        return toolCalls.none { isTerminalToolName(it.name, terminalTool) }
    }

    /**
     * Renders one model turn. Only reached for a turn [isSilentTurn] kept.
     * @param rawText the model's raw reply.
     * @param toolCalls the calls parsed out of it.
     * @param terminalTool the name of the answer-carrying pseudo-tool (`respond`).
     * @param lastToolFailed whether this run's most recent tool call failed.
     * @param actionFailedText what to show when the model claims success after a failed tool.
     * @param noResponseText last-resort text when the turn carries nothing to show.
     * @param unparsedReplyText what to show for a reply that meant to call a tool and failed to.
     * @return the text to display for this turn.
     */
    fun render(
        rawText: String,
        toolCalls: List<ToolCall>,
        terminalTool: String,
        lastToolFailed: Boolean,
        actionFailedText: String,
        noResponseText: String,
        unparsedReplyText: (ToolCallExtractor.UnparsedReply) -> String,
    ): String {
        val respondCall = toolCalls.firstOrNull { isTerminalToolName(it.name, terminalTool) }
        return when {
            respondCall != null && lastToolFailed -> actionFailedText
            // The answer wherever the model put it, then the prose beside an empty envelope.
            respondCall != null ->
                respondMessageOf(respondCall.args)
                    ?: ToolCallExtractor.proseOutsideToolCalls(rawText)
                    ?: noResponseText
            // A call that failed to parse: say so, rather than pasting the raw envelope on screen.
            else -> ToolCallExtractor.diagnoseUnparsedReply(rawText)?.let(unparsedReplyText)
                ?: rawText.ifBlank { noResponseText }
        }
    }
}
