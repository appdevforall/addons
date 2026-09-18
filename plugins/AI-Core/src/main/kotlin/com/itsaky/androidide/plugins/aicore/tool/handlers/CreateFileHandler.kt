package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema

private const val TAG = "$LOG_PREFIX.CreateFileHandler"

/**
 * Handler for creating new files.
 */
class CreateFileHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "create_file"
    override val parametersSchema = ToolSchema.objectOf(
        "file_path" to ToolSchema.string("Project-relative path of the file to create."),
        "content" to ToolSchema.string("The file's full contents."),
        required = listOf("file_path", "content"),
    )
    override val description = "Create a new file with given content"
    override val requiresApproval = true  // Requires approval for file creation
    override val mutatesProject = true
    override val pathArgs = listOf("file_path")

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        var filePath = args["file_path"]?.toString()?.trim()
        val content = args["content"]?.toString() ?: ""

        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        return try {
            Log.d(TAG, "Creating file: $filePath")

            // Security: resolve against the project root and reject any escape.
            val file = PathGuard.resolveWithin(filePath)
                ?: return ToolResult.failure("File path must be within project directory")

            Log.d(TAG, "Resolved path: ${file.absolutePath}")

            // In-root is not enough: build trees, .git and keystores are never legal targets.
            PathGuard.writeDenialReason(file)?.let { reason ->
                Log.w(TAG, "Refusing creation of protected path: ${file.path}")
                return ToolResult.failure("Cannot create $reason")
            }

            if (file.exists()) {
                Log.w(TAG, "File already exists: ${file.absolutePath}")
                return ToolResult.failure("File already exists: $filePath")
            }

            // Create parent directories if needed
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                Log.d(TAG, "Creating parent directories: ${parentDir.absolutePath}")
                val created = parentDir.mkdirs()
                Log.d(TAG, "Parent directories creation result: $created")
            }

            // Write content
            Log.d(TAG, "Writing ${content.length} characters to file")
            file.writeText(content)

            Log.d(TAG, "File created successfully: ${file.absolutePath}")

            ToolResult.success(
                message = "Created file: $filePath (${content.length} characters)",
                data = file.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating file at $filePath", e)
            ToolResult.failure(
                "Error creating file: ${e.message}",
                "Path: $filePath\nError: ${e.stackTraceToString()}"
            )
        }
    }
}
