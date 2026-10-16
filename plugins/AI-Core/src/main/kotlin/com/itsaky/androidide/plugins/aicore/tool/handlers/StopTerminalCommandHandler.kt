package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview
import com.itsaky.androidide.plugins.aicore.tool.ToolOutputBudget
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * Handler for stopping a command [RunShellCommandHandler] left running, such as `ping` or a dev
 * server, with Ctrl-C, as the user would. The host only stops the agent's own commands.
 */
class StopTerminalCommandHandler(
    pluginContext: PluginContext,
    private val startedCommands: StartedCommands = StartedCommands(),
    budget: ToolOutputBudget = ToolOutputBudget.DEFAULT,
) : TerminalCommandHandler(pluginContext, TOOL_NAME, budget) {

    // Asked every time: unasked, Gemini stopped a ping the user had just started.
    override val requiresApproval = true

    // A session grant would let every later stop through unasked, which is what the dialog prevents.
    override val allowsSessionApproval = false

    // An id alone cannot tell a dev server from a ping, so the dialog shows the command it stops.
    override val approvalPreview = ApprovalPreview.STOP_COMMAND

    // Rebuilt from what this chat started, never the model's own words, which the dialog would show.
    override fun approvalArgs(commandId: String, args: Map<String, Any?>): Map<String, Any?> = buildMap {
        put(ARG_COMMAND_ID, commandId)
        startedCommands.find(commandId)?.let { started ->
            put(DETAIL_COMMAND, started.command)
            put(DETAIL_SESSION, started.sessionName)
        }
    }

    override suspend fun callHost(terminal: IdeTerminalService, commandId: String) =
        terminal.stopCommand(commandId)

    override fun running(commandId: String, running: TerminalCommandResult.Running): ToolResult {
        val advice = "Tell the user to stop it in the Terminal session \"${running.sessionName}\". Its latest output:\n"
        return ToolResult.failure(
            "Command \"$commandId\" is still running; it did not stop on Ctrl-C",
            advice + TerminalOutput.tailOf(running.output, budget.dataChars() - advice.length)
        )
    }

    // Any exit code succeeds, since Ctrl-C makes most exit non-zero; one that exited before is left alone.
    override fun completed(commandId: String, completed: TerminalCommandResult.Completed) = ToolResult.success(
        message = "Command \"$commandId\" is no longer running: it ${TerminalOutput.exitOf(completed.exitCode)}",
        data = TerminalOutput.tailOf(completed.output, budget.dataChars())
    )

    companion object {
        const val TOOL_NAME = "stop_terminal_command"

        /** The stopped command's text, added for the approval dialog when this chat started it. */
        const val DETAIL_COMMAND = "command"

        /** The Terminal session it runs in, added alongside [DETAIL_COMMAND]. */
        const val DETAIL_SESSION = "session"
    }
}
