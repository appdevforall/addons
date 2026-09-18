package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import java.io.File

private const val TAG = "$LOG_PREFIX.SearchProjectHandler"

/**
 * Handler for searching files in the project.
 */
class SearchProjectHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "search_project"
    override val parametersSchema = ToolSchema.objectOf(
        "query" to ToolSchema.string("Text to search for; a file name unless searching contents."),
        "project_dir" to ToolSchema.string(
            "Project-relative directory to search under. Defaults to the whole project."
        ),
        "search_in_contents" to ToolSchema.boolean(
            "Search inside files instead of matching their names."
        ),
        required = listOf("query"),
    )
    override val description = "Search for files by name or content in the project"
    override val requiresApproval = false
    override val pathArgs = listOf("project_dir")

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val query = args["query"]?.toString()?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult.failure("query is required")
        }

        val projectDir = args["project_dir"]?.toString()?.trim()
        val searchInContents = args["search_in_contents"]?.toString()?.toBoolean() ?: false

        // Confine the search to the project root. Default to it, and reject any
        // explicit project_dir that resolves outside it, so a prompt-injected
        // model can't read/exfiltrate arbitrary files on external storage.
        val searchRoot = if (projectDir.isNullOrBlank()) {
            PathGuard.projectRoot()?.let { File(it) }
                ?: return ToolResult.failure("Search directory must be within the project directory")
        } else {
            PathGuard.resolveWithin(projectDir)
                ?: return ToolResult.failure("Search directory must be within the project directory")
        }

        return try {
            if (!searchRoot.exists() || !searchRoot.isDirectory) {
                return ToolResult.failure("Invalid project directory: ${searchRoot.absolutePath}")
            }

            val results = mutableListOf<String>()
            searchFilesRecursive(searchRoot, query, results, searchInContents, maxResults = 100)

            if (results.isEmpty()) {
                ToolResult.success("No ${if (searchInContents) "content" else "files"} found matching '$query'", "")
            } else {
                ToolResult.success(
                    message = "Found ${results.size} match(es) for '$query'",
                    data = results.joinToString("\n")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching project", e)
            ToolResult.failure("Error searching project: ${e.message}", e.stackTraceToString())
        }
    }

    private fun searchFilesRecursive(
        dir: File,
        query: String,
        results: MutableList<String>,
        searchInContents: Boolean,
        maxResults: Int,
        depth: Int = 0
    ) {
        if (depth > 10 || results.size >= maxResults) return

        dir.listFiles()?.forEach { file ->
            if (results.size >= maxResults) return

            if (file.isDirectory && !file.name.startsWith(".")) {
                searchFilesRecursive(file, query, results, searchInContents, maxResults, depth + 1)
            } else if (file.isFile) {
                // First: check filename
                if (file.name.contains(query, ignoreCase = true)) {
                    results.add("📄 [FILE] ${file.absolutePath}")
                    return@forEach
                }

                // Second: check file contents if requested
                if (searchInContents && isSearchableFile(file)) {
                    try {
                        val content = file.readText()
                        if (content.contains(query, ignoreCase = true)) {
                            // Find matching lines with context
                            val lines = content.split("\n")
                            lines.forEachIndexed { index, line ->
                                if (line.contains(query, ignoreCase = true) && results.size < maxResults) {
                                    val lineNum = index + 1
                                    val context = line.take(80)
                                    results.add("📄 ${file.absolutePath}:$lineNum → $context")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Skip files that can't be read as text
                        Log.d(TAG, "Skipped non-text file: ${file.name}")
                    }
                }
            }
        }
    }

    private fun isSearchableFile(file: File): Boolean {
        val name = file.name.lowercase()
        val binaryExtensions = listOf(".apk", ".dex", ".so", ".zip", ".jar", ".class", ".bin")
        return !binaryExtensions.any { name.endsWith(it) }
    }
}
