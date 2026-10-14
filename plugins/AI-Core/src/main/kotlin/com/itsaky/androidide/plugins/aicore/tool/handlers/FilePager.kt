package com.itsaky.androidide.plugins.aicore.tool.handlers

/**
 * Where a read starts and stops, 1-based. [startColumn] continues a line too long for one page;
 * [endLine] null reads to the end of the file.
 */
internal data class PageRequest(
    val startLine: Int = 1,
    val startColumn: Int = 1,
    val endLine: Int? = null,
)

/** Where the following page of a read starts, 1-based. */
internal data class PagePosition(val line: Int, val column: Int = 1)

/**
 * One page of a file: [text] is a verbatim slice, line terminators included, so consecutive pages
 * concatenate to the exact file.
 * @property endColumn the last column kept when the page stops mid-line; null when it ends a line.
 * @property next where the rest of the request starts; null when the page finished it.
 */
internal data class FilePage(
    val text: String,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int?,
    val next: PagePosition?,
    val totalLines: Int,
    val totalChars: Int,
)

/** What [FilePager.page] made of a request. */
internal sealed interface PageResult {
    data class Page(val page: FilePage) : PageResult

    /** The request names lines or columns the file does not have; [reason] says which. */
    data class OutOfRange(val reason: String) : PageResult
}

/**
 * Splits a file's text into pages of whole lines that fit a character budget. A line longer than
 * the budget is split by column instead, never inside a surrogate pair. Lines end at `\n`; a `\r`
 * before it stays part of the line, so CRLF files round-trip.
 */
internal class FilePager(private val content: String) {

    /** Offset of each line's first character. */
    private val lineStarts: IntArray = buildList {
        if (content.isNotEmpty()) add(0)
        content.forEachIndexed { index, char ->
            if (char == '\n' && index + 1 < content.length) add(index + 1)
        }
    }.toIntArray()

    /** Lines in the file; a final newline does not start another. */
    val lineCount: Int get() = lineStarts.size

    /**
     * The page [request] asks for, at most [room] characters long.
     * @param room the characters the page may hold; at least 2, so a surrogate pair always fits.
     */
    fun page(request: PageRequest, room: Int): PageResult {
        require(room >= 2) { "room must hold a surrogate pair, was $room" }
        rangeProblem(request)?.let { return PageResult.OutOfRange(it) }

        val from = lineStarts[request.startLine - 1] + request.startColumn - 1
        val lastLine = minOf(request.endLine ?: lineCount, lineCount)
        val lastFitting = (request.startLine..lastLine).lastOrNull { lineEnd(it) - from <= room }

        val page = if (lastFitting != null) {
            wholeLines(request, from, lastFitting, lastLine)
        } else {
            columnSlice(request, from, room)
        }
        return PageResult.Page(page)
    }

    /** From [from] to the end of line [last], continuing at the line after it when short of [lastLine]. */
    private fun wholeLines(request: PageRequest, from: Int, last: Int, lastLine: Int) = FilePage(
        text = content.substring(from, lineEnd(last)),
        startLine = request.startLine,
        startColumn = request.startColumn,
        endLine = last,
        endColumn = null,
        next = if (last < lastLine) PagePosition(last + 1) else null,
        totalLines = lineCount,
        totalChars = content.length,
    )

    /** The part of one over-long line that fits, continuing at the column after it. */
    private fun columnSlice(request: PageRequest, from: Int, room: Int): FilePage {
        val cut = (from + room).let { if (content[it - 1].isHighSurrogate()) it - 1 else it }
        val lineStart = lineStarts[request.startLine - 1]
        return FilePage(
            text = content.substring(from, cut),
            startLine = request.startLine,
            startColumn = request.startColumn,
            endLine = request.startLine,
            endColumn = cut - lineStart,
            next = PagePosition(request.startLine, cut - lineStart + 1),
            totalLines = lineCount,
            totalChars = content.length,
        )
    }

    private fun rangeProblem(request: PageRequest): String? = with(request) {
        when {
            lineCount == 0 -> "The file is empty."
            startLine < 1 -> "start_line must be 1 or more, was $startLine."
            startLine > lineCount -> "start_line $startLine is past the end of the file ($lineCount lines)."
            endLine != null && endLine < startLine -> "end_line $endLine is before start_line $startLine."
            startColumn < 1 -> "start_column must be 1 or more, was $startColumn."
            startColumn > lineSpan(startLine) ->
                "start_column $startColumn is past the end of line $startLine (${lineSpan(startLine)} characters)."
            else -> null
        }
    }

    /** Offset just past line [line], its `\n` included. */
    private fun lineEnd(line: Int): Int = if (line < lineCount) lineStarts[line] else content.length

    /** Characters on line [line], its `\n` included: a column slice may stop just before it. */
    private fun lineSpan(line: Int): Int = lineEnd(line) - lineStarts[line - 1]
}
