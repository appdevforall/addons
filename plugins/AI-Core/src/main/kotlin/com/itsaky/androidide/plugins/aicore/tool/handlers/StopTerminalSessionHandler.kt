package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult

/**
 * Handler for stopping a command [RunShellCommandHandler] left running, such as `ping` or a dev
 * server, with Ctrl-C, as the user would. The host only stops the agent's own sessions.
 */
class StopTerminalSessionHandler(
    pluginContext: PluginContext,
) : TerminalSessionHandler(pluginContext, TOOL_NAME) {

    // Asked every time: unasked, Gemini stopped a ping the user had just started.
    override val requiresApproval = true

    // A session grant would let every later stop through unasked, which is what the dialog prevents.
    override val allowsSessionApproval = false

    override suspend fun callHost(terminal: IdeTerminalService, sessionName: String) =
        terminal.stopSession(sessionName)

    override fun running(sessionName: String, running: TerminalCommandResult.Running) = ToolResult.failure(
        "The command in \"$sessionName\" is still running; it did not stop on Ctrl-C",
        "Tell the user to stop it in the Terminal. Its latest output:\n" + TerminalOutput.tailOf(running.output)
    )

    // Any exit code succeeds, since Ctrl-C itself makes most commands exit non-zero.
    override fun completed(sessionName: String, completed: TerminalCommandResult.Completed) = ToolResult.success(
        message = "The command in \"$sessionName\" has stopped (exit code ${completed.exitCode})",
        data = TerminalOutput.tailOf(completed.output)
    )

    companion object {
        const val TOOL_NAME = "stop_terminal_session"
    }
}
