package com.itsaky.androidide.plugins.aicore.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.web.WebAccess
import kotlinx.coroutines.CancellationException

private const val TAG = "$LOG_PREFIX.WebSearchHandler"

/**
 * Searches the web through the active backend. Needs no approval: the query goes to the provider
 * already receiving the whole conversation.
 *
 * @param search runs one search; see [com.itsaky.androidide.plugins.aicore.tool.web.BackendWebSearch].
 */
class WebSearchHandler(
    private val search: suspend (String) -> ToolResult,
) : ToolHandler {

    override val toolName = WebAccess.WEB_SEARCH_TOOL
    override val parametersSchema = ToolSchema.objectOf(
        "query" to ToolSchema.string(),
        required = listOf("query"),
    )
    override val argAliases = mapOf("q" to "query", "search" to "query", "text" to "query")

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val query = args["query"]?.toString()?.trim()
        if (query.isNullOrEmpty()) return ToolResult.failure("query is required")
        return try {
            search(query)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "search failed", e)
            ToolResult.failure("Web search failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
