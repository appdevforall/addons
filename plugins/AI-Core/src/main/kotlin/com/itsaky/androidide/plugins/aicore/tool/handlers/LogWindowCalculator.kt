package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.services.LogEntry
import com.itsaky.androidide.plugins.services.LogLevel

/**
 * Selects the slice of a log the model needs, in whole lines and within [MAX_OUTPUT_CHARS]
 * including the truncation markers. Only the kept lines are ever joined into a string.
 */
internal object LogWindowCalculator {
    /** Maximum characters of log handed to the model, the same budget as build output. */
    const val MAX_OUTPUT_CHARS = 8000

    private const val TRUNCATION_MARKER = "...[truncated]..."

    /** Markers of an app crash; the anchor prefers these over any earlier plain error. */
    private val CRASH_MARKERS = listOf("FATAL EXCEPTION", "Fatal signal")

    /**
     * A crash's exception and top frames sit where it starts, not in the tail of later chatter,
     * so the window starts at the first crash line, else the first error line, and keeps the
     * head of what follows. With neither, it keeps the newest lines.
     */
    fun windowFor(entries: List<LogEntry>, hostTruncated: Boolean): OutputWindow {
        val anchor = entries.indexOfFirst { isCrashLine(it) }.takeIf { it >= 0 }
            ?: entries.indexOfFirst { it.level == LogLevel.ERROR }.takeIf { it >= 0 }
        val text = if (anchor != null) {
            head(entries.subList(anchor, entries.size), droppedBefore = hostTruncated || anchor > 0)
        } else {
            tail(entries, droppedBefore = hostTruncated)
        }
        return OutputWindow(text = text, anchoredOnError = anchor != null)
    }

    /** The oldest lines of [entries] that fit, marking what was dropped on either side. */
    private fun head(entries: List<LogEntry>, droppedBefore: Boolean): String {
        val prefix = if (droppedBefore) "$TRUNCATION_MARKER\n" else ""
        val room = MAX_OUTPUT_CHARS - prefix.length
        if (fitCount(entries, room) == entries.size) return prefix + joined(entries)

        val suffix = "\n$TRUNCATION_MARKER"
        val kept = fitCount(entries, room - suffix.length)
        val body = if (kept > 0) {
            joined(entries.subList(0, kept))
        } else {
            keepStart(entries.first().text, room - suffix.length)
        }
        return prefix + body + suffix
    }

    /** The newest lines of [entries] that fit, marking what was dropped before them. */
    private fun tail(entries: List<LogEntry>, droppedBefore: Boolean): String {
        val prefix = "$TRUNCATION_MARKER\n"
        val newestFirst = entries.asReversed()
        if (!droppedBefore && fitCount(newestFirst, MAX_OUTPUT_CHARS) == entries.size) {
            return joined(entries)
        }

        val room = MAX_OUTPUT_CHARS - prefix.length
        val kept = fitCount(newestFirst, room)
        val body = if (kept > 0) {
            joined(entries.subList(entries.size - kept, entries.size))
        } else {
            keepEnd(entries.last().text, room)
        }
        return prefix + body
    }

    /** How many of [entries], taken in order, fit in [room] characters once joined by newlines. */
    private fun fitCount(entries: List<LogEntry>, room: Int): Int {
        var used = -1 // the first line needs no separator
        var count = 0
        for (entry in entries) {
            used += entry.text.length + 1
            if (used > room) break
            count++
        }
        return count
    }

    private fun joined(entries: List<LogEntry>): String = entries.joinToString("\n") { it.text }

    /** The start of one oversized line, never splitting a surrogate pair. */
    private fun keepStart(text: String, room: Int): String {
        val end = if (text[room - 1].isHighSurrogate()) room - 1 else room
        return text.substring(0, end)
    }

    /** The end of one oversized line, never splitting a surrogate pair. */
    private fun keepEnd(text: String, room: Int): String {
        val start = text.length - room
        return text.substring(if (text[start].isLowSurrogate()) start + 1 else start)
    }

    private fun isCrashLine(entry: LogEntry): Boolean =
        CRASH_MARKERS.any { entry.text.contains(it, ignoreCase = true) }
}
