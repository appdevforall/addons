package com.itsaky.androidide.plugins.aiagentmcp.settings

import android.content.Context
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpTranscript

/**
 * [McpTranscript.Labels] resolved from this plugin's own `strings.xml`.
 * @param context this plugin's context; null resolves every label to empty rather than failing.
 */
class McpTranscriptStrings(private val context: Context?) : McpTranscript.Labels {

    override fun hidden(length: Int): String = string(R.string.mcp_transcript_hidden, length)

    override fun omitted(count: Int): String = string(R.string.mcp_transcript_omitted, count)

    override val nothingSent: String get() = string(R.string.mcp_transcript_nothing_sent)

    private fun string(resId: Int, vararg args: Any): String =
        context?.getString(resId, *args).orEmpty()
}
