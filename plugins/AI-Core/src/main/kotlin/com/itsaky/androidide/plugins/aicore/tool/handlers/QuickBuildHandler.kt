package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.HostServiceCall.Companion.BUILD_STAGE
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildState

/**
 * Handler for starting Quick Build, which updates the running app in place. The host runs it as a
 * tap on the toolbar button, so the toolbar, Build Output and the replace-app prompt behave as usual.
 */
class QuickBuildHandler(
    private val pluginContext: PluginContext,
) : ToolHandler {
    override val toolName = TOOL_NAME

    private val buildCall = HostServiceCall(
        pluginContext, toolName, IdeBuildService::class.java, BUILD_STAGE, "Build",
    )

    // Starts a real build and can relaunch the app, like run_app and run_gradle_task.
    override val requiresApproval = true

    override suspend fun validate(args: Map<String, Any?>): Validation {
        // No dialog for a Quick Build that cannot start, or that the host would refuse as busy.
        val buildService = pluginContext.services.get(IdeBuildService::class.java)
            ?: return Validation.Rejected(buildCall.unavailable())
        return buildService.cannotStart()?.let { Validation.Rejected(it) } ?: Validation.Accepted(args)
    }

    override suspend fun execute(args: Map<String, Any?>): ToolResult = buildCall.run { buildService ->
        // Re-checked: a sync or build may have started while the user read the approval dialog.
        buildService.cannotStart()?.let { return@run it }

        AgentTrace.stage(BUILD_STAGE, "$toolName started")
        val startMs = System.currentTimeMillis()
        val outcome = awaitBuild(toolName, buildService.startQuickBuild()) { buildService.cancelQuickBuild() }
        val waitedMs = System.currentTimeMillis() - startMs

        if (outcome == null) {
            AgentTrace.refusal(
                BUILD_STAGE,
                "$toolName timed out waitedMs=$waitedMs",
                "no result within ${BUILD_TIMEOUT_MS / 1000}s; the build may still be running"
            )
            return@run ToolResult.failure(
                "Quick Build still running",
                "Quick Build did not finish within 10 minutes and may still be running. " +
                    "Call read_build_output to see how far it got."
            )
        }

        AgentTrace.stage(BUILD_STAGE, "$toolName outcome=$outcome waitedMs=$waitedMs")
        QuickBuildReport.resultFor(outcome)
    }

    /** Why Quick Build cannot start now, or null when it can. */
    private fun IdeBuildService.cannotStart(): ToolResult? {
        (getQuickBuildAvailability() as? QuickBuildAvailability.Unavailable)?.let { unavailable ->
            AgentTrace.refusal(BUILD_STAGE, "$toolName rejected", unavailable.message)
            return QuickBuildReport.unavailable(unavailable)
        }
        if (isBuildInProgress() || getQuickBuildStatus() == QuickBuildState.BUILDING) {
            AgentTrace.refusal(BUILD_STAGE, "$toolName rejected", "a build is already in progress")
            return QuickBuildReport.BUILD_IN_PROGRESS
        }
        return null
    }

    companion object {
        const val TOOL_NAME = "quick_build"
    }
}
