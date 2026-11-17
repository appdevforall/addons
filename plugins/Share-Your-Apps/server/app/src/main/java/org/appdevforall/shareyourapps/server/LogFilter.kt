package org.appdevforall.shareyourapps.server

/** The full-screen log view's search and filters, kept apart from the screen so they can be tested. */
object LogFilter {

    enum class Kind { ALL, REQUESTS, STATUS }

    /**
     * Status lines are the server's own, written by [StoreLog.status]: a timestamp in brackets, then a tag in
     * brackets, e.g. `[03/Oct/2026:17:30:20 -0700] [server] …`. Request lines start with the buyer's address.
     */
    private val STATUS = Regex("""^\[[^\]]+\] \[[A-Za-z0-9_-]+\]""")

    fun isStatus(line: String) = STATUS.containsMatchIn(line)

    class Result(val lines: List<String>, val matches: Int)

    /**
     * The lines matching [kind] and [query] (case-insensitive), newest first, at most [cap] of
     * them; [Result.matches] counts all matches, so the screen can say when it's showing fewer.
     */
    fun apply(fileLines: List<String>, kind: Kind, query: String, cap: Int): Result {
        val q = query.trim().lowercase()
        val out = ArrayList<String>(minOf(cap, fileLines.size))
        var matches = 0
        for (i in fileLines.indices.reversed()) {
            val l = fileLines[i]
            if (l.isEmpty()) continue
            val kindOk = when (kind) { Kind.ALL -> true; Kind.REQUESTS -> !isStatus(l); Kind.STATUS -> isStatus(l) }
            if (!kindOk || (q.isNotEmpty() && !l.lowercase().contains(q))) continue
            matches++
            if (out.size < cap) out += l
        }
        return Result(out, matches)
    }

    /** "Current" for the live file; otherwise how long ago it was last written, like "2h ago". */
    fun ageLabel(index: Int, lastModified: Long, now: Long): String {
        if (index == 0) return "Current"
        val mins = maxOf(0L, (now - lastModified) / 60_000)
        return when {
            mins < 60 -> "${mins}m ago"
            mins < 24 * 60 -> "${mins / 60}h ago"
            else -> "${mins / (24 * 60)}d ago"
        }
    }
}
