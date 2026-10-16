package com.itsaky.androidide.plugins.aiagentmcp.settings

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpTranscript

/**
 * The server dialog's status sentence and the Show details button under it.
 * Every message replaces the last, so a log never outlives the failure it explains.
 *
 * @param onShowDetails opens a log when the button is tapped.
 */
class McpStatusLine(
    private val message: TextView,
    private val details: Button,
    onShowDetails: (McpTranscript.Log) -> Unit,
) {

    private var log: McpTranscript.Log? = null

    init {
        details.setOnClickListener { log?.let(onShowDetails) }
    }

    /**
     * Shows [text], with the details button only when there is a [log] behind it.
     * @param log what went over the wire, for a failed Connect; null otherwise.
     */
    fun show(text: CharSequence, log: McpTranscript.Log? = null) {
        message.text = text
        this.log = log
        details.visibility = if (log == null) View.GONE else View.VISIBLE
    }
}
