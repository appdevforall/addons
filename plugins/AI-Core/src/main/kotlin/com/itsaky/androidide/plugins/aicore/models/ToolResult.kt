package com.itsaky.androidide.plugins.aicore.models

/**
 * Result from executing a tool/function call.
 */
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: String? = null,
    val error_details: String? = null,
    /** The user refused the call at the approval dialog, so the tool never ran. */
    val denied: Boolean = false,
    /**
     * The user refused or corrected the call at the approval dialog, so the tool never ran. A call
     * left unanswered is a plain failure, since nobody decided against it. True whenever [denied]
     * is; only [denied] offers the user a Retry.
     */
    val notApproved: Boolean = denied,
    /**
     * A write that failed because its change is in the file whatever was written to it since: a
     * create whose file exists, as no write tool deletes one.
     */
    val alreadyMade: Boolean = false,
    /**
     * A write that failed the way its own repeat fails once its change is made: an edit whose
     * old_string is not found. That shows the change made only if nothing has written the file
     * since the change worked, which only the agent loop can tell; a first attempt at text that was
     * never there fails the same way.
     */
    val madeIfUnchanged: Boolean = false,
    /**
     * The canonical path of the file a write resolved to, however the call spelled it: set by the
     * file-writing tools on success and on an [alreadyMade] or [madeIfUnchanged] failure, so a
     * repeat is matched to the change it repeats. Null for every other result.
     */
    val file: String? = null,
) {
    companion object {
        fun success(message: String, data: String? = null, file: String? = null): ToolResult {
            return ToolResult(true, message, data, file = file)
        }

        fun failure(message: String, error_details: String? = null): ToolResult {
            return ToolResult(false, message, error_details = error_details)
        }

        fun denied(message: String): ToolResult {
            return ToolResult(false, message, denied = true)
        }

        /** A call the user corrected at the approval dialog. */
        fun notApproved(message: String): ToolResult {
            return ToolResult(false, message, notApproved = true)
        }

        /** A write to [file] that failed because its change is there; see [alreadyMade]. */
        fun alreadyMade(message: String, file: String): ToolResult {
            return ToolResult(false, message, alreadyMade = true, file = file)
        }

        /** A write to [file] that failed as its repeat would once made; see [madeIfUnchanged]. */
        fun madeIfUnchanged(message: String, file: String): ToolResult {
            return ToolResult(false, message, madeIfUnchanged = true, file = file)
        }
    }

    fun toResultMap(): Map<String, Any> {
        return mapOf(
            "success" to success,
            "message" to message,
            "data" to (data ?: ""),
            "error_details" to (error_details ?: "")
        )
    }
}
