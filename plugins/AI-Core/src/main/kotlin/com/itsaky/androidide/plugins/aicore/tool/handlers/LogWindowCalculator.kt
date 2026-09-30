package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.aicore.tool.AgentLoop
import com.itsaky.androidide.plugins.services.LogEntry
import com.itsaky.androidide.plugins.services.LogLevel

/**
 * Selects the slice of a log the model needs, in whole lines and within [MAX_OUTPUT_CHARS]
 * including the truncation markers. Only the kept lines are ever joined into a string.
 */
internal object LogWindowCalculator {
    /** Maximum characters of log handed to the model: AgentLoop's cap, less room for the message. */
    const val MAX_OUTPUT_CHARS = AgentLoop.DEFAULT_TOOL_OUTPUT_CHAR_LIMIT - 100

    /** Markers of an app crash; the anchor prefers these over any plain error. */
    private val CRASH_MARKERS = listOf("FATAL EXCEPTION", "Fatal signal")

    /**
     * Logs span runs, so the window starts at the newest crash line, else the start of the newest
     * run of error lines, and keeps the head of what follows. With neither, it keeps the newest
     * lines.
     */
    fun windowFor(entries: List<LogEntry>, hostTruncated: Boolean): OutputWindow {
        val anchor = entries.indexOfLast { isCrashLine(it) }.takeIf { it >= 0 }
            ?: newestErrorRunStart(entries)
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

    /** The first line of the newest contiguous run of ERROR lines, so its trace keeps its head. */
    private fun newestErrorRunStart(entries: List<LogEntry>): Int? {
        var start = entries.indexOfLast { it.level == LogLevel.ERROR }.takeIf { it >= 0 } ?: return null
        while (start > 0 && entries[start - 1].level == LogLevel.ERROR) start--
        return start
    }

    private fun isCrashLine(entry: LogEntry): Boolean =
        CRASH_MARKERS.any { entry.text.contains(it, ignoreCase = true) }
}
