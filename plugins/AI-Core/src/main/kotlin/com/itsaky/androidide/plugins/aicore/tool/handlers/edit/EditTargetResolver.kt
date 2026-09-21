package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import java.io.File

/**
 * Turns the path a model asked for into the file an edit may touch. Containment is strict, but an
 * invented path with exactly ONE plausible candidate is corrected rather than bounced back, which
 * costs 4–9s per local turn. Not silent: the corrected path is what the approval dialog shows.
 */
object EditTargetResolver {

    private const val TAG = "$LOG_PREFIX.EditTargetResolver"

    /** Outcome of [resolve]. */
    sealed interface Target {
        /**
         * An existing, editable in-root file.
         * @property file the resolved file.
         * @property displayPath the path to show and report — the corrected one when the
         *   model's guess was wrong.
         * @property correctedFrom the model's original guess when it was corrected, else null.
         */
        data class Resolved(
            val file: File,
            val displayPath: String,
            val correctedFrom: String?,
        ) : Target

        /**
         * No file may be edited for this path.
         * @property reason a model-readable explanation, phrased to tell it what to do next.
         */
        data class Rejected(val reason: String) : Target
    }

    /**
     * Resolves [filePath] to an editable project file.
     * @param filePath the model-supplied path.
     * @return the target, or the rejection to report.
     */
    fun resolve(filePath: String): Target {
        val requested = PathGuard.resolveWithin(filePath)
            ?: return Target.Rejected("File path must be within project directory")

        var displayPath = filePath
        var correctedFrom: String? = null
        val file = if (requested.exists()) requested else {
            val suggestions = PathGuard.suggestPathsFor(filePath)
            val candidate = suggestions.unambiguous
            when {
                candidate != null -> {
                    val corrected = PathGuard.resolveWithin(candidate)
                        ?: return Target.Rejected("File does not exist: $filePath")
                    Log.i(TAG, "Resolved guessed path '$filePath' to '$candidate'")
                    correctedFrom = filePath
                    displayPath = candidate
                    corrected
                }
                suggestions.all.isEmpty() -> return Target.Rejected(
                    "File does not exist: $filePath — locate the real path with " +
                        "search_project first, or use create_file to make a new file"
                )
                else -> return Target.Rejected(
                    "File does not exist: $filePath — did you mean " +
                        suggestions.all.joinToString(" or ") { "\"$it\"" } +
                        "? Retry with that exact path."
                )
            }
        }

        if (!file.isFile) return Target.Rejected("Path is not a file: $displayPath")

        // In-root is not enough: build trees, .git and keystores must never be machine-edited.
        PathGuard.writeDenialReason(file)?.let { reason ->
            Log.w(TAG, "Refusing edit of protected path: ${file.path}")
            return Target.Rejected("Cannot edit $reason")
        }

        return Target.Resolved(file, displayPath, correctedFrom)
    }
}
