package com.itsaky.androidide.plugins.aicore.tool.handlers

/**
 * The slice of a Terminal command's output handed to the model: its end, where an error and the
 * final state show up. Shared by the shell tools, so a run and a later read look the same.
 */
internal object TerminalOutput {
    /** Maximum characters of output handed to the model; the end is kept. */
    const val MAX_CHARS = 8000

    private const val NO_OUTPUT = "(No output)"

    /** The last [MAX_CHARS] of [output], marked with [TRUNCATION_MARKER] when earlier output was cut. */
    fun tailOf(output: String): String = when {
        output.isBlank() -> NO_OUTPUT
        output.length <= MAX_CHARS -> output
        else -> "$TRUNCATION_MARKER\n${output.takeLast(MAX_CHARS)}"
    }
}
