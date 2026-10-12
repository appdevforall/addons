package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.CancellationException

/**
 * Handler for reading the current build output.
 */
class ReadBuildOutputHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "read_build_output"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return try {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                AgentTrace.refusal(
                    "BUILD",
                    "read_build_output rejected",
                    "IdeBuildService not available"
                )
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available."
                )
            }

            val output = buildService.getBuildOutput()
            if (output.isNullOrBlank()) {
                AgentTrace.detail("BUILD", "read_build_output chars=0 (host returned nothing)")
                ToolResult.success(
                    message = "No build output available",
                    data = "(No recent build output)"
                )
            } else {
                val window = BuildOutputWindowCalculator.windowFor(output)
                AgentTrace.detail(
                    "BUILD",
                    "read_build_output chars=${window.text.length} " +
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
        } catch (ce: CancellationException) {
            // An Exception on the JVM, so the catch below would report Stop as a read failure.
            throw ce
        } catch (e: Exception) {
            // The trace stream keeps the run readable; the host log keeps the stack trace, which
            // AgentTrace previews only in a debug build.
            AgentTrace.refusal("BUILD", "read_build_output failed", e.toString())
            pluginContext.logger.error("read_build_output failed", e)
            ToolResult.failure(
                "Error reading build output",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
