package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import kotlinx.coroutines.CancellationException

/**
 * Handler for starting Quick Build, which updates the running app in place. The host runs it as a
 * tap on the toolbar button, so the toolbar, Build Output and the replace-app prompt behave as usual.
 */
class QuickBuildHandler(
    private val pluginContext: PluginContext,
) : ToolHandler {
    override val toolName = "quick_build"

    // Starts a real build and can relaunch the app, like run_app and run_gradle_task.
    override val requiresApproval = true

    override suspend fun validate(args: Map<String, Any?>): Validation {
        // No dialog for a Quick Build that cannot start.
        val buildService = buildService() ?: return Validation.Rejected(noBuildService())
        val unavailable = buildService.unavailability() ?: return Validation.Accepted(args)
        AgentTrace.refusal("BUILD", "$toolName rejected", unavailable.message)
        return Validation.Rejected(QuickBuildReport.unavailable(unavailable))
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return try {
            val buildService = buildService() ?: return noBuildService()
            // Re-checked: a sync may have started while the user read the approval dialog.
            buildService.unavailability()?.let { unavailable ->
                AgentTrace.refusal("BUILD", "$toolName rejected", unavailable.message)
                return QuickBuildReport.unavailable(unavailable)
            }

            AgentTrace.stage("BUILD", "$toolName started")
            val startMs = System.currentTimeMillis()
            val outcome = awaitBuild(toolName, buildService.startQuickBuild()) { buildService.cancelQuickBuild() }
            val waitedMs = System.currentTimeMillis() - startMs

            if (outcome == null) {
                AgentTrace.refusal(
                    "BUILD",
                    "$toolName timed out waitedMs=$waitedMs",
                    "no result within ${BUILD_TIMEOUT_MS / 1000}s; the build may still be running"
                )
                return ToolResult.failure(
                    "Quick Build still running",
                    "Quick Build did not finish within 10 minutes and may still be running. " +
                        "Call read_build_output to see how far it got."
                )
            }

            AgentTrace.stage("BUILD", "$toolName outcome=$outcome waitedMs=$waitedMs")
            QuickBuildReport.resultFor(outcome)
        } catch (ce: CancellationException) {
            // An Exception on the JVM, so the catch below would report Stop as a build failure.
            throw ce
        } catch (e: Exception) {
            AgentTrace.refusal("BUILD", "$toolName failed", e.toString())
            pluginContext.logger.error("$toolName failed", e)
            ToolResult.failure(
                "Error: ${e.javaClass.simpleName}",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }

    private fun buildService(): IdeBuildService? = pluginContext.services.get(IdeBuildService::class.java)

    private fun IdeBuildService.unavailability(): QuickBuildAvailability.Unavailable? =
        getQuickBuildAvailability() as? QuickBuildAvailability.Unavailable

    private fun noBuildService(): ToolResult {
        AgentTrace.refusal("BUILD", "$toolName rejected", "IdeBuildService not available")
        return ToolResult.failure("Build service not available", "The IDE build service is not available.")
    }
}
