package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.TerminalToolCall.Companion.TRACE_STAGE
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * A tool acting on one Terminal session [RunShellCommandHandler] reported: the session argument,
 * the host call, tracing and the unknown-session result. Subclasses say what to call and report.
 */
abstract class TerminalSessionHandler(
    pluginContext: PluginContext,
    final override val toolName: String,
) : ToolHandler {

    final override val parametersSchema = ToolSchema.objectOf(
        ARG_SESSION to ToolSchema.string(),
        required = listOf(ARG_SESSION),
    )

    final override val argAliases = mapOf(
        "session_name" to ARG_SESSION,
        "name" to ARG_SESSION,
        "terminal" to ARG_SESSION,
    )

    private val terminalCall = TerminalToolCall(pluginContext, toolName)

    /** Calls the host on [sessionName]; null when the host has no such session of the agent's. */
    internal abstract suspend fun callHost(terminal: IdeTerminalService, sessionName: String): TerminalCommandResult?

    /** The result for [sessionName] when the call leaves its command [running]. */
    internal abstract fun running(sessionName: String, running: TerminalCommandResult.Running): ToolResult

    /** The result for [sessionName] when the call finds its command [completed]. */
    internal abstract fun completed(sessionName: String, completed: TerminalCommandResult.Completed): ToolResult

    final override suspend fun validate(args: Map<String, Any?>): Validation =
        sessionOf(args)?.let { Validation.Accepted(args) } ?: Validation.Rejected(NO_SESSION)

    final override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val sessionName = sessionOf(args) ?: return NO_SESSION

        return terminalCall.run { terminal ->
            val state = callHost(terminal, sessionName)
            AgentTrace.stage(TRACE_STAGE, "$toolName session=$sessionName state=${state?.let { it::class.simpleName }}")
            resultFor(sessionName, state)
        }
    }

    /** The tool result for the [state] the call left session [sessionName] in. */
    // The else is for a result a newer host adds; without it that result throws at runtime.
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    internal fun resultFor(sessionName: String, state: TerminalCommandResult?): ToolResult = when (state) {
        null -> ToolResult.failure(
            "No Terminal session named \"$sessionName\"",
            "It was closed, or the name is wrong. Use the session name a " +
                "${RunShellCommandHandler.TOOL_NAME} result reported."
        )
        is TerminalCommandResult.Running -> running(sessionName, state)
        is TerminalCommandResult.Completed -> completed(sessionName, state)
        else -> ToolResult.failure("Session \"$sessionName\" is in an unknown state: $state")
    }

    companion object {
        const val ARG_SESSION = "session"

        private val NO_SESSION = ToolResult.failure(
            "session is required",
            "Give the Terminal session name a ${RunShellCommandHandler.TOOL_NAME} result reported."
        )

        private fun sessionOf(args: Map<String, Any?>): String? =
            args[ARG_SESSION]?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }
}
