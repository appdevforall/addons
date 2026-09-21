package com.itsaky.androidide.plugins.aicore.tool

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult

/**
 * Routes tool calls to appropriate handlers.
 *
 * @param handlers every registered handler; routing is deliberately unbudgeted.
 * @param suggestableNames the names a failed call may be offered back, i.e. the ones the prompt
 *   admitted; suggesting a name the token mask forbids costs the model a whole turn.
 */
class ToolRouter(
    private val handlers: List<ToolHandler>,
    suggestableNames: Set<String>? = null,
) {
    companion object {
        /** Names offered back on a failed lookup; a long list is noise the model reads every turn. */
        private const val MAX_SUGGESTIONS = 3
    }

    private val TAG = "$LOG_PREFIX.ToolRouter"
    private val handlerMap: Map<String, ToolHandler> = handlers.associateBy { it.toolName }
    private val byLowercase: Map<String, List<ToolHandler>> = handlers.groupBy { it.toolName.lowercase() }
    private val suggestable: List<String> =
        handlers.map { it.toolName }.filter { suggestableNames == null || it in suggestableNames }

    /**
     * Finds the handler for a name the model emitted.
     *
     * Exact first, then two forgiving passes, because the alternative is a wasted turn: a model
     * shown `test_add` will sometimes write `add`, and a name that identifies exactly one tool is
     * an answer, not a guess. Anything matching two or more tools resolves to nothing — dispatching
     * a write to the wrong tool is far worse than asking the model to be specific.
     *
     * @param toolName the name as emitted.
     * @return the handler, or null when nothing matches or more than one does.
     */
    fun getHandler(toolName: String): ToolHandler? {
        val name = toolName.trim()
        if (name.isEmpty()) return null

        handlerMap[name]?.let { return it }

        val lower = name.lowercase()
        byLowercase[lower]?.singleOrNull()?.let { return it }

        // The same tool under a longer prefix: `add` -> `test_add`, `create_issue` -> `gh_create_issue`.
        return handlers.filter { it.toolName.lowercase().endsWith("_$lower") }
            .singleOrNull()
            ?.also { Log.i(TAG, "Resolved '$name' to '${it.toolName}'") }
    }

    /**
     * Registered names worth offering back when a call named a tool that resolved to nothing.
     *
     * The model reads this in the failure and retries, so it is cheaper than the turn it saves —
     * but only for a name it was told about and is allowed to emit.
     *
     * @param toolName the name that failed to resolve.
     * @return up to [MAX_SUGGESTIONS] plausible names, nearest kind of match first.
     */
    fun suggestionsFor(toolName: String): List<String> {
        val lower = toolName.trim().lowercase()
        if (lower.isEmpty()) return emptyList()
        val names = suggestable
        return (names.filter { it.lowercase().endsWith("_$lower") } +
            names.filter { it.lowercase().startsWith("${lower}_") } +
            names.filter { it.lowercase().contains(lower) })
            .distinct()
            .take(MAX_SUGGESTIONS)
    }

    /**
     * Dispatch a tool call to its handler.
     */
    suspend fun dispatch(toolName: String, args: Map<String, Any?>): ToolResult {
        val handler = getHandler(toolName)
        if (handler == null) {
            Log.e(TAG, "No handler found for tool: $toolName")
            return ToolResult.failure("Unknown tool: $toolName")
        }
        return dispatch(handler, args)
    }

    /**
     * Dispatch to a handler a caller has already resolved.
     *
     * @param handler the handler to run.
     * @param args the validated call arguments.
     * @return the tool's result, or the failure it raised.
     */
    suspend fun dispatch(handler: ToolHandler, args: Map<String, Any?>): ToolResult {
        val toolName = handler.toolName
        return try {
            Log.d(TAG, "Dispatching $toolName with args: $args")
            handler.execute(args)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // It is an Exception on the JVM, so the catch below would report Stop as a failure.
            Log.i(TAG, "Tool $toolName cancelled")
            // Traced, or the run appears to hang mid-tool with no EXEC-done line.
            com.itsaky.androidide.plugins.aicore.logging.AgentTrace
                .refusal("EXEC", "$toolName cancelled", "run stopped before the tool finished")
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool $toolName", e)
            ToolResult.failure("Error executing $toolName: ${e.message}", e.stackTraceToString())
        }
    }

    /**
     * Get all available tool names.
     */
    fun getAvailableTools(): List<String> {
        return handlerMap.keys.toList()
    }

    /**
     * Get all registered tool handlers.
     */
    fun getAllHandlers(): List<ToolHandler> {
        return handlers
    }
}
