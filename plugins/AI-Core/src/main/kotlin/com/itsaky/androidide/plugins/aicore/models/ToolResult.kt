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
) {
    companion object {
        fun success(message: String, data: String? = null): ToolResult {
            return ToolResult(true, message, data)
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
