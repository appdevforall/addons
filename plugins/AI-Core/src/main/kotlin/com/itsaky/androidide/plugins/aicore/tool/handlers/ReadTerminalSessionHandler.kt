package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.TerminalToolCall.Companion.TRACE_STAGE
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * Handler for checking on a command [RunShellCommandHandler] left running, such as a dev server:
 * whether it still runs, its exit code once it stopped, and its latest output. The host only reads
 * the agent's own Terminal sessions, never the user's.
 */
class ReadTerminalSessionHandler(
    pluginContext: PluginContext,
) : ToolHandler {
    override val toolName = TOOL_NAME

    // Reads output of a command the user already approved; changes nothing.
    override val requiresApproval = false

    override val parametersSchema = ToolSchema.objectOf(
        ARG_SESSION to ToolSchema.string(),
        required = listOf(ARG_SESSION),
    )

    override val argAliases = SESSION_ALIASES

    private val terminalCall = TerminalToolCall(pluginContext, toolName)

    override suspend fun validate(args: Map<String, Any?>): Validation =
        sessionOf(args)?.let { Validation.Accepted(args) } ?: Validation.Rejected(NO_SESSION)

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val sessionName = sessionOf(args) ?: return NO_SESSION

        return terminalCall.run { terminal ->
            val state = terminal.readSession(sessionName)
            AgentTrace.stage(TRACE_STAGE, "$toolName session=$sessionName state=${state?.let { it::class.simpleName }}")
            resultFor(sessionName, state)
        }
    }

    companion object {
        const val TOOL_NAME = "read_terminal_session"
        const val ARG_SESSION = "session"

        /** The names models reach for instead of [ARG_SESSION]; shared with [StopTerminalSessionHandler]. */
        internal val SESSION_ALIASES = mapOf(
            "session_name" to ARG_SESSION,
            "name" to ARG_SESSION,
            "terminal" to ARG_SESSION,
        )

        internal val NO_SESSION = ToolResult.failure(
            "session is required",
            "Give the Terminal session name a ${RunShellCommandHandler.TOOL_NAME} result reported."
        )

        /** The session [args] names, or null when it names none. */
        internal fun sessionOf(args: Map<String, Any?>): String? =
            args[ARG_SESSION]?.toString()?.trim()?.takeIf(String::isNotEmpty)

        /** The failure for a session the host does not know, shared with [StopTerminalSessionHandler]. */
        internal fun unknownSession(sessionName: String): ToolResult = ToolResult.failure(
            "No Terminal session named \"$sessionName\"",
            "It was closed, or the name is wrong. Use the session name a " +
                "${RunShellCommandHandler.TOOL_NAME} result reported."
        )

        /**
         * The tool result for the [state] of session [sessionName]. Exit code 0 succeeds and another
         * code fails, as in [RunShellCommandHandler]; a command still running succeeds.
         */
        // The else is for a result a newer host adds; without it that result throws at runtime.
        @Suppress("REDUNDANT_ELSE_IN_WHEN")
        internal fun resultFor(sessionName: String, state: TerminalCommandResult?): ToolResult = when (state) {
            null -> unknownSession(sessionName)
            is TerminalCommandResult.Running -> ToolResult.success(
                message = "The command in \"$sessionName\" is still running",
                data = TerminalOutput.tailOf(state.output)
            )
            is TerminalCommandResult.Completed -> {
                val message = "The command in \"$sessionName\" exited with code ${state.exitCode}"
                val output = TerminalOutput.tailOf(state.output)
                if (state.exitCode == 0) {
                    ToolResult.success(message = message, data = output)
                } else {
                    ToolResult.failure(message, output)
                }
            }
            else -> ToolResult.failure("Session \"$sessionName\" is in an unknown state: $state")
        }
    }
}
