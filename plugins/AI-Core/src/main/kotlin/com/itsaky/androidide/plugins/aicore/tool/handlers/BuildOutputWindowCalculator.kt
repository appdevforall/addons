package com.itsaky.androidide.plugins.aicore.tool.handlers

/**
 * Selects the slice of a build log the model needs, shared by every tool that hands build output
 * back: read_build_output, run_gradle_task and quick_build.
 */
internal object BuildOutputWindowCalculator {
    /** Maximum characters of build log handed to the model. */
    const val MAX_OUTPUT_CHARS = 8000

    // The host strips line timing prefixes; tolerated here so the window is right either way.
    private val LINE_PREFIX = Regex("""^(?:\[\d{2}:\d{2}:\d{2}\.\d{3}] )?(?:Δ\d+ms\s+)?""")

    /**
     * Markers that begin the part of a build log worth reading. Warnings are deliberately
     * absent: a build with 200 warnings and one error must anchor on the error.
     */
    private val ERROR_MARKERS = listOf(
        Regex("""^e: """),
        Regex("""(?:^|\s)error:"""),
        Regex("""^FAILURE: Build failed"""),
        Regex("""^\* What went wrong:"""),
        Regex("""^Execution failed for task"""),
        Regex("""^BUILD FAILED"""),
        Regex("""^Caused by:"""),
    )

    /**
     * Selects the slice of [output] the model needs. A plain tail is the wrong window for a
     * failed build — the tail is the summary and boilerplate, while the compiler errors sit
     * hundreds of lines earlier — so the window starts at the first error line when there is one.
     */
    fun windowFor(output: String): OutputWindow {
        val errorOffset = firstErrorOffset(output)
        val body = if (errorOffset == null) output else output.substring(errorOffset)
        val overflows = body.length > MAX_OUTPUT_CHARS
        // A cascade of hundreds of errors still ends at the summary, so overflow re-tails.
        val text = if (overflows) body.takeLast(MAX_OUTPUT_CHARS) else body
        val dropped = overflows || (errorOffset ?: 0) > 0
        return OutputWindow(
            text = if (dropped) "$TRUNCATION_MARKER\n$text" else text,
            anchoredOnError = errorOffset != null && !overflows,
        )
    }

    /** The [windowFor] text of [output], or null when the host gave no output to read. */
    fun windowOrNull(output: String?): String? =
        output?.takeIf { it.isNotBlank() }?.let { windowFor(it).text }

    /** Character offset of the first line that looks like a compiler or Gradle failure. */
    private fun firstErrorOffset(output: String): Int? {
        var lineStart = 0
        while (true) {
            val newline = output.indexOf('\n', lineStart)
            val lineEnd = if (newline == -1) output.length else newline
            if (isErrorLine(output.substring(lineStart, lineEnd))) return lineStart
            if (newline == -1) return null
            lineStart = newline + 1
        }
    }

    private fun isErrorLine(line: String): Boolean {
        val body = LINE_PREFIX.replaceFirst(line, "")
        return ERROR_MARKERS.any { it.containsMatchIn(body) }
    }
}
