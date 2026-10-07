package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.IdeTerminalService
import kotlinx.coroutines.CancellationException

/**
 * What the shell tools share around a call to the host's [IdeTerminalService]: finding it, and
 * turning a host error into a result the model reads. Stop still cancels the call.
 */
internal class TerminalToolCall(
    private val pluginContext: PluginContext,
    private val toolName: String,
) {
    /** Runs [block] with the terminal service, or fails when the host has none. */
    suspend fun run(block: suspend (IdeTerminalService) -> ToolResult): ToolResult = try {
        pluginContext.services.get(IdeTerminalService::class.java)
            ?.let { terminal -> block(terminal) }
            ?: unavailable()
    } catch (ce: CancellationException) {
        // An Exception on the JVM, so the catches below would report Stop as a command failure.
        throw ce
    } catch (e: SecurityException) {
        // A working directory outside the project, or the permission missing: the model can fix the first.
        AgentTrace.refusal(TRACE_STAGE, "$toolName refused", e.toString())
        ToolResult.failure("Command refused: ${e.message ?: "not allowed"}")
    } catch (e: Exception) {
        AgentTrace.refusal(TRACE_STAGE, "$toolName failed", e.toString())
        pluginContext.logger.error("$toolName failed", e)
        ToolResult.failure(
            "Error: ${e.javaClass.simpleName}",
            "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
        )
    }

    private fun unavailable(): ToolResult {
        AgentTrace.refusal(TRACE_STAGE, "$toolName rejected", "IdeTerminalService not available")
        return ToolResult.failure(
            "Terminal service not available",
            "The IDE terminal service is not available."
        )
    }

    companion object {
        /** The [AgentTrace] stage both shell tools log under. */
        const val TRACE_STAGE = "SHELL"
    }
}
