package com.itsaky.androidide.plugins.aicore.tool.handlers

/**
 * Selects the slice of a build log the model needs, shared by every tool that hands build output
 * back: read_build_output, run_gradle_task and quick_build.
 */
internal object BuildOutputWindowCalculator {
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
     * @param maxChars the most the window may hold, its truncation marker included.
     */
    fun windowFor(output: String, maxChars: Int): OutputWindow {
        val errorOffset = firstErrorOffset(output)
        val body = if (errorOffset == null) output else output.substring(errorOffset)
        val droppedHead = (errorOffset ?: 0) > 0
        val overflows = body.length > if (droppedHead) maxChars - MARKER_LINE else maxChars
        // A cascade of hundreds of errors still ends at the summary, so overflow re-tails.
        val text = if (overflows) tailOf(body, (maxChars - MARKER_LINE).coerceAtLeast(0)) else body
        val dropped = overflows || droppedHead
        return OutputWindow(
            text = if (dropped) "$TRUNCATION_MARKER\n$text" else text,
            anchoredOnError = errorOffset != null && !overflows,
        )
    }

    /** The [windowFor] text of [output], or null when the host gave no output to read. */
    fun windowOrNull(output: String?, maxChars: Int): String? =
        output?.takeIf { it.isNotBlank() }?.let { windowFor(it, maxChars).text }

    private const val MARKER_LINE = TRUNCATION_MARKER.length + 1

    /** The last [room] characters of [text], starting at a line when one fits. */
    private fun tailOf(text: String, room: Int): String {
        val tail = text.takeLast(room)
        val lineStart = tail.indexOf('\n')
        return if (lineStart in 0 until tail.lastIndex) tail.substring(lineStart + 1) else tail
    }

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
