package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.SelectionRange
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Applies an edit to a file **open in the editor** through the buffer, not behind it: unsaved work is
 * what changes, the change is one Ctrl+Z away, and no stale buffer can overwrite it. Owns the
 * coordinate conversion, since [IdeEditorService.replaceRange] takes 0-based (line, column) pairs.
 * The save is file-targeted, so it never depends on - or steals - the user's tab focus.
 * @param editorService the host editor.
 * @param mainDispatcher dispatcher for editor-UI calls; overridden in unit tests.
 */
class EditorBufferApplier(
    private val editorService: IdeEditorService,
    private val mainDispatcher: CoroutineDispatcher,
) {

    private companion object {
        const val TAG = "$LOG_PREFIX.EditorBufferApplier"
    }

    /** Outcome of [apply]. */
    sealed interface Outcome {
        /**
         * The buffer now holds the edit.
         * @property saved whether it also reached disk.
         */
        data class Applied(val saved: Boolean) : Outcome

        /**
         * Nothing was changed.
         * @property reason a model-readable explanation.
         */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Replaces [oldString] with [newString] in [file]'s open buffer, then saves it. A single match is
     * replaced in place, keeping undo history; `replace_all` and CRLF swap the whole buffer instead.
     * Re-read and written in **one** main-thread block, so no offset can land on concurrent typing.
     * @param file the open file.
     * @param displayPath the project-relative path to name in messages.
     * @param matched the buffer text the edit was computed against.
     * @param updated [matched] with the replacement already applied.
     * @param oldString the snippet as it appears in [matched].
     * @param newString its replacement.
     * @param occurrences how many times [oldString] appears in [matched].
     * @return the outcome to report to the model.
     */
    suspend fun apply(
        file: File,
        displayPath: String,
        matched: String,
        updated: String,
        oldString: String,
        newString: String,
        occurrences: Int,
    ): Outcome {
        // In-place for one match with no CR to confuse the line model; else swap the buffer.
        val inPlace = occurrences == 1 && !matched.contains('\r') && !oldString.contains('\r')
        val range = if (inPlace) {
            rangeOf(matched, matched.indexOf(oldString), oldString)
        } else {
            wholeBufferRange(matched)
        }
        val replacement = if (inPlace) newString else updated

        AgentTrace.stage(
            "EDIT",
            "apply=editor mode=${if (inPlace) "in-place" else "whole-buffer"} " +
                "range=${range.startLine}:${range.startColumn}-${range.endLine}:${range.endColumn}",
        )

        // Read-check and mutate in one main-thread block; a refusal short-circuits the save.
        val refusal = withContext(mainDispatcher) {
            val current = editorService.getFileContent(file)
            if (current != matched) {
                AgentTrace.refusal(
                    "EDIT", "apply=editor path=${file.name}",
                    "buffer changed after analysis (was ${matched.length} chars, " +
                        "now ${current?.length ?: -1}); not applying stale offsets",
                )
                return@withContext Outcome.Failed(
                    "The editor buffer for $displayPath changed while this edit was waiting for " +
                        "approval — read the file again and redo the edit against its current text"
                )
            }

            if (!editorService.replaceRange(file, range, replacement)) {
                AgentTrace.refusal("EDIT", "apply=editor path=${file.name}", "replaceRange returned false")
                return@withContext Outcome.Failed(
                    "Could not apply the edit to the open editor for $displayPath — close the file and retry"
                )
            }

            null
        }
        refusal?.let { return it }

        // Only a denied filesystem.write means "unsaved"; cancellation and defects must propagate.
        val saved = try {
            editorService.saveFile(file)
        } catch (e: SecurityException) {
            AgentTrace.refusal("EDIT", "apply=editor path=${file.name}", "saveFile: ${e.message}")
            false
        }

        Log.d(TAG, "Edited $displayPath in the editor buffer (saved=$saved)")
        return Outcome.Applied(saved = saved)
    }

    /**
     * Maps a character offset span onto the editor's 0-based (line, column) coordinates.
     * @param text the buffer contents the offsets refer to.
     * @param start offset of the first character to replace.
     * @param match the matched text, whose length gives the end offset.
     */
    private fun rangeOf(text: String, start: Int, match: String): SelectionRange {
        val end = start + match.length
        val startLine = text.countNewlinesBefore(start)
        val endLine = text.countNewlinesBefore(end)
        return SelectionRange(
            startLine,
            start - text.lineStartOffset(startLine),
            endLine,
            end - text.lineStartOffset(endLine),
        )
    }

    /** The span covering the entire buffer, for a whole-content replacement. */
    private fun wholeBufferRange(text: String): SelectionRange {
        val lastLine = text.count { it == '\n' }
        return SelectionRange(0, 0, lastLine, text.length - text.lineStartOffset(lastLine))
    }

    private fun String.countNewlinesBefore(offset: Int): Int {
        var count = 0
        for (i in 0 until offset) if (this[i] == '\n') count++
        return count
    }

    /** Character offset at which 0-based [line] starts. */
    private fun String.lineStartOffset(line: Int): Int {
        if (line == 0) return 0
        var seen = 0
        for (i in indices) {
            if (this[i] == '\n') {
                seen++
                if (seen == line) return i + 1
            }
        }
        return length
    }
}
