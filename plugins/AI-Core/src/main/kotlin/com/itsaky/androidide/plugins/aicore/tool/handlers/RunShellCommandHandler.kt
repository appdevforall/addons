package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.HostServiceCall.Companion.SHELL_STAGE
import com.itsaky.androidide.plugins.services.IdeTerminalService
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import kotlin.time.measureTimedValue

/**
 * Handler for running a shell command or script with bash in the IDE's visible Terminal, so the
 * user sees what ran. The host reuses an idle session and opens another only while one is busy,
 * e.g. with a dev server. Stop interrupts the command; Android's sandbox bounds what it can reach.
 */
class RunShellCommandHandler(
    pluginContext: PluginContext,
) : ToolHandler {
    override val toolName = TOOL_NAME

    // Runs whatever the model wrote, so the user reads the command before it runs.
    override val requiresApproval = true
    override val approvalPreview = ApprovalPreview.SHELL_COMMAND

    // Approval is keyed by tool name, so a session grant would cover every later command.
    override val allowsSessionApproval = false

    // sed -i, rm or a script can rewrite any project file, so a run with one is no longer read-only.
    override val mutatesProject = true

    // Checked against the project root before the user is asked, so a doomed call costs no dialog.
    override val pathArgs = listOf(ARG_WORKING_DIRECTORY)

    // Where the command runs is not what it writes, so the progress guard treats every file as touched.
    override val trackedPathArgs = emptyList<String>()

    override val parametersSchema = ToolSchema.objectOf(
        ARG_COMMAND to ToolSchema.string(),
        ARG_WORKING_DIRECTORY to ToolSchema.string(),
        required = listOf(ARG_COMMAND),
    )

    override val argAliases = mapOf(
        "cmd" to ARG_COMMAND,
        "script" to ARG_COMMAND,
        "shell_command" to ARG_COMMAND,
        "cwd" to ARG_WORKING_DIRECTORY,
        "directory" to ARG_WORKING_DIRECTORY,
        "working_dir" to ARG_WORKING_DIRECTORY,
    )

    private val terminalCall = HostServiceCall(
        pluginContext, toolName, IdeTerminalService::class.java, SHELL_STAGE, "Terminal",
    )

    override suspend fun validate(args: Map<String, Any?>): Validation =
        ShellInvocation.from(args)?.let { Validation.Accepted(args) } ?: Validation.Rejected(NO_COMMAND)

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val invocation = ShellInvocation.from(args) ?: return NO_COMMAND

        return terminalCall.run { terminal ->
            AgentTrace.stage(SHELL_STAGE, "$toolName cwd=${invocation.workingDirectory ?: "<project root>"}")
            // No timeout here: the host returns Running once its wait ends. Stop sends Ctrl-C.
            val (outcome, waited) = measureTimedValue {
                terminal.runInTerminal(invocation.command, invocation.workingDirectory)
            }
            AgentTrace.stage(
                SHELL_STAGE,
                "$toolName outcome=${outcome::class.simpleName} waitedMs=${waited.inWholeMilliseconds}"
            )
            resultFor(outcome)
        }
    }

    companion object {
        const val TOOL_NAME = "run_shell_command"
        const val ARG_COMMAND = "command"
        const val ARG_WORKING_DIRECTORY = "working_directory"

        // Naming the stop tool as a next step made Gemini stop a ping the user had just started.
        private const val STILL_RUNNING_NOTE =
            "The command has not exited and keeps running, e.g. a server, a watch task or a ping; " +
                "the output below is what it printed so far. That is expected and the command " +
                "succeeded: report this output. Do not run it again, and leave it running unless " +
                "the user asks you to stop it. Its command id is what " +
                "${ReadTerminalCommandHandler.TOOL_NAME} and ${StopTerminalCommandHandler.TOOL_NAME} " +
                "take. Another command runs in a separate Terminal session meanwhile."

        private val NO_COMMAND = ToolResult.failure(
            "command is required",
            "Give the shell command or script to run, e.g. command=\"ls -la\"."
        )

        /**
         * The tool result for [outcome]: a command that exited succeeds whatever its code, as grep
         * finding nothing exits 1; one still running (a server) succeeds with its output so far.
         */
        // The else is for a result a newer host adds; without it that result throws at runtime.
        @Suppress("REDUNDANT_ELSE_IN_WHEN")
        internal fun resultFor(outcome: TerminalCommandResult): ToolResult = when (outcome) {
            is TerminalCommandResult.Completed -> ToolResult.success(
                message = "Command ${TerminalOutput.exitOf(outcome.exitCode)}",
                data = TerminalOutput.tailOf(outcome.output)
            )
            is TerminalCommandResult.Running -> ToolResult.success(
                message = "Command is still running in Terminal session \"${outcome.sessionName}\" " +
                    "with command id \"${outcome.commandId}\"",
                data = "$STILL_RUNNING_NOTE\n\n${TerminalOutput.tailOf(outcome.output)}"
            )
            is TerminalCommandResult.NotStarted -> ToolResult.failure(
                "Command did not start: ${outcome.reason}",
                "Nothing ran. Tell the user why; retrying the same command will not help."
            )
            else -> ToolResult.failure("Command ended with an unknown result: $outcome")
        }
    }
}
