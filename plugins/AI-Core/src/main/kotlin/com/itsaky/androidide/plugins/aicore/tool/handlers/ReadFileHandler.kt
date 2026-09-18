package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema

private const val TAG = "$LOG_PREFIX.ReadFileHandler"

/**
 * Handler for reading file contents.
 */
class ReadFileHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "read_file"
    override val parametersSchema = ToolSchema.objectOf(
        "file_path" to ToolSchema.string("Project-relative path of the file to read."),
        required = listOf("file_path"),
    )
    override val description = "Read the contents of a file"
    override val requiresApproval = false
    override val pathArgs = listOf("file_path", "path")
    override val resolvesPathsInternally = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        // Accept both "file_path" (standardized) and "path" (legacy LLM responses)
        val filePath = (args["file_path"] ?: args["path"])?.toString()?.trim()
        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path argument is required (or 'path' as fallback)")
        }

        return try {
            val file = when (val resolution = PathGuard.resolve(filePath)) {
                is PathGuard.Resolution.Resolved -> resolution.file
                is PathGuard.Resolution.Ambiguous -> {
                    val root = java.io.File(PathGuard.projectRoot())
                    return ToolResult.failure(
                        "Multiple files named '${resolution.baseName}' — specify a path",
                        resolution.matches.joinToString("\n") { it.relativeToOrSelf(root).path }
                    )
                }
                PathGuard.Resolution.Escaped ->
                    return ToolResult.failure("File path must be within project directory")
                PathGuard.Resolution.NotFound ->
                    return ToolResult.failure("File does not exist: $filePath")
            }

            when {
                !file.isFile -> ToolResult.failure("Path is not a file: $filePath")
                !file.canRead() -> ToolResult.failure("Cannot read file: $filePath")
                else -> {
                    val content = file.readText()
                    ToolResult.success(
                        message = "Read ${content.length} characters from $filePath",
                        data = content
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file", e)
            ToolResult.failure("Error reading file: ${e.message}")
        }
    }
}
