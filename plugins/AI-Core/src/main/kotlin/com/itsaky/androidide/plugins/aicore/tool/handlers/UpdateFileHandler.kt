package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema

private const val TAG = "$LOG_PREFIX.UpdateFileHandler"

/**
 * Handler for updating existing files.
 */
class UpdateFileHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "update_file"
    override val parametersSchema = ToolSchema.objectOf(
        "file_path" to ToolSchema.string("Project-relative path of the file to overwrite."),
        "content" to ToolSchema.string("The file's new full contents."),
        required = listOf("file_path", "content"),
    )
    override val description = "Update an existing file with new content"
    override val requiresApproval = true  // Requires approval for file modification
    override val mutatesProject = true
    override val pathArgs = listOf("file_path")

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filePath = args["file_path"]?.toString()?.trim()
        val content = args["content"]?.toString() ?: ""

        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        return try {
            val file = PathGuard.resolveWithin(filePath)
                ?: return ToolResult.failure("File path must be within project directory")

            // In-root is not enough: build trees, .git and keystores are never legal targets.
            PathGuard.writeDenialReason(file)?.let { reason ->
                Log.w(TAG, "Refusing update of protected path: ${file.path}")
                return ToolResult.failure("Cannot update $reason")
            }

            if (!file.exists()) {
                ToolResult.failure("File does not exist: $filePath")
            } else if (!file.isFile) {
                ToolResult.failure("Path is not a file: $filePath")
            } else {
                val previous = file.readText()
                try {
                    file.writeText(content)
                } catch (e: Exception) {
                    runCatching { file.writeText(previous) }
                        .onFailure { Log.e(TAG, "Could not restore $filePath after a failed write", it) }
                    throw e
                }

                ToolResult.success(
                    message = "Updated file: $filePath (${content.length} characters)",
                    data = filePath
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating file", e)
            ToolResult.failure("Error updating file: ${e.message}", e.stackTraceToString())
        }
    }
}
