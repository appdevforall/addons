package com.itsaky.androidide.plugins.aiagentmcp.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpTranscript
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Shows a failed Connect's log as cards, with a button that copies all of it as plain text. */
object McpTranscriptDialog {

    /** How long Copy reads "Copied" before it is offered again. */
    private val COPIED_FEEDBACK = 2.seconds

    /**
     * Copy stays in the dialog: the log is usually copied and then read on.
     * @param context the context the dialog is shown over.
     * @param inflater the plugin's inflater, so the layout resolves against the plugin's resources.
     * @param scope the caller's view scope; a copy still running is cancelled with it.
     * @param log the attempt's exchanges, credentials already redacted.
     * @return the dialog, for the caller to dismiss when its view goes.
     */
    fun show(
        context: Context,
        inflater: LayoutInflater,
        scope: CoroutineScope,
        log: McpTranscript.Log,
    ): AlertDialog {
        val view = inflater.inflate(R.layout.dialog_mcp_transcript, null)
        val pluginContext = view.context
        McpConnectionLogView(inflater, McpTranscriptStrings(pluginContext))
            .bind(view.findViewById<LinearLayout>(R.id.mcpLogEntries), log)
        // No listener on Copy here: the builder's own dismisses the dialog; it is bound after show().
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(pluginContext.getString(R.string.mcp_transcript_title))
            .setView(view)
            .setPositiveButton(pluginContext.getString(R.string.mcp_transcript_copy), null)
            .setNegativeButton(pluginContext.getString(R.string.mcp_transcript_close), null)
            .show()
        // The dialog's own buttons take the host's grey accent; give them the plugin's.
        val primary = ContextCompat.getColor(pluginContext, R.color.plugin_primary)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(primary)
        val copyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE) ?: return dialog
        copyButton.setTextColor(primary)

        var copying: Job? = null
        copyButton.setOnClickListener {
            copying?.cancel()
            copying = scope.launch { copy(context, pluginContext, copyButton, log.text) }
        }
        dialog.setOnDismissListener { copying?.cancel() }
        return dialog
    }

    private suspend fun copy(context: Context, pluginContext: Context, button: Button, text: String) {
        val label = pluginContext.getString(R.string.mcp_transcript_title)
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        // On the main thread: a short binder call, and the clipboard service is Looper-bound on old APIs.
        if (runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(label, text)) }.isFailure) return
        button.isEnabled = false
        button.text = pluginContext.getString(R.string.mcp_transcript_copied)
        // Not restored on cancellation: that only comes with the dialog or its view going.
        delay(COPIED_FEEDBACK)
        button.text = pluginContext.getString(R.string.mcp_transcript_copy)
        button.isEnabled = true
    }
}
