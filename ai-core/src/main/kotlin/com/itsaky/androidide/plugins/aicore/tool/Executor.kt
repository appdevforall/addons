package com.itsaky.androidide.plugins.aicore.tool

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolExecutionTracker
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Executes tool calls, handling parallel vs sequential execution.
 */
class Executor(
    private val toolRouter: ToolRouter,
    private val approvalManager: ToolApprovalManager,
    private val toolExecutionTracker: ToolExecutionTracker? = null
) {
    private val TAG = "$LOG_PREFIX.Executor"

    companion object {
        // Tools that only read state and can be executed in parallel safely
        private val PARALLEL_SAFE_TOOLS = setOf(
            "read_file",
            "list_files",
            "search_project",
        )

        /**
         * Arguments a tool cannot run without.
         * @param toolName the tool being dispatched.
         * @return the required argument names, empty when the tool has none.
         */
        fun requiredArgsForTool(toolName: String): List<String> {
            return when (toolName) {
                "read_file" -> listOf("file_path")
                "open_file" -> listOf("file_path")
                "list_files" -> emptyList()  // directory is optional, defaults to "."
                "search_project" -> listOf("query")
                "create_file" -> listOf("file_path", "content")
                "update_file" -> listOf("file_path", "content")
                // new_string omitted: empty is a legal edit (deletion), so EditFileHandler checks it.
                "edit_file" -> listOf("file_path", "old_string")
                else -> emptyList()
            }
        }

        /**
         * Required args holding verbatim file text, where whitespace is content: checked for
         * emptiness rather than blankness, since `old_string = "    "` is a legal edit and
         * rejecting it would contradict `EditFileHandler.validate`.
         */
        private val VERBATIM_TEXT_ARGS = setOf("old_string")

        /**
         * Whether [value] fails the required-argument check for [key].
         * @param key the required argument's name.
         * @param value the supplied value, or null when absent.
         * @return true when the argument must be reported missing.
         */
        private fun isMissing(key: String, value: Any?): Boolean {
            val text = value?.toString()
            return if (key in VERBATIM_TEXT_ARGS) text.isNullOrEmpty() else text?.trim().isNullOrEmpty()
        }
    }

    /**
     * Executes a batch of tool calls, treating its order as a dependency graph: consecutive
     * read-only calls ([PARALLEL_SAFE_TOOLS]) run concurrently, every write runs alone between such
     * runs. Hoisting all reads broke `create_file` + `read_file`; mixing broke `search` + `edit`.
     *
     * Handlers are resolved once, up front, and parallel-safety is decided from the registered name
     * rather than the emitted one: a model writing `Read_File` or `  read_file ` reaches the same
     * read-only handler, and a batch of four reads must not go sequential over spelling.
     *
     * @param toolCalls the calls to run, in the order the model emitted them.
     * @param onCallStarted invoked as each call begins, for the UI's activity line; a parallel run
     *   invokes it once per call in that run, in no guaranteed order.
     * @return one result per call, positionally aligned with [toolCalls].
     */
    suspend fun execute(
        toolCalls: List<ToolCall>,
        onCallStarted: suspend (ToolCall) -> Unit = {},
    ): List<ToolResult> = coroutineScope {
        Log.i(TAG, "Executing ${toolCalls.size} tool call(s)...")

        val results = arrayOfNulls<ToolResult>(toolCalls.size)
        val handlers = toolCalls.map { call ->
            call.name.takeIf { it.isNotBlank() }?.let { toolRouter.getHandler(it) }
        }
        val parallelSafe = handlers.map { it != null && it.toolName in PARALLEL_SAFE_TOOLS }

        var index = 0
        while (index < toolCalls.size) {
            if (!parallelSafe[index]) {
                onCallStarted(toolCalls[index])
                results[index] = executeCall(toolCalls[index], handlers[index], "Sequential")
                index++
                continue
            }
            // Extend over every read-only call that follows, stopping at the first write.
            var end = index
            while (end < toolCalls.size && parallelSafe[end]) end++
            (index until end).map { i ->
                async {
                    onCallStarted(toolCalls[i])
                    results[i] = executeCall(toolCalls[i], handlers[i], "Parallel")
                }
            }.awaitAll()
            index = end
        }

        results.requireNoNulls().toList()
    }

    /**
     * Runs one call end to end: alias remapping, required-argument and containment checks,
     * pre-approval validation, the approval gate, then dispatch.
     * @param call the tool call to run.
     * @param handler the handler [execute] resolved for it, or null when nothing matched.
     * @param executionMode "Parallel"/"Sequential", for logging.
     * @return the tool's result, or the failure that stopped it short of running.
     */
    private suspend fun executeCall(
        call: ToolCall,
        handler: ToolHandler?,
        executionMode: String,
    ): ToolResult {
        val args = call.args

        if (call.name.isBlank()) {
            Log.e(TAG, "($executionMode): Encountered unnamed function call.")
            return ToolResult.failure("Unnamed function call")
        }

        if (handler == null) {
            Log.e(TAG, "($executionMode): Unknown function requested: ${call.name}")
            val suggestions = toolRouter.suggestionsFor(call.name)
            val message = if (suggestions.isEmpty()) {
                "Unknown function '${call.name}'"
            } else {
                "Unknown function '${call.name}'. Did you mean: ${suggestions.joinToString(", ")}?"
            }
            AgentTrace.refusal("ROUTE", "${call.name} unresolved", message)
            return ToolResult.failure(message)
        }

        // The registered name from here on: the model may have named the tool loosely, and every
        // check, approval key and log line below has to mean the tool that will actually run.
        val toolName = handler.toolName

        // Shared with the progress guard, so it derives a call's paths from the keys that will run.
        val normalizedArgs = normalizeToolArgs(handler, args)
        (normalizedArgs.keys - args.keys).forEach { canonical ->
            Log.d(TAG, "($executionMode): Filled '$canonical' from an alias for $toolName tool")
            AgentTrace.detail("ARGS", "$toolName remapped →$canonical")
        }

        // Check required arguments
        val missingArgs = requiredArgsForTool(toolName).filter { key ->
            isMissing(key, normalizedArgs[key])
        }
        if (missingArgs.isNotEmpty()) {
            val message = "Missing required argument(s): ${missingArgs.joinToString(", ")}"
            Log.i(TAG, "($executionMode): Tool '$toolName' missing args: $missingArgs")
            AgentTrace.refusal("ARGS", "$toolName mode=$executionMode", message)
            return ToolResult.failure(message)
        }

        pathContainmentFailure(toolName, handler, normalizedArgs, executionMode)?.let {
            AgentTrace.refusal("GUARD", "$toolName containment", it.message)
            return it
        }

        // Rejects a doomed call before it costs a dialog; guarded since validate() can throw.
        val validation = try {
            handler.validate(normalizedArgs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "($executionMode): Pre-approval validation of '$toolName' threw", e)
            AgentTrace.refusal(
                "PRECHECK",
                "$toolName validation threw",
                e.message ?: e.javaClass.simpleName,
            )
            return ToolResult.failure("Error validating $toolName: ${e.message}", e.stackTraceToString())
        }

        val validatedArgs = when (validation) {
            is Validation.Rejected -> {
                val failure = validation.result
                Log.i(TAG, "($executionMode): Tool '$toolName' failed pre-approval validation: ${failure.message}")
                AgentTrace.refusal("PRECHECK", "$toolName rejected before approval", failure.message)
                return failure
            }
            is Validation.Accepted -> {
                // Model-supplied keys only: handler bookkeeping is not an "args corrected" event.
                if (normalizedArgs.any { (key, value) -> validation.args[key] != value }) {
                    AgentTrace.stage("PRECHECK", "$toolName args corrected", AgentTrace.previewArgs(validation.args))
                }
                validation.args
            }
        }

        // Check approval
        val approvalStart = System.currentTimeMillis()
        val approvalResponse = approvalManager.ensureApproved(toolName, handler, validatedArgs)
        val approvalMs = System.currentTimeMillis() - approvalStart
        if (!approvalResponse.approved) {
            val message = approvalResponse.denialMessage ?: "Action denied by user."
            Log.i(TAG, "($executionMode): Tool '$toolName' denied. $message")
            AgentTrace.stage("APPROVAL", "$toolName approved=false waitedMs=$approvalMs", AgentTrace.preview(message))
            return ToolResult.failure(message)
        }
        AgentTrace.stage("APPROVAL", "$toolName approved=true waitedMs=$approvalMs")

        Log.d(TAG, "($executionMode): Dispatching '$toolName' with args: $validatedArgs")
        AgentTrace.stage("EXEC", "$toolName mode=$executionMode", AgentTrace.previewArgs(validatedArgs))

        // Before tool execution
        val toolStartTime = System.currentTimeMillis()

        // The handler `execute` already resolved, so the name is not looked up a second time.
        val result = toolRouter.dispatch(handler, validatedArgs)

        // After tool execution
        val toolDuration = System.currentTimeMillis() - toolStartTime
        toolExecutionTracker?.logToolCall(toolName, toolDuration)

        Log.i(TAG, "($executionMode): Result: ${result.toResultMap()}")
        // A failure's reason goes in the head, not the preview: it is the handler's own short
        // message rather than the user's content, and a release build drops previews — which is
        // what left a run_app that failed in 0ms indistinguishable from one that ran and failed.
        if (result.success) {
            AgentTrace.stage(
                "EXEC",
                "$toolName done success=true tookMs=$toolDuration",
                AgentTrace.preview(result.message),
            )
        } else {
            AgentTrace.refusal(
                "EXEC",
                "$toolName done success=false tookMs=$toolDuration " +
                    "reason=${AgentTrace.preview(result.message, 80)}",
                result.error_details.orEmpty(),
            )
        }
        return result
    }

    /**
     * Confines model-supplied path args to the project root; handlers that resolve
     * paths themselves opt out via [ToolHandler.resolvesPathsInternally].
     * @param toolName the tool being dispatched (for logging).
     * @param handler the tool's handler, source of [ToolHandler.pathArgs].
     * @param args the normalized call arguments.
     * @param executionMode "Parallel"/"Sequential", for logging.
     * @return a failure [ToolResult] for the first escaping arg, or null if all are safe.
     */
    private fun pathContainmentFailure(
        toolName: String,
        handler: ToolHandler,
        args: Map<String, Any?>,
        executionMode: String,
    ): ToolResult? {
        if (handler.resolvesPathsInternally) return null
        for (key in handler.pathArgs) {
            val raw = args[key]?.toString()?.trim()
            if (raw.isNullOrEmpty()) continue
            if (PathGuard.resolveWithin(raw) != null) continue
            Log.w(TAG, "($executionMode): '$toolName' arg '$key' escapes project root: $raw")
            return ToolResult.failure("Path '$raw' is outside the project directory")
        }
        return null
    }
}

/**
 * Represents a tool call to be executed.
 */
data class ToolCall(
    val name: String,
    val args: Map<String, Any?>
)
