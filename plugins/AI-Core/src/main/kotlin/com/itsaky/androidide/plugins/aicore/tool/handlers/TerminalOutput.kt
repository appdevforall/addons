package com.itsaky.androidide.plugins.aicore.tool.handlers

/**
 * The slice of a Terminal command's output handed to the model: its end, where an error and the
 * final state show up. Shared by the shell tools, so a run and a later read look the same.
 */
internal object TerminalOutput {
    private const val NO_OUTPUT = "(No output)"

    private const val MARKER_LINE = TRUNCATION_MARKER.length + 1

    /**
     * The end of [output], marked with [TRUNCATION_MARKER] when earlier output was cut.
     * @param maxChars the most the slice may hold, its marker included.
     */
    fun tailOf(output: String, maxChars: Int): String = when {
        output.isBlank() -> NO_OUTPUT
        output.length <= maxChars -> output
        else -> "$TRUNCATION_MARKER\n${output.takeLast((maxChars - MARKER_LINE).coerceAtLeast(0))}"
    }

    /** How a command with [exitCode] ended, as a predicate; the host reports -1 when it could not tell. */
    fun exitOf(exitCode: Int): String =
        if (exitCode == UNKNOWN_EXIT_CODE) "exited, but the Terminal could not tell its exit code"
        else "exited with code $exitCode"

    private const val UNKNOWN_EXIT_CODE = -1
}
