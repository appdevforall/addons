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
     * Logs span runs, so the window starts at whichever is newer, the newest crash line or the start
     * of the newest run of error lines. With neither, or a log of nothing but errors, it keeps the
     * newest lines.
     */
    fun windowFor(entries: List<LogEntry>, hostTruncated: Boolean): OutputWindow {
        val anchor = listOfNotNull(
            entries.indexOfLast { isCrashLine(it) }.takeIf { it >= 0 },
            newestErrorRunStart(entries),
        ).maxOrNull()
        val text = if (anchor != null) {
            fromAnchor(entries.subList(anchor, entries.size), droppedBefore = hostTruncated || anchor > 0)
        } else {
            tail(entries, droppedBefore = hostTruncated)
        }
        return OutputWindow(text = text, anchoredOnError = anchor != null)
    }

    /** [entries] whole if they fit, else their head and their newest lines, half the room each. */
    private fun fromAnchor(entries: List<LogEntry>, droppedBefore: Boolean): String {
        val prefix = if (droppedBefore) "$TRUNCATION_MARKER\n" else ""
        val room = MAX_OUTPUT_CHARS - prefix.length
        if (fitCount(entries, room) == entries.size) return prefix + joined(entries)

        val separator = "\n$TRUNCATION_MARKER\n"
        val half = (room - separator.length) / 2
        return prefix + headBody(entries, half) + separator + tailBody(entries, half)
    }

    /** The newest lines of [entries] that fit, marking what was dropped before them. */
    private fun tail(entries: List<LogEntry>, droppedBefore: Boolean): String {
        if (!droppedBefore && fitCount(entries, MAX_OUTPUT_CHARS) == entries.size) return joined(entries)
        val prefix = "$TRUNCATION_MARKER\n"
        return prefix + tailBody(entries, MAX_OUTPUT_CHARS - prefix.length)
    }

    /** The oldest whole lines of [entries] that fit in [room], else the start of the first. */
    private fun headBody(entries: List<LogEntry>, room: Int): String {
        val kept = fitCount(entries, room)
        return if (kept > 0) joined(entries.subList(0, kept)) else keepStart(entries.first().text, room)
    }

    /** The newest whole lines of [entries] that fit in [room], else the end of the last. */
    private fun tailBody(entries: List<LogEntry>, room: Int): String {
        val kept = fitCount(entries.asReversed(), room)
        return if (kept > 0) {
            joined(entries.subList(entries.size - kept, entries.size))
        } else {
            keepEnd(entries.last().text, room)
        }
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

    /**
     * The first line of the newest contiguous run of ERROR lines, so its trace keeps its head; null
     * when every line is an ERROR, where a head would keep the oldest errors.
     */
    private fun newestErrorRunStart(entries: List<LogEntry>): Int? {
        var start = entries.indexOfLast { it.level == LogLevel.ERROR }.takeIf { it >= 0 } ?: return null
        while (start > 0 && entries[start - 1].level == LogLevel.ERROR) start--
        return start.takeUnless { it == 0 && entries.last().level == LogLevel.ERROR }
    }

    private fun isCrashLine(entry: LogEntry): Boolean =
        CRASH_MARKERS.any { entry.text.contains(it, ignoreCase = true) }
}
