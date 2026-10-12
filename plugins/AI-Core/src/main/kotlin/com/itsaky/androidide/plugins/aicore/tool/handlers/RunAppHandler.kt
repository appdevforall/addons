package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.CancellationException
import java.util.concurrent.CompletableFuture

/**
 * Handler for running/building the app.
 */
class RunAppHandler(
    private val pluginContext: PluginContext,
    private val hasLogTools: Boolean = false,
) : ToolHandler {
    override val toolName = "run_app"

    // Build operation requires approval for safety
    override val requiresApproval = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return try {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                AgentTrace.refusal("BUILD", "run_app rejected", "IdeBuildService not available")
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available. This IDE instance may not support build operations."
                )
            }

            if (buildService.isBuildInProgress()) {
                AgentTrace.refusal("BUILD", "run_app rejected", "a build is already in progress")
                return ToolResult.failure(
                    "Build already running",
                    "A build is already in progress. Please wait for it to complete before running again."
                )
            }

            AgentTrace.stage("BUILD", "run_app triggered; waiting for the build callback")
            val startMs = System.currentTimeMillis()
            val outcome = awaitBuild(toolName, startBuild(buildService)) { buildService.cancelBuild() }
            val waitedMs = System.currentTimeMillis() - startMs

            if (outcome == null) {
                AgentTrace.refusal(
                    "BUILD",
                    "run_app timed out waitedMs=$waitedMs",
                    "no callback within ${BUILD_TIMEOUT_MS / 1000}s; the build may still be running"
                )
                return ToolResult.failure(
                    "Build still running",
                    "The build did not finish within 10 minutes and may still be running. " +
                        "Call read_build_output to see how far it got."
                )
            }

            val (success, message) = outcome
            AgentTrace.stage(
                "BUILD",
                "run_app outcome=${if (success) "success" else "failure"} waitedMs=$waitedMs",
                AgentTrace.preview(message)
            )
            if (success) {
                ToolResult.success(
                    message = "Build succeeded",
                    data = if (hasLogTools) {
                        "$message\n\nIf the app crashes or misbehaves, call read_app_logs."
                    } else {
                        message
                    }
                )
            } else {
                ToolResult.failure(
                    "Build failed",
                    "$message\n\nCall read_build_output for the compiler errors."
                )
            }
        } catch (ce: CancellationException) {
            // An Exception on the JVM, so the catch below would report Stop as a build failure.
            throw ce
        } catch (e: Exception) {
            // The trace stream keeps the run readable; the host log keeps the stack trace, which
            // AgentTrace previews only in a debug build.
            AgentTrace.refusal("BUILD", "run_app failed", e.toString())
            pluginContext.logger.error("run_app failed", e)
            ToolResult.failure(
                "Error: ${e.javaClass.simpleName}",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }

    /**
     * Starts the build, completing the future when [BuildAndLaunchCallback] reports back.
     * @return the callback's success flag paired with its message.
     */
    private fun startBuild(buildService: IdeBuildService): CompletableFuture<Pair<Boolean, String>> {
        val future = CompletableFuture<Pair<Boolean, String>>()
        // The host can report more than once, even synchronously; complete keeps only the first.
        buildService.runApp(object : BuildAndLaunchCallback {
            override fun onComplete(success: Boolean, message: String) {
                future.complete(success to message)
            }
        })
        return future
    }
}
