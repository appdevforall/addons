package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import java.io.File

private const val TAG = "$LOG_PREFIX.ListFilesHandler"

/**
 * Handler for listing files in a directory.
 */
class ListFilesHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "list_files"
    override val parametersSchema = ToolSchema.objectOf(
        "directory" to ToolSchema.string(
            "Project-relative directory to list. Empty or omitted lists the project root."
        ),
    )
    override val description = "List files and directories in a given path"
    override val requiresApproval = false
    override val pathArgs = listOf("directory")
    // Resolved internally (below) to rescue slash-prefixed paths; opt out of the
    // Executor pre-guard that would reject "/app/src" first.
    override val resolvesPathsInternally = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val directory = args["directory"]?.toString()?.trim()?.takeIf { it.isNotBlank() }

        Log.d(TAG, "Listing files in directory: ${directory ?: "<project root>"}")

        return try {
            // Resolve/containment-check via PathGuard; blank defaults to the root.
            // A slash-prefixed relative dir ("/app/src") escapes the root, so retry
            // it as relative — matching Read/OpenFileHandler.
            val dir = if (directory == null) {
                File(PathGuard.projectRoot())
            } else {
                PathGuard.resolveWithin(directory)
                    ?: PathGuard.resolveWithin(directory.removePrefix("/"))
                    ?: return ToolResult.failure("Directory path must be within project directory")
            }
            Log.d(TAG, "Absolute path: ${dir.absolutePath}")

            if (!dir.exists()) {
                Log.w(TAG, "Directory does not exist: ${dir.absolutePath}")
                return ToolResult.failure("Directory does not exist: ${dir.absolutePath}")
            }

            if (!dir.isDirectory) {
                Log.w(TAG, "Path is not a directory: ${dir.absolutePath}")
                return ToolResult.failure("Path is not a directory: ${dir.absolutePath}")
            }

            val fileList = dir.listFiles()?.sortedBy { it.name } ?: emptyList()

            Log.d(TAG, "Found ${fileList.size} items")

            if (fileList.isEmpty()) {
                Log.w(TAG, "Directory is empty: ${dir.absolutePath}")
                return ToolResult.success(
                    message = "Directory is empty: ${dir.absolutePath}",
                    data = "(no files or directories)"
                )
            }

            // Format files and directories separately for better organization
            val directories = fileList.filter { it.isDirectory }
            val regularFiles = fileList.filter { it.isFile }

            val formatted = buildString {
                appendLine("📁 DIRECTORIES (${directories.size}):")
                directories.forEach { dir ->
                    appendLine("  ├── ${dir.name}/")
                }

                if (directories.isNotEmpty() && regularFiles.isNotEmpty()) {
                    appendLine()
                }

                appendLine("📄 FILES (${regularFiles.size}):")
                regularFiles.forEach { file ->
                    val size = formatSize(file.length())
                    val sizeStr = String.format("%-8s", size)  // Right-pad for alignment
                    appendLine("  ├── ${file.name} ($sizeStr)")
                }
            }

            ToolResult.success(
                message = "Found ${fileList.size} items in ${dir.absolutePath}",
                data = formatted
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error listing files in $directory", e)
            ToolResult.failure("Error listing files: ${e.message}", e.stackTraceToString())
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}
