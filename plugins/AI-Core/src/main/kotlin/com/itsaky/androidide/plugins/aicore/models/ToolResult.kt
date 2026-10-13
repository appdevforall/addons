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
