package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult

/** Words a tool batch's results as the user turn that carries them back to the model. */
fun interface ToolResultsFormatter {

    /**
     * @param calls the tool calls that ran.
     * @param results their results, positionally aligned with [calls].
     * @return the turn to add to the transcript.
     */
    suspend fun format(calls: List<ToolCall>, results: List<ToolResult>): String
}
