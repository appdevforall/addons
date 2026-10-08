package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.HostServiceCall.Companion.BUILD_STAGE
import com.itsaky.androidide.plugins.services.GradleTaskResult
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Handler for running any Gradle task (`test`, `lint`, `clean`, `:app:testDebugUnitTest`) with
 * Gradle arguments, on the IDE's own tooling server. Its output reaches the Build Output pane, and
 * the slice worth reading comes back with the result, so a test run reports its failures at once.
 */
class RunGradleTaskHandler(
    pluginContext: PluginContext,
) : ToolHandler {
    override val toolName = "run_gradle_task"

    private val buildCall = HostServiceCall(
        pluginContext, toolName, IdeBuildService::class.java, BUILD_STAGE, "Build",
    )

    // Starts a real build, like run_app and gradle_sync, and the arguments are the model's choice.
    override val requiresApproval = true

    override val parametersSchema = ToolSchema.objectOf(
        "tasks" to ToolSchema.string(),
        "arguments" to ToolSchema.string(),
        required = listOf("tasks"),
    )

    override val argAliases = mapOf(
        "task" to "tasks",
        "task_name" to "tasks",
        "args" to "arguments",
    )

    override suspend fun validate(args: Map<String, Any?>): Validation {
        val invocation = invocationOf(args)
        return if (invocation.tasks.isEmpty()) {
            Validation.Rejected(NO_TASKS)
        } else {
            Validation.Accepted(args)
        }
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val invocation = invocationOf(args)
        if (invocation.tasks.isEmpty()) return NO_TASKS
        val label = invocation.tasks.joinToString(" ")

        return buildCall.run { buildService ->
            AgentTrace.stage(BUILD_STAGE, "$toolName tasks=$label args=${invocation.arguments}")
            val startMs = System.currentTimeMillis()
            val future = buildService.executeTasks(invocation.tasks, invocation.arguments)
            val outcome = try {
                withTimeoutOrNull(BUILD_TIMEOUT_MS) {
                    coroutineScope {
                        val heartbeat = launch { logProgressUntilCancelled(startMs) }
                        try {
                            awaitResult(future)
                        } finally {
                            heartbeat.cancel()
                        }
                    }
                }
            } catch (ce: CancellationException) {
                // Stop pressed while the build runs: the agent started it, so the agent ends it.
                if (!future.isDone) {
                    AgentTrace.stage(BUILD_STAGE, "$toolName stopped; cancelling the build")
                    buildService.cancelBuild()
                }
                throw ce
            }
            val waitedMs = System.currentTimeMillis() - startMs

            if (outcome == null) {
                AgentTrace.refusal(
                    BUILD_STAGE,
                    "$toolName timed out waitedMs=$waitedMs",
                    "no result within ${BUILD_TIMEOUT_MS / 1000}s; the build may still be running"
                )
                return@run ToolResult.failure(
                    "Gradle task still running",
                    "$label did not finish within 10 minutes and may still be running. " +
                        "Call read_build_output to see how far it got."
                )
            }

            AgentTrace.stage(BUILD_STAGE, "$toolName outcome=$outcome waitedMs=$waitedMs")
            resultFor(label, outcome, buildService.getBuildOutput())
        }
    }

    /** Logs one line every [BUILD_PROGRESS_LOG_INTERVAL_MS] while the wait lasts. */
    private suspend fun logProgressUntilCancelled(startMs: Long) {
        while (true) {
            delay(BUILD_PROGRESS_LOG_INTERVAL_MS)
            val seconds = (System.currentTimeMillis() - startMs) / 1000
            AgentTrace.detail(BUILD_STAGE, "$toolName still waiting elapsed=${seconds}s")
        }
    }

    /**
     * Suspends until [future] completes. Unlike `CompletableFuture.await`, a cancelled wait leaves
     * the future alone, so the caller can still tell whether the build was running when it stopped.
     */
    private suspend fun awaitResult(future: CompletableFuture<GradleTaskResult>): GradleTaskResult =
        suspendCancellableCoroutine { continuation ->
            future.whenComplete { result, error ->
                if (!continuation.isActive) return@whenComplete
                // A dependent stage wraps the host's exception; the model should read the original.
                val cause = (error as? CompletionException)?.cause ?: error
                if (cause != null) continuation.resumeWithException(cause) else continuation.resume(result)
            }
        }

    companion object {
        private val NO_TASKS = ToolResult.failure(
            "tasks is required",
            "Name the Gradle task to run, e.g. tasks=\"test\" or tasks=\":app:testDebugUnitTest\"."
        )

        /** The call's `tasks` and `arguments` as the host takes them; see [GradleCommandLine]. */
        internal fun invocationOf(args: Map<String, Any?>): GradleInvocation =
            GradleCommandLine.parse(args["tasks"], args["arguments"])

        /**
         * The tool result for [outcome], carrying the build output window so a failed test or
         * compile error reaches the model without a second call.
         */
        // The else is for a result a newer host adds; without it that result throws at runtime.
        @Suppress("REDUNDANT_ELSE_IN_WHEN")
        internal fun resultFor(label: String, outcome: GradleTaskResult, output: String?): ToolResult {
            val log = output?.takeIf { it.isNotBlank() }?.let { ReadBuildOutputHandler.windowFor(it).text }
            return when (outcome) {
                GradleTaskResult.Success -> if (nothingRan(output)) {
                    ToolResult.success(
                        message = "$label succeeded; every task was up to date",
                        data = "$UP_TO_DATE_NOTE\n\n${log.orEmpty()}"
                    )
                } else {
                    ToolResult.success(message = "$label succeeded", data = log ?: "(No build output)")
                }
                is GradleTaskResult.Failed -> ToolResult.failure(
                    "$label failed: ${outcome.reason}",
                    log ?: "(No build output)"
                )
                is GradleTaskResult.Refused -> ToolResult.failure(
                    "$label did not start: ${outcome.reason}",
                    "No build ran. If another build is in progress, wait for it to finish and try again."
                )
                GradleTaskResult.Cancelled -> ToolResult.failure(
                    "$label was cancelled",
                    log ?: "(No build output)"
                )
                else -> ToolResult.failure("$label ended with an unknown result: $outcome")
            }
        }

        private const val UP_TO_DATE_NOTE =
            "Gradle skipped every task because nothing changed since it last ran, so no test or " +
                "check ran now. To run it anyway, call run_gradle_task again with arguments=\"--rerun\"."

        // "> Task :app:test UP-TO-DATE"; a task that ran has no outcome after its path.
        private val TASK_LINE = Regex("""> Task (\S+)(?: ([A-Z-]+))?\s*$""")

        // A colour console wraps the task line in escapes, which would hide it from TASK_LINE.
        private val ANSI_ESCAPE = Regex("""\u001B\[[0-9;]*[A-Za-z]""")

        /**
         * Whether [output] lists tasks and Gradle skipped every one of them (UP-TO-DATE, FROM-CACHE,
         * NO-SOURCE, SKIPPED). Without task lines nothing is claimed, so the plain result stands.
         */
        internal fun nothingRan(output: String?): Boolean {
            val outcomes = output.orEmpty().lineSequence()
                .mapNotNull { TASK_LINE.find(it.replace(ANSI_ESCAPE, "")) }
                .map { it.groupValues[2] }
                .toList()
            return outcomes.isNotEmpty() && outcomes.none { it.isEmpty() }
        }
    }
}
