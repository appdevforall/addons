package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolInt
import com.itsaky.androidide.plugins.aicore.tool.ToolOutputBudget
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.parseToolInt

private const val TAG = "$LOG_PREFIX.ReadFileHandler"

/**
 * Handler for reading file contents, a page at a time: whole lines that fit [budget], verbatim and
 * unnumbered so pages join back to the exact file, with a message naming where the next page starts.
 */
class ReadFileHandler(
    private val pluginContext: PluginContext,
    private val budget: ToolOutputBudget = ToolOutputBudget.DEFAULT,
) : ToolHandler {
    override val toolName = "read_file"
    override val parametersSchema = ToolSchema.objectOf(
        "file_path" to ToolSchema.string(),
        "start_line" to ToolSchema.integer(),
        "end_line" to ToolSchema.integer(),
        "start_column" to ToolSchema.integer(),
        required = listOf("file_path"),
    )
    override val requiresApproval = false
    override val pathArgs = listOf("file_path", "path")
    override val resolvesPathsInternally = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        // Accept both "file_path" (standardized) and "path" (legacy LLM responses)
        val filePath = (args["file_path"] ?: args["path"])?.toString()?.trim()
        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path argument is required (or 'path' as fallback)")
        }

        val request = when (val parsed = pageRequestOf(args)) {
            is ParsedPageRequest.Valid -> parsed.request
            is ParsedPageRequest.Invalid -> return ToolResult.failure(parsed.reason)
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
                else -> pageOf(FilePager(file.readText()), request, filePath)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading file", e)
            ToolResult.failure("Error reading file: ${e.message}")
        }
    }

    /**
     * The page that fits [budget] with its message. The message names the page's own line
     * numbers, so its length depends on the page; shrinking the room by the overflow converges.
     */
    private fun pageOf(pager: FilePager, request: PageRequest, path: String): ToolResult {
        if (pager.lineCount == 0) return ToolResult.success("$path is empty (0 lines)")
        var room = budget.chars() - DATA_SEPARATOR
        while (room >= MIN_ROOM) {
            val page = when (val result = pager.page(request, room)) {
                is PageResult.OutOfRange -> return ToolResult.failure(result.reason)
                is PageResult.Page -> result.page
            }
            val message = describe(page, path, request.endLine)
            val overflow = message.length + DATA_SEPARATOR + page.text.length - budget.chars()
            if (overflow <= 0) return ToolResult.success(message, page.text)
            room -= overflow
        }
        return ToolResult.failure("The tool output budget is too small to read $path")
    }

    companion object {
        /** The newline the prompt puts between a result's message and its data. */
        private const val DATA_SEPARATOR = 1

        /** A surrogate pair, the least a page can hold. */
        private const val MIN_ROOM = 2

        private val POSITION_ARGS = listOf("start_line", "start_column", "end_line")

        /** The call's line range, or the reason naming the first argument that is not a whole number. */
        internal fun pageRequestOf(args: Map<String, Any?>): ParsedPageRequest {
            val values = POSITION_ARGS.associateWith { parseToolInt(args[it]) }
            values.firstNotNullOfOrNull { (key, value) ->
                (value as? ToolInt.Invalid)?.let { ParsedPageRequest.Invalid("$key must be a whole number, was '${it.raw}'") }
            }?.let { return it }
            fun valueOf(key: String): Int? = (values.getValue(key) as? ToolInt.Present)?.value
            return ParsedPageRequest.Valid(
                PageRequest(
                    startLine = valueOf("start_line") ?: 1,
                    startColumn = valueOf("start_column") ?: 1,
                    endLine = valueOf("end_line"),
                )
            )
        }

        /**
         * What [page] covers and how to read on, e.g.
         * `Lines 1-95 of 410 in Main.kt (17643 characters). Next: read_file file_path=Main.kt start_line=96`.
         */
        internal fun describe(page: FilePage, path: String, endLine: Int?): String = buildString {
            append(rangeOf(page)).append(" of ").append(page.totalLines).append(" in ").append(path)
            append(" (").append(page.totalChars).append(" characters).")
            val next = page.next
            when {
                next != null -> {
                    append(" Next: read_file file_path=").append(path).append(" start_line=").append(next.line)
                    if (next.column > 1) append(" start_column=").append(next.column)
                    endLine?.let { append(" end_line=").append(it) }
                }
                page.endLine == page.totalLines && page.endColumn == null -> append(" End of file.")
                endLine != null && page.endColumn == null ->
                    append(" End of the requested range (end_line=").append(endLine).append(").")
            }
        }

        private fun rangeOf(page: FilePage): String = with(page) {
            when {
                startColumn > 1 || endColumn != null ->
                    "From line $startLine column $startColumn to " +
                        (endColumn?.let { "line $endLine column $it" } ?: "the end of line $endLine")
                startLine == endLine -> "Line $startLine"
                else -> "Lines $startLine-$endLine"
            }
        }
    }
}

/** What [ReadFileHandler.pageRequestOf] made of a call's position arguments. */
internal sealed interface ParsedPageRequest {
    /** Every position argument is absent or a whole number. */
    data class Valid(val request: PageRequest) : ParsedPageRequest

    /** An argument is not a whole number; [reason] names it for the model. */
    data class Invalid(val reason: String) : ParsedPageRequest
}
