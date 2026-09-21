package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** How long to wait for the build callback before reporting the build still running. */
internal const val BUILD_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * How often to log that the wait is still alive. A build can hold the agent for ten minutes, and
 * without a heartbeat that stretch of logcat is indistinguishable from a hung agent.
 */
internal const val BUILD_PROGRESS_LOG_INTERVAL_MS = 30 * 1000L

/**
 * Handler for running/building the app.
 */
class RunAppHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "run_app"

    // The install is gated on a system prompt only the user can answer, so the model is told the
    // success it gets back is weaker than "the app is running" — otherwise it reports the launch.
    override val description = "Build the app and install it on this device. The user has to " +
        "confirm a system install prompt, so success means the install started, not that the " +
        "app is on screen"

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
            val outcome = withTimeoutOrNull(BUILD_TIMEOUT_MS) {
                coroutineScope {
                    val heartbeat = launch { logProgressUntilCancelled(startMs) }
                    try {
                        awaitBuild(buildService)
                    } finally {
                        heartbeat.cancel()
                    }
                }
            }
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
                    data = message
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
     * Logs one line every [BUILD_PROGRESS_LOG_INTERVAL_MS] for as long as the wait lasts, so a long
     * build reads as a running build rather than as a stalled agent. Cancelled by its caller the
     * moment the callback lands.
     *
     * @param startMs when the wait began, for the elapsed figure.
     */
    private suspend fun logProgressUntilCancelled(startMs: Long) {
        while (true) {
            delay(BUILD_PROGRESS_LOG_INTERVAL_MS)
            val seconds = (System.currentTimeMillis() - startMs) / 1000
            AgentTrace.detail("BUILD", "run_app still waiting elapsed=${seconds}s")
        }
    }

    /**
     * Starts the build and suspends until [BuildAndLaunchCallback] reports back.
     * @return the callback's success flag paired with its message.
     */
    private suspend fun awaitBuild(buildService: IdeBuildService): Pair<Boolean, String> =
        suspendCancellableCoroutine { continuation ->
            // The host reports completion from several paths, and synchronously when it has no
            // run-app provider at all; a second resume on a resumed continuation throws.
            val reported = AtomicBoolean(false)
            val callback = object : BuildAndLaunchCallback {
                override fun onComplete(success: Boolean, message: String) {
                    if (reported.compareAndSet(false, true)) {
                        continuation.resume(success to message)
                    }
                }
            }
            try {
                buildService.runApp(callback)
            } catch (e: Exception) {
                // Reported to the caller, which logs it once with the stack trace.
                if (reported.compareAndSet(false, true)) {
                    continuation.resumeWith(Result.failure(e))
                }
            }
        }
}
