package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * Handler for checking on a command [RunShellCommandHandler] left running, such as a dev server:
 * whether it still runs, its exit code once it stopped, and its latest output. The host only reads
 * the agent's own Terminal sessions, never the user's.
 */
class ReadTerminalSessionHandler(
    pluginContext: PluginContext,
) : TerminalSessionHandler(pluginContext, TOOL_NAME) {

    // Reads output of a command the user already approved; changes nothing.
    override val requiresApproval = false

    override suspend fun callHost(terminal: IdeTerminalService, sessionName: String) =
        terminal.readSession(sessionName)

    override fun running(sessionName: String, running: TerminalCommandResult.Running) = ToolResult.success(
        message = "The command in \"$sessionName\" is still running",
        data = TerminalOutput.tailOf(running.output)
    )

    // Any exit code succeeds, as in RunShellCommandHandler: the model reads what the code means.
    override fun completed(sessionName: String, completed: TerminalCommandResult.Completed) = ToolResult.success(
        message = "The command in \"$sessionName\" exited with code ${completed.exitCode}",
        data = TerminalOutput.tailOf(completed.output)
    )

    companion object {
        const val TOOL_NAME = "read_terminal_session"
    }
}
