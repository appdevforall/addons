package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.ReadTerminalSessionHandler.Companion.ARG_SESSION
import com.itsaky.androidide.plugins.aicore.tool.handlers.ReadTerminalSessionHandler.Companion.NO_SESSION
import com.itsaky.androidide.plugins.aicore.tool.handlers.ReadTerminalSessionHandler.Companion.SESSION_ALIASES
import com.itsaky.androidide.plugins.aicore.tool.handlers.ReadTerminalSessionHandler.Companion.sessionOf
import com.itsaky.androidide.plugins.aicore.tool.handlers.ReadTerminalSessionHandler.Companion.unknownSession
import com.itsaky.androidide.plugins.aicore.tool.handlers.TerminalToolCall.Companion.TRACE_STAGE
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * Handler for stopping a command [RunShellCommandHandler] left running, such as `ping` or a dev
 * server, with Ctrl-C, as the user would. The host only stops the agent's own sessions.
 */
class StopTerminalSessionHandler(
    pluginContext: PluginContext,
) : ToolHandler {
    override val toolName = TOOL_NAME

    // Asked every time: unasked, Gemini stopped a ping the user had just started.
    override val requiresApproval = true

    // A session grant would let every later stop through unasked, which is what the dialog prevents.
    override val allowsSessionApproval = false

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
            val state = terminal.stopSession(sessionName)
            AgentTrace.stage(TRACE_STAGE, "$toolName session=$sessionName state=${state?.let { it::class.simpleName }}")
            resultFor(sessionName, state)
        }
    }

    companion object {
        const val TOOL_NAME = "stop_terminal_session"

        /**
         * The tool result for the [state] session [sessionName] is in after the stop. Any exit
         * code succeeds, since Ctrl-C itself makes most commands exit non-zero.
         */
        // The else is for a result a newer host adds; without it that result throws at runtime.
        @Suppress("REDUNDANT_ELSE_IN_WHEN")
        internal fun resultFor(sessionName: String, state: TerminalCommandResult?): ToolResult = when (state) {
            null -> unknownSession(sessionName)
            is TerminalCommandResult.Completed -> ToolResult.success(
                message = "The command in \"$sessionName\" has stopped (exit code ${state.exitCode})",
                data = TerminalOutput.tailOf(state.output)
            )
            is TerminalCommandResult.Running -> ToolResult.failure(
                "The command in \"$sessionName\" is still running; it did not stop on Ctrl-C",
                "Tell the user to stop it in the Terminal. Its latest output:\n" +
                    TerminalOutput.tailOf(state.output)
            )
            else -> ToolResult.failure("Session \"$sessionName\" is in an unknown state: $state")
        }
    }
}
