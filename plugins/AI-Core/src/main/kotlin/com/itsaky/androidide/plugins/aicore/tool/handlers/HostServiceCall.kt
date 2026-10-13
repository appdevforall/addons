package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import kotlinx.coroutines.CancellationException

/**
 * What every handler backed by one host service shares around the call: finding the service, and
 * turning a host error into a result the model reads. Stop still cancels the call.
 *
 * @param service the host service the tool calls.
 * @param traceStage the [AgentTrace] stage the tool logs under.
 * @param serviceLabel the service as the model reads it, e.g. "Build" or "Terminal".
 * @param failureMessage the result message for an unexpected host error.
 */
internal class HostServiceCall<S : Any>(
    private val pluginContext: PluginContext,
    private val toolName: String,
    private val service: Class<S>,
    private val traceStage: String,
    private val serviceLabel: String,
    private val failureMessage: (Exception) -> String = { "Error: ${it.javaClass.simpleName}" },
) {
    /** Runs [block] with the service, or fails when the host has none. */
    suspend fun run(block: suspend (S) -> ToolResult): ToolResult = try {
        pluginContext.services.get(service)
            ?.let { host -> block(host) }
            ?: unavailable()
    } catch (ce: CancellationException) {
        // An Exception on the JVM, so the catches below would report Stop as a tool failure.
        throw ce
    } catch (e: SecurityException) {
        // A path outside the project, or the permission missing: the model can fix the first.
        AgentTrace.refusal(traceStage, "$toolName refused", e.toString())
        ToolResult.failure("Refused by the IDE: ${e.message ?: "not allowed"}")
    } catch (e: Exception) {
        AgentTrace.refusal(traceStage, "$toolName failed", e.toString())
        pluginContext.logger.error("$toolName failed", e)
        ToolResult.failure(
            failureMessage(e),
            "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
        )
    }

    private fun unavailable(): ToolResult {
        AgentTrace.refusal(traceStage, "$toolName rejected", "${service.simpleName} not available")
        return ToolResult.failure(
            "$serviceLabel service not available",
            "The IDE ${serviceLabel.lowercase()} service is not available."
        )
    }

    companion object {
        /** The [AgentTrace] stage the shell tools log under. */
        const val SHELL_STAGE = "SHELL"

        /** The [AgentTrace] stage the build tools log under. */
        const val BUILD_STAGE = "BUILD"
    }
}
