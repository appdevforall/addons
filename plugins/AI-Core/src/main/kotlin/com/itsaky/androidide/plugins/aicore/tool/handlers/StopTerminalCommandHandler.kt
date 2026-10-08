package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * Handler for stopping a command [RunShellCommandHandler] left running, such as `ping` or a dev
 * server, with Ctrl-C, as the user would. The host only stops the agent's own commands.
 */
class StopTerminalCommandHandler(
    pluginContext: PluginContext,
) : TerminalCommandHandler(pluginContext, TOOL_NAME) {

    // Asked every time: unasked, Gemini stopped a ping the user had just started.
    override val requiresApproval = true

    // A session grant would let every later stop through unasked, which is what the dialog prevents.
    override val allowsSessionApproval = false

    override suspend fun callHost(terminal: IdeTerminalService, commandId: String) =
        terminal.stopCommand(commandId)

    override fun running(commandId: String, running: TerminalCommandResult.Running) = ToolResult.failure(
        "Command \"$commandId\" is still running; it did not stop on Ctrl-C",
        "Tell the user to stop it in the Terminal session \"${running.sessionName}\". Its latest output:\n" +
            TerminalOutput.tailOf(running.output)
    )

    // Any exit code succeeds, since Ctrl-C itself makes most commands exit non-zero.
    override fun completed(commandId: String, completed: TerminalCommandResult.Completed) = ToolResult.success(
        message = "Command \"$commandId\" has stopped: it ${TerminalOutput.exitOf(completed.exitCode)}",
        data = TerminalOutput.tailOf(completed.output)
    )

    companion object {
        const val TOOL_NAME = "stop_terminal_command"
    }
}
