package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.HostServiceCall.Companion.BUILD_STAGE
import com.itsaky.androidide.plugins.services.IdeBuildService

/**
 * Handler for reading the current build output.
 */
class ReadBuildOutputHandler(
    pluginContext: PluginContext
) : ToolHandler {
    override val toolName = TOOL_NAME
    override val requiresApproval = false

    private val buildCall = HostServiceCall(
        pluginContext, toolName, IdeBuildService::class.java, BUILD_STAGE, "Build",
        failureMessage = { "Error reading build output" },
    )

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return buildCall.run { buildService ->
            val output = buildService.getBuildOutput()
            if (output.isNullOrBlank()) {
                AgentTrace.detail(BUILD_STAGE, "$toolName chars=0 (host returned nothing)")
                ToolResult.success(
                    message = "No build output available",
                    data = "(No recent build output)"
                )
            } else {
                val window = BuildOutputWindowCalculator.windowFor(output)
                AgentTrace.detail(
                    BUILD_STAGE,
                    "$toolName chars=${window.text.length} " +
                        "anchoredOnError=${window.anchoredOnError} hostChars=${output.length}"
                )
                ToolResult.success(
                    message = if (window.anchoredOnError) {
                        "Build output from the first error (${window.text.length} characters)"
                    } else {
                        "Build output (last ${window.text.length} characters)"
                    },
                    data = window.text
                )
            }
        }
    }

    companion object {
        const val TOOL_NAME = "read_build_output"
    }
}
