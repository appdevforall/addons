package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.aicore.tool.handlers.RunShellCommandHandler.Companion.ARG_COMMAND
import com.itsaky.androidide.plugins.aicore.tool.handlers.RunShellCommandHandler.Companion.ARG_WORKING_DIRECTORY

/**
 * A `run_shell_command` call as the host takes it: the command exactly as the model wrote it, and
 * the working directory, null for the project root.
 */
internal data class ShellInvocation(
    val command: String,
    val workingDirectory: String?,
) {
    companion object {
        /** The call in [args], or null when it names no command. */
        fun from(args: Map<String, Any?>): ShellInvocation? =
            args[ARG_COMMAND]?.toString()
                ?.takeIf(String::isNotBlank)
                ?.let { command ->
                    ShellInvocation(
                        command = command,
                        workingDirectory = args[ARG_WORKING_DIRECTORY]?.toString()?.trim()?.takeIf(String::isNotEmpty),
                    )
                }
    }
}
