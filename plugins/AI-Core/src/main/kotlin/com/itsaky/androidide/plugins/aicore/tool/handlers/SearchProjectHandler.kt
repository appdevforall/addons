package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import java.io.File
import java.io.IOException

private const val TAG = "$LOG_PREFIX.SearchProjectHandler"

/**
 * Handler for searching files in the project.
 */
class SearchProjectHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "search_project"
    override val parametersSchema = ToolSchema.objectOf(
        "query" to ToolSchema.string(),
        "project_dir" to ToolSchema.string(),
        "search_in_contents" to ToolSchema.boolean(),
        required = listOf("query"),
    )
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
            when {
                !searchRoot.exists() ->
                    ToolResult.failure("No such file or directory: ${displayPath(searchRoot)}")
                searchRoot.isFile -> searchSingleFile(searchRoot, query)
                else -> searchTree(searchRoot, query, searchInContents)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error searching project", e)
            ToolResult.failure("Error searching project: ${e.message}", e.stackTraceToString())
        }
    }

    /** Searches a file's contents; its name is moot because the model already named it. */
    private fun searchSingleFile(file: File, query: String): ToolResult {
        if (!isTextFile(file)) {
            return ToolResult.failure("${file.name} is not a text file")
        }
        val results = mutableListOf<String>()
        searchFileContents(file, query, results, MAX_RESULTS)
        return resultOf(results, query, "No lines matching '$query' in ${file.name}", " in ${file.name}")
    }

    private fun searchTree(root: File, query: String, searchInContents: Boolean): ToolResult {
        val results = mutableListOf<String>()
        searchFilesRecursive(root, query, results, searchInContents, MAX_RESULTS)
        val noMatches = "No ${if (searchInContents) "content" else "files"} found matching '$query'"
        return resultOf(results, query, noMatches)
    }

    private fun resultOf(results: List<String>, query: String, noMatches: String, scope: String = ""): ToolResult =
        if (results.isEmpty()) {
            ToolResult.success(noMatches, "")
        } else {
            ToolResult.success(
                message = "Found ${results.size} match(es) for '$query'$scope",
                data = results.joinToString("\n")
            )
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
                    results.add("📄 [FILE] ${displayPath(file)}")
                    return@forEach
                }

                // Second: check file contents if requested
                // The sniff opens the file too, so a file deleted mid-walk is skipped, not fatal.
                if (searchInContents) {
                    try {
                        if (isTextFile(file)) searchFileContents(file, query, results, maxResults)
                    } catch (e: IOException) {
                        Log.d(TAG, "Skipped unreadable file: ${file.name}", e)
                    }
                }
            }
        }
    }

    /** Streams [file] line by line, appending each line containing [query] to [results]. */
    private fun searchFileContents(file: File, query: String, results: MutableList<String>, maxResults: Int) {
        file.bufferedReader().useLines { lines ->
            for ((index, line) in lines.withIndex()) {
                if (results.size >= maxResults) return
                val at = line.indexOf(query, ignoreCase = true)
                if (at >= 0) results.add("📄 ${displayPath(file)}:${index + 1} → ${snippet(line, at, query.length)}")
            }
        }
    }

    private fun displayPath(file: File): String =
        file.relativeToOrSelf(File(PathGuard.projectRoot())).path

    /** Known binary extensions, else git's heuristic: a NUL byte in the first block means binary. */
    private fun isTextFile(file: File): Boolean {
        val name = file.name.lowercase()
        if (BINARY_EXTENSIONS.any { name.endsWith(it) }) return false
        val head = ByteArray(SNIFF_BYTES)
        val read = file.inputStream().use { it.read(head) }
        return (0 until read.coerceAtLeast(0)).none { head[it] == 0.toByte() }
    }

    private companion object {
        const val MAX_RESULTS = 100
        const val SNIFF_BYTES = 8 * 1024
        const val SNIPPET_LENGTH = 80
        const val SNIPPET_LEAD = 20
        val BINARY_EXTENSIONS = listOf(".apk", ".dex", ".so", ".zip", ".jar", ".class", ".bin")

        /** At least [SNIPPET_LENGTH] chars of [line], widened to keep the whole [length]-char match at [at]. */
        fun snippet(line: String, at: Int, length: Int): String {
            if (line.length <= SNIPPET_LENGTH) return line
            val start = (at - SNIPPET_LEAD).coerceIn(0, line.length - SNIPPET_LENGTH)
            val end = maxOf(start + SNIPPET_LENGTH, at + length)
            return (if (start > 0) "…" else "") + line.substring(start, end) + (if (end < line.length) "…" else "")
        }
    }
}
