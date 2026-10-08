package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.HostServiceCall.Companion.SHELL_STAGE
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * A tool acting on one command [RunShellCommandHandler] left running, by the id it reported: the
 * argument, the host call, tracing and the unknown-id result. Subclasses say what to call and report.
 * Keyed by command, not session: the host reuses an idle session for the next command.
 */
abstract class TerminalCommandHandler(
    pluginContext: PluginContext,
    final override val toolName: String,
) : ToolHandler {

    final override val parametersSchema = ToolSchema.objectOf(
        ARG_COMMAND_ID to ToolSchema.string(),
        required = listOf(ARG_COMMAND_ID),
    )

    final override val argAliases = mapOf(
        "commandId" to ARG_COMMAND_ID,
        "id" to ARG_COMMAND_ID,
    )

    private val terminalCall = HostServiceCall(
        pluginContext, toolName, IdeTerminalService::class.java, SHELL_STAGE, "Terminal",
    )

    /** Calls the host on [commandId]; null when the host has no such command of the agent's. */
    internal abstract suspend fun callHost(terminal: IdeTerminalService, commandId: String): TerminalCommandResult?

    /** The result for [commandId] when the call leaves it [running]. */
    internal abstract fun running(commandId: String, running: TerminalCommandResult.Running): ToolResult

    /** The result for [commandId] when the call finds it [completed]. */
    internal abstract fun completed(commandId: String, completed: TerminalCommandResult.Completed): ToolResult

    final override suspend fun validate(args: Map<String, Any?>): Validation =
        commandIdOf(args)?.let { Validation.Accepted(args) } ?: Validation.Rejected(NO_COMMAND_ID)

    final override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val commandId = commandIdOf(args) ?: return NO_COMMAND_ID

        return terminalCall.run { terminal ->
            val state = callHost(terminal, commandId)
            AgentTrace.stage(SHELL_STAGE, "$toolName command=$commandId state=${state?.let { it::class.simpleName }}")
            resultFor(commandId, state)
        }
    }

    /** The tool result for the [state] the call left command [commandId] in. */
    // The else is for a result a newer host adds; without it that result throws at runtime.
    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    internal fun resultFor(commandId: String, state: TerminalCommandResult?): ToolResult = when (state) {
        null -> ToolResult.failure(
            "No command with id \"$commandId\"",
            "The id is wrong, or the command ended long enough ago that the Terminal no longer keeps " +
                "it. Use the command id a ${RunShellCommandHandler.TOOL_NAME} result reported."
        )
        is TerminalCommandResult.Running -> running(commandId, state)
        is TerminalCommandResult.Completed -> completed(commandId, state)
        else -> ToolResult.failure("Command \"$commandId\" is in an unknown state: $state")
    }

    companion object {
        const val ARG_COMMAND_ID = "command_id"

        private val NO_COMMAND_ID = ToolResult.failure(
            "command_id is required",
            "Give the command id a ${RunShellCommandHandler.TOOL_NAME} result reported."
        )

        private fun commandIdOf(args: Map<String, Any?>): String? =
            args[ARG_COMMAND_ID]?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }
}
